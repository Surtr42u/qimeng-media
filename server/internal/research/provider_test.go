package research

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"qimeng-media/server/internal/config"
)

// newTestService 启动 httptest 假上游并把全部 provider 基地址指向它；
// 安全客户端由 AllowLoopbackEndpoints 显式放行回环（生产恒 false）。
func newTestService(t *testing.T, handler http.Handler, tweak func(*config.ResearchConfig)) (*Service, *time.Time) {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	now := time.Date(2026, 11, 6, 12, 0, 0, 0, time.UTC)
	clock := &now
	cfg := config.ResearchConfig{
		Enabled: true, LensPerHour: 20, LensPerDay: 100, ProviderPerMinute: 20,
		EvidenceDir: t.TempDir(),
	}
	if tweak != nil {
		tweak(&cfg)
	}
	endpoints := make(map[Name]string, len(providerOrder))
	for _, name := range providerOrder {
		endpoints[name] = server.URL
	}
	svc, err := New(Options{
		Config: cfg, DataDir: t.TempDir(),
		Logger:    slog.New(slog.NewTextHandler(io.Discard, nil)),
		Now:       func() time.Time { return *clock },
		Endpoints: endpoints, AllowLoopbackEndpoints: true,
	})
	if err != nil {
		t.Fatalf("构造研究服务失败: %v", err)
	}
	return svc, clock
}

// writeJSON 是假上游的统一响应助手（显式 content-type，避免客户端猜类型）。
func writeJSON(t *testing.T, w http.ResponseWriter, body string) {
	t.Helper()
	w.Header().Set("Content-Type", "application/json")
	_, _ = io.WriteString(w, body)
}

// TestFxTwitterProvider：账号资料与帖子解析（含 @ 前缀归一与 extra 字段）。
func TestFxTwitterProvider(t *testing.T) {
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if ua := r.Header.Get("User-Agent"); !strings.Contains(ua, "QimengMedia-Research") {
			t.Errorf("User-Agent = %q, 期望研究标识", ua)
		}
		switch r.URL.Path {
		case "/johndoe":
			writeJSON(t, w, `{"code":200,"message":"OK","user":{"id":"123","screen_name":"JohnDoe","name":"John","description":"bio","followers":10,"joined":"2020-01-01T00:00:00.000Z","website":{"url":"https://example.com"}}}`)
		case "/status/999":
			writeJSON(t, w, `{"code":200,"message":"OK","tweet":{"id":"999","url":"https://x.com/JohnDoe/status/999","text":"Hello world","created_at":"Wed Nov 06 00:00:00 +0000 2026","likes":5,"retweets":1,"replies":2,"author":{"id":"123","screen_name":"JohnDoe","name":"John"}}}`)
		default:
			http.NotFound(w, r)
		}
	})
	svc, clock := newTestService(t, handler, nil)

	author, err := svc.Query(context.Background(), NameFxTwitter, Query{Operation: OperationAuthor, Target: "@johndoe"})
	if err != nil {
		t.Fatalf("author 查询失败: %v", err)
	}
	if author.Status != StatusOk || len(author.Items) != 1 {
		t.Fatalf("author 结果 = %+v", author)
	}
	if author.Items[0].URL != "https://x.com/JohnDoe" || author.Items[0].ID != "123" || author.Items[0].Author != "JohnDoe" {
		t.Errorf("author item = %+v", author.Items[0])
	}
	if author.Items[0].Extra["followers"] != "10" || author.Items[0].Extra["website"] != "https://example.com" {
		t.Errorf("author extra = %+v", author.Items[0].Extra)
	}
	if !author.FetchedAt.Equal(*clock) {
		t.Errorf("FetchedAt = %v, 期望注入时钟", author.FetchedAt)
	}

	post, err := svc.Query(context.Background(), NameFxTwitter, Query{Operation: OperationPost, Target: "999"})
	if err != nil {
		t.Fatalf("post 查询失败: %v", err)
	}
	if post.Status != StatusOk || len(post.Items) != 1 {
		t.Fatalf("post 结果 = %+v", post)
	}
	if post.Items[0].Title != "Hello world" || post.Items[0].Extra["likes"] != "5" {
		t.Errorf("post item = %+v", post.Items[0])
	}
	if post.Items[0].Domain != "x.com" {
		t.Errorf("post domain = %q", post.Items[0].Domain)
	}
}

// TestFxTwitterPostIDFromURL：帖子链接里的 /status/{id} 也能作为 target。
func TestFxTwitterPostIDFromURL(t *testing.T) {
	id, ok := fxStatusID("https://x.com/someone/status/123456?ref=abc")
	if !ok || id != "123456" {
		t.Fatalf("fxStatusID = %q,%v", id, ok)
	}
	if _, ok := fxStatusID("https://x.com/someone"); ok {
		t.Fatal("非 status 链接应被拒绝")
	}
}

// TestPixivProvider：账号/作品解析，且请求必须带 Referer（缺了会被平台拒）。
func TestPixivProvider(t *testing.T) {
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("Referer"); got != "https://www.pixiv.net/" {
			t.Errorf("Referer = %q, 期望 pixiv 站点", got)
		}
		switch r.URL.Path {
		case "/ajax/user/42":
			writeJSON(t, w, `{"error":false,"message":"","body":{"userId":"42","name":"PixivArtist","comment":"hello","webpage":"https://example.com","image":"https://i.pximg.net/a.jpg"}}`)
		case "/ajax/illust/777":
			writeJSON(t, w, `{"error":false,"message":"","body":{"illustId":"777","illustTitle":"Work Title","userId":"42","userName":"PixivArtist","uploadDate":"2024-01-01T00:00:00+09:00","pageCount":2,"tags":{"tags":[{"tag":"a"},{"tag":"b"}]}}}`)
		case "/ajax/illust/404":
			writeJSON(t, w, `{"error":true,"message":"该作品不存在"}`)
		default:
			http.NotFound(w, r)
		}
	})
	svc, _ := newTestService(t, handler, nil)

	author, err := svc.Query(context.Background(), NamePixiv, Query{Operation: OperationAuthor, Target: "42"})
	if err != nil || author.Status != StatusOk {
		t.Fatalf("pixiv author = %+v, err=%v", author, err)
	}
	if author.Items[0].URL != "https://www.pixiv.net/users/42" || author.Items[0].Author != "PixivArtist" {
		t.Errorf("pixiv author item = %+v", author.Items[0])
	}

	work, err := svc.Query(context.Background(), NamePixiv, Query{Operation: OperationWork, Target: "777"})
	if err != nil || work.Status != StatusOk {
		t.Fatalf("pixiv work = %+v, err=%v", work, err)
	}
	if work.Items[0].URL != "https://www.pixiv.net/artworks/777" || work.Items[0].Extra["pageCount"] != "2" {
		t.Errorf("pixiv work item = %+v", work.Items[0])
	}
	if work.Items[0].Extra["tags"] != "a, b" {
		t.Errorf("pixiv tags = %q", work.Items[0].Extra["tags"])
	}

	missing, err := svc.Query(context.Background(), NamePixiv, Query{Operation: OperationWork, Target: "404"})
	if err != nil {
		t.Fatalf("pixiv 404 查询失败: %v", err)
	}
	if missing.Status != StatusNotFound || missing.Err == nil || missing.Err.Code != "NOT_FOUND" {
		t.Fatalf("pixiv 404 = %+v", missing)
	}

	if _, err := svc.Query(context.Background(), NamePixiv, Query{Operation: OperationWork, Target: "abc"}); err != ErrInvalidTarget {
		t.Fatalf("非数字作品 ID 应为 ErrInvalidTarget, got %v", err)
	}
}

// TestYandereProvider：画师与标签检索。
func TestYandereProvider(t *testing.T) {
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/artist.json":
			if got := r.URL.Query().Get("name"); got != "Artist" {
				t.Errorf("artist name = %q", got)
			}
			writeJSON(t, w, `[{"id":5,"name":"Artist","alias":["Alias A","Alias B"],"urls":[{"url":"https://example.com/a"}]}]`)
		case "/post.json":
			if got := r.URL.Query().Get("limit"); got != "3" {
				t.Errorf("post limit = %q, 期望 3", got)
			}
			writeJSON(t, w, `[{"id":9,"tags":"tag1 tag2","created_at":1700000000,"file_url":"https://files.yande.re/x.jpg","score":3,"source":"https://example.com"}]`)
		default:
			http.NotFound(w, r)
		}
	})
	svc, _ := newTestService(t, handler, nil)

	artist, err := svc.Query(context.Background(), NameYandere, Query{Operation: OperationArtist, Target: "Artist"})
	if err != nil || artist.Status != StatusOk {
		t.Fatalf("yandere artist = %+v, err=%v", artist, err)
	}
	if artist.Items[0].URL != "https://yande.re/artist/show/5" || artist.Items[0].Extra["alias"] != "Alias A, Alias B" {
		t.Errorf("yandere artist item = %+v", artist.Items[0])
	}

	posts, err := svc.Query(context.Background(), NameYandere, Query{Operation: OperationTags, Target: "tag1 tag2", Limit: 3})
	if err != nil || posts.Status != StatusOk {
		t.Fatalf("yandere posts = %+v, err=%v", posts, err)
	}
	if posts.Items[0].URL != "https://yande.re/post/show/9" || posts.Items[0].PublishedAt == "" {
		t.Errorf("yandere post item = %+v", posts.Items[0])
	}
}

// TestE621Provider：e621 必须带自定义 UA；帖子与标签搜索解析。
func TestE621Provider(t *testing.T) {
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if ua := r.Header.Get("User-Agent"); !strings.Contains(ua, "QimengMedia-Research") {
			t.Errorf("e621 User-Agent = %q", ua)
		}
		switch r.URL.Path {
		case "/posts/31.json":
			writeJSON(t, w, `{"post":{"id":31,"created_at":"2024-01-02T03:04:05.000-05:00","description":"Title line
more","rating":"s","score":{"total":5},"tags":{"artist":["foo"],"general":["bar"]}}}`)
		case "/posts.json":
			writeJSON(t, w, `{"posts":[{"id":32,"created_at":"2024-02-02T00:00:00.000Z","description":"Second","score":{"total":1},"tags":{"artist":["baz"],"general":[]}}]}`)
		default:
			http.NotFound(w, r)
		}
	})
	svc, _ := newTestService(t, handler, nil)

	post, err := svc.Query(context.Background(), NameE621, Query{Operation: OperationPost, Target: "31"})
	if err != nil || post.Status != StatusOk {
		t.Fatalf("e621 post = %+v, err=%v", post, err)
	}
	if post.Items[0].URL != "https://e621.net/posts/31" || post.Items[0].Title != "Title line" {
		t.Errorf("e621 post item = %+v", post.Items[0])
	}
	if post.Items[0].Author != "foo" || post.Items[0].Extra["score"] != "5" {
		t.Errorf("e621 extra = %+v", post.Items[0])
	}

	posts, err := svc.Query(context.Background(), NameE621, Query{Operation: OperationTags, Target: "bar", Limit: 5})
	if err != nil || posts.Status != StatusOk || len(posts.Items) != 1 {
		t.Fatalf("e621 tags = %+v, err=%v", posts, err)
	}
}

// TestWaybackProvider：CDX JSON 表头解析与存档链接拼装。
func TestWaybackProvider(t *testing.T) {
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/cdx/search/cdx" {
			http.NotFound(w, r)
			return
		}
		if got := r.URL.Query().Get("url"); got != "https://example.com/a" {
			t.Errorf("cdx url = %q", got)
		}
		writeJSON(t, w, `[["timestamp","original","mimetype","statuscode"],["20240101000000","https://example.com/a","text/html","200"]]`)
	})
	svc, _ := newTestService(t, handler, nil)

	res, err := svc.Query(context.Background(), NameWayback, Query{Operation: OperationURL, Target: "https://example.com/a"})
	if err != nil || res.Status != StatusOk {
		t.Fatalf("wayback = %+v, err=%v", res, err)
	}
	if res.Items[0].URL != "https://web.archive.org/web/20240101000000/https://example.com/a" {
		t.Errorf("wayback item url = %q", res.Items[0].URL)
	}
	if res.Items[0].PublishedAt != "2024-01-01T00:00:00Z" || res.Items[0].Extra["statusCode"] != "200" {
		t.Errorf("wayback item = %+v", res.Items[0])
	}

	empty := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(t, w, "[]")
	}))
	defer empty.Close()
	emptySvc, err := New(Options{Config: config.ResearchConfig{Enabled: true, EvidenceDir: t.TempDir(), LensPerHour: 20, LensPerDay: 100, ProviderPerMinute: 20},
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)), Endpoints: map[Name]string{NameWayback: empty.URL}, AllowLoopbackEndpoints: true})
	if err != nil {
		t.Fatalf("构造空上游服务失败: %v", err)
	}
	none, err := emptySvc.Query(context.Background(), NameWayback, Query{Operation: OperationURL, Target: "https://example.com/none"})
	if err != nil || none.Status != StatusNotFound {
		t.Fatalf("空 CDX = %+v, err=%v", none, err)
	}
}

// TestSteamProvider：POST 表单与创意工坊条目解析。
func TestSteamProvider(t *testing.T) {
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || r.URL.Path != "/ISteamRemoteStorage/GetPublishedFileDetails/v1/" {
			http.NotFound(w, r)
			return
		}
		body, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(body), "publishedfileids%5B0%5D=123") {
			t.Errorf("steam form body = %q", body)
		}
		writeJSON(t, w, `{"response":{"result":1,"resultcount":1,"publishedfiledetails":[{"publishedfileid":"123","result":1,"title":"Mod","description":"desc","creator":"7656119","time_created":1700000000,"preview_url":"https://example.com/p.jpg","consumer_app_id":294100}]}}`)
	})
	svc, _ := newTestService(t, handler, nil)

	res, err := svc.Query(context.Background(), NameSteam, Query{Operation: OperationWorkshop, Target: "123"})
	if err != nil || res.Status != StatusOk {
		t.Fatalf("steam = %+v, err=%v", res, err)
	}
	if res.Items[0].URL != "https://steamcommunity.com/sharedfiles/filedetails/?id=123" || res.Items[0].Extra["creator"] != "7656119" {
		t.Errorf("steam item = %+v", res.Items[0])
	}
	if res.Items[0].PublishedAt != "2023-11-14T22:13:20Z" {
		t.Errorf("steam publishedAt = %q", res.Items[0].PublishedAt)
	}
}

// TestBingProvider：b_algo 提取（含 title/href/domain）与空结果。
func TestBingProvider(t *testing.T) {
	page := `<html><body><ol>
<li class="b_algo"><h2><a href="https://example.com/x">Example Title</a></h2><div class="b_caption"><p>snippet</p></div></li>
<li class="b_algo"><h2><a href="https://second.example/y">Second</a></h2></li>
</ol></body></html>`
	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/search" {
			http.NotFound(w, r)
			return
		}
		if got := r.URL.Query().Get("q"); got != "query text" {
			t.Errorf("bing q = %q", got)
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = io.WriteString(w, page)
	})
	svc, _ := newTestService(t, handler, nil)

	res, err := svc.Query(context.Background(), NameBing, Query{Operation: OperationSearch, Target: "query text", Limit: 5})
	if err != nil || res.Status != StatusOk {
		t.Fatalf("bing = %+v, err=%v", res, err)
	}
	if len(res.Items) != 2 {
		t.Fatalf("bing items = %d, 期望 2", len(res.Items))
	}
	if res.Items[0].Title != "Example Title" || res.Items[0].URL != "https://example.com/x" || res.Items[0].Domain != "example.com" {
		t.Errorf("bing first item = %+v", res.Items[0])
	}
}

// TestProviderMatrixRejectsUnsupported：矩阵外的组合必须在 service 层拒绝。
func TestProviderMatrixRejectsUnsupported(t *testing.T) {
	svc, _ := newTestService(t, http.NotFoundHandler(), nil)
	cases := []struct {
		name Name
		op   Operation
	}{
		{NameFxTwitter, OperationSearch},
		{NamePixiv, OperationPost},
		{NameYandere, OperationWork},
		{NameE621, OperationAuthor},
		{NameWayback, OperationAuthor},
		{NameSteam, OperationTags},
		{NameBing, OperationPost},
	}
	for _, c := range cases {
		if _, err := svc.Query(context.Background(), c.name, Query{Operation: c.op, Target: "x"}); err != ErrUnsupportedOperation {
			t.Errorf("%s/%s = %v, 期望 ErrUnsupportedOperation", c.name, c.op, err)
		}
	}
	if _, err := svc.Query(context.Background(), Name("unknown"), Query{Operation: OperationAuthor, Target: "x"}); err != ErrUnknownProvider {
		t.Errorf("未知 provider = %v, 期望 ErrUnknownProvider", err)
	}
}

// TestProviderRateLimitStatus：每分钟预算耗尽返回 200 语义的 rate_limited。
func TestProviderRateLimitStatus(t *testing.T) {
	svc, _ := newTestService(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(t, w, `{"code":200,"user":{"id":"1","screen_name":"A","name":"A"}}`)
	}), func(c *config.ResearchConfig) { c.ProviderPerMinute = 1 })

	if _, err := svc.Query(context.Background(), NameFxTwitter, Query{Operation: OperationAuthor, Target: "a"}); err != nil {
		t.Fatalf("第一次查询失败: %v", err)
	}
	res, err := svc.Query(context.Background(), NameFxTwitter, Query{Operation: OperationAuthor, Target: "a"})
	if err != nil {
		t.Fatalf("限流不应返回 error: %v", err)
	}
	if res.Status != StatusRateLimited || res.Err == nil || res.Err.Code != "RATE_LIMITED" {
		t.Fatalf("限流结果 = %+v", res)
	}
}

// TestProviderUpstreamBlocked：上游 403 映射 blocked（平台 API 决定证据等级）。
func TestProviderUpstreamBlocked(t *testing.T) {
	svc, _ := newTestService(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
	}), nil)
	res, err := svc.Query(context.Background(), NameFxTwitter, Query{Operation: OperationAuthor, Target: "a"})
	if err != nil {
		t.Fatalf("403 不应返回协议错误: %v", err)
	}
	if res.Status != StatusBlocked || res.Err == nil {
		t.Fatalf("403 结果 = %+v", res)
	}
}

// TestProviderInvalidJSONIsUnavailable：结构不符按 provider_unavailable 处理。
func TestProviderInvalidJSONIsUnavailable(t *testing.T) {
	svc, _ := newTestService(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, "not json")
	}), nil)
	res, err := svc.Query(context.Background(), NameFxTwitter, Query{Operation: OperationAuthor, Target: "a"})
	if err != nil {
		t.Fatalf("解析失败不应返回协议错误: %v", err)
	}
	if res.Status != StatusProviderUnavailable || res.Err == nil || res.Err.Code != "INVALID_RESPONSE" {
		t.Fatalf("解析失败结果 = %+v", res)
	}
}
