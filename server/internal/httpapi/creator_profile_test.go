package httpapi

import (
	"encoding/json"
	"net/http"
	"reflect"
	"testing"

	"qimeng-media/server/internal/creatorprofile"
)

func readCreatorProfile(t *testing.T, env *testEnv, path string) creatorprofile.Profile {
	t.Helper()
	response := env.do(t, http.MethodGet, path, "")
	defer closeBody(response)
	if response.StatusCode != http.StatusOK {
		t.Fatalf("read profile: status %d", response.StatusCode)
	}
	var profile creatorprofile.Profile
	if err := decodeBody(response, &profile); err != nil {
		t.Fatal(err)
	}
	return profile
}

func TestCreatorAvatarProxyIsNotCachedAcrossProfileChanges(t *testing.T) {
	env := newTestEnv(t)
	code, author := env.createAuthor(t, "profile_avatar_cache")
	if code != http.StatusOK || author.Id == nil {
		t.Fatalf("create author: status %d", code)
	}
	// 空头像只走本地合成数据，不访问外站；成功与失败均使用相同代理缓存策略。
	response := env.do(t, http.MethodGet, "/api/v1/authors/"+*author.Id+"/profile/avatar", "")
	defer closeBody(response)
	if response.StatusCode != http.StatusNotFound {
		t.Fatalf("empty avatar: status %d", response.StatusCode)
	}
	if got := response.Header.Get("Cache-Control"); got != "no-store" {
		t.Fatalf("mutable avatar must bypass HTTP cache: got %q", got)
	}
}

func TestCreatorProfileRequiredFieldsDoNotEraseStoredData(t *testing.T) {
	env := newTestEnv(t)
	code, author := env.createAuthor(t, "profile_presence")
	if code != http.StatusOK || author.Id == nil {
		t.Fatalf("create author: status %d", code)
	}
	path := "/api/v1/authors/" + *author.Id + "/profile"
	empty := readCreatorProfile(t, env, path)
	body := map[string]any{
		"revision": empty.Revision, "nickname": "人工昵称", "biography": "保留人工简介",
		"avatarUrl": "https://example.org/avatar.png", "homepageUrl": "https://example.org/creator",
		"evidenceUrls": []string{"https://example.org/about"}, "status": "candidate",
		"autoRefresh": false, "feedUrl": "", "trackUpdates": false,
	}
	put := func(t *testing.T, payload map[string]any, expected int) {
		t.Helper()
		encoded, err := json.Marshal(payload)
		if err != nil {
			t.Fatal(err)
		}
		response := env.do(t, http.MethodPut, path, string(encoded))
		defer closeBody(response)
		if response.StatusCode != expected {
			t.Fatalf("put profile: status %d, want %d", response.StatusCode, expected)
		}
	}
	put(t, body, http.StatusOK)
	saved := readCreatorProfile(t, env, path)
	body["revision"] = saved.Revision
	for _, field := range []string{"revision", "nickname", "biography", "avatarUrl", "homepageUrl", "evidenceUrls", "status", "autoRefresh", "feedUrl", "trackUpdates"} {
		for _, mode := range []string{"missing", "null"} {
			t.Run(field+"/"+mode, func(t *testing.T) {
				bad := make(map[string]any, len(body))
				for key, value := range body {
					bad[key] = value
				}
				if mode == "missing" {
					delete(bad, field)
				} else {
					bad[field] = nil
				}
				put(t, bad, http.StatusBadRequest)
				if got := readCreatorProfile(t, env, path); !reflect.DeepEqual(got, saved) {
					t.Fatalf("invalid request modified profile: got %+v, want %+v", got, saved)
				}
			})
		}
	}
	// 显式提供空字符串、false、[] 才是用户要求清空，不能被 presence 校验误拒。
	put(t, map[string]any{
		"revision": saved.Revision, "nickname": "", "biography": "", "avatarUrl": "", "homepageUrl": "",
		"evidenceUrls": []string{}, "status": "empty", "autoRefresh": false, "feedUrl": "", "trackUpdates": false,
	}, http.StatusOK)
	cleared := readCreatorProfile(t, env, path)
	if cleared.Nickname != "" || cleared.Biography != "" || cleared.AvatarURL != "" || cleared.HomepageURL != "" ||
		len(cleared.EvidenceURLs) != 0 || cleared.Status != "empty" || cleared.AutoRefresh || cleared.TrackUpdates || cleared.FeedURL != "" {
		t.Fatalf("explicit clear did not apply: %+v", cleared)
	}
}
