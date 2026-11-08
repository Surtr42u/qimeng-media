package httpapi

import (
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"

	"qimeng-media/server/internal/creatorprofile"
	"qimeng-media/server/internal/httpapi/gen"
)

// TestImportRejectsInvalidAuthorProfileConflict：query 枚举非法值必须在任何
// 写入前返回 400 INVALID_PARAM。
func TestImportRejectsInvalidAuthorProfileConflict(t *testing.T) {
	env := newTestEnv(t)
	body := `{"format":"qimeng_backup","schemaVersion":2,"data":{"viewEvents":[]}}`
	response := env.do(t, http.MethodPost, "/api/v1/import/qimeng-backup?authorProfileConflict=overwrite", body)
	defer closeBody(response)
	var payload gen.Error
	if err := decodeBody(response, &payload); err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusBadRequest || payload.Code != codeInvalidParam {
		t.Fatalf("非法 authorProfileConflict 应 400 INVALID_PARAM，got %d %q", response.StatusCode, payload.Code)
	}
}

// TestImportReplaceAuthorProfileConflict：replace 以备份档案整份覆盖本机
// （含离线头像），Revision 递增且不再产生「保留本机」提示。
func TestImportReplaceAuthorProfileConflict(t *testing.T) {
	env := newTestEnv(t)
	code, author := env.createAuthor(t, "profile_replace")
	if code != http.StatusOK || author.Id == nil {
		t.Fatalf("create author: %d", code)
	}
	id := *author.Id
	local := creatorprofile.Empty(id)
	local.Status = "candidate"
	local.Nickname = "本机昵称"
	local.Biography = "本机简介"
	local.AvatarURL = "https://example.org/local-avatar.png"
	saved, err := env.s.creatorProfiles.Save(t.Context(), id, local.Write)
	if err != nil {
		t.Fatal(err)
	}

	incoming := creatorprofile.Empty(id)
	incoming.Status = "verified"
	incoming.Nickname = "备份昵称"
	incoming.Biography = "备份简介"
	incoming.HomepageURL = "https://example.org/creator"
	incoming.EvidenceURLs = []string{"https://example.org/evidence"}
	incoming.AvatarURL = "https://example.org/backup-avatar.png"
	profileBytes, err := json.Marshal(incoming)
	if err != nil {
		t.Fatal(err)
	}
	profileJSON, image := string(profileBytes), offlineAvatarPNG
	request := gen.LegacyBackupImport{Format: "qimeng_backup", SchemaVersion: portableSchemaVersion, AppIdentifier: "synthetic",
		Data: gen.LegacyBackupData{
			Authors:    &[]gen.LegacyAuthor{{AuthorId: id, DisplayName: "profile_replace", ProfileJson: &profileJSON, AvatarData: &image}},
			ViewEvents: &[]gen.PortableViewEvent{},
		}}
	raw, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	response := env.do(t, http.MethodPost, "/api/v1/import/qimeng-backup?authorProfileConflict=replace", string(raw))
	defer closeBody(response)
	if response.StatusCode != http.StatusOK {
		t.Fatalf("replace 导入应 200，got %d", response.StatusCode)
	}
	var result gen.LegacyImportResult
	if err := decodeBody(response, &result); err != nil {
		t.Fatal(err)
	}
	if result.AuthorProfilesReplaced == nil || *result.AuthorProfilesReplaced != 1 {
		t.Fatalf("AuthorProfilesReplaced = %v，want 1", result.AuthorProfilesReplaced)
	}
	if result.AuthorsImported == nil || *result.AuthorsImported != 1 {
		t.Fatalf("AuthorsImported = %v，want 1", result.AuthorsImported)
	}
	if result.Warnings != nil {
		for _, warning := range *result.Warnings {
			if strings.Contains(warning, "已保留本机已有资料") {
				t.Fatalf("replace 不应提示保留本机: %q", warning)
			}
		}
	}

	current := readCreatorProfile(t, env, "/api/v1/authors/"+id+"/profile")
	if current.Revision != saved.Revision+1 || current.Status != "verified" || current.Nickname != "备份昵称" ||
		current.Biography != "备份简介" || current.HomepageURL != incoming.HomepageURL || current.AvatarURL != incoming.AvatarURL {
		t.Fatalf("replace 未以备份整份覆盖: %+v", current)
	}
	response = env.do(t, http.MethodGet, "/api/v1/authors/"+id+"/profile/avatar", "")
	data, readErr := io.ReadAll(response.Body)
	closeBody(response)
	if readErr != nil || response.StatusCode != http.StatusOK || base64.StdEncoding.EncodeToString(data) != offlineAvatarPNG {
		t.Fatalf("replace 未采用备份离线头像: status=%d err=%v", response.StatusCode, readErr)
	}
}
