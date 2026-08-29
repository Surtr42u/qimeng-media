package httpapi

// SPA 托管测试：静态目录可用→托管 dist（直发/缓存头/前端路由回退/API 不吞）、
// 目录缺失→回退内嵌验收页（M1 行为）、/_debug/ 固定挂载。

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/thumbnail"
)

// writeDist 在 dir 下写一份最小 Web 构建产物（interop 与真实 vite 输出同款
// 布局：dist/index.html + dist/assets/*-hash.js + dist/icons/*.png）。
func writeDist(t *testing.T, dir string) {
	t.Helper()
	for _, sub := range []string{"assets", "icons"} {
		if err := os.MkdirAll(filepath.Join(dir, sub), 0o755); err != nil {
			t.Fatalf("创建 dist 子目录失败: %v", err)
		}
	}
	files := map[string]string{
		"index.html":         "<!doctype html><html><body>M2-DIST-INDEX</body></html>",
		"assets/app-hash.js": "console.log('dist')",
		"icons/favicon.png":  "PNGDATA",
	}
	for rel, content := range files {
		if err := os.WriteFile(filepath.Join(dir, filepath.FromSlash(rel)), []byte(content), 0o644); err != nil {
			t.Fatalf("写 dist 文件 %s 失败: %v", rel, err)
		}
	}
}

// newSPAEnv 建一个最小 SPA 托管测试环境（临时 data 目录 + 真实 SQLite + 指定
// Web.StaticDir）。不需要扫描器与媒体文件——SPA 用例只关心页面投放。
func newSPAEnv(t *testing.T, distDir string) *httptest.Server {
	t.Helper()
	root := t.TempDir()
	dataDir := filepath.Join(root, "data")
	media := filepath.Join(root, "media")
	for _, d := range []string{dataDir, media} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			t.Fatalf("创建目录失败: %v", err)
		}
	}
	conn, err := store.Open(filepath.Join(dataDir, "spa-test.db"))
	if err != nil {
		t.Fatalf("打开测试库失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := store.Migrate(conn); err != nil {
		t.Fatalf("迁移失败: %v", err)
	}
	q := db.New(conn)
	if _, err := q.CreateLibrary(context.Background(), db.CreateLibraryParams{
		ID: uuid.NewString(), Name: "SPA 库", RootPath: media,
		CreatedAt: store.FormatTimestamp(time.Now()),
	}); err != nil {
		t.Fatalf("建库失败: %v", err)
	}
	cfg := &config.Config{
		DataDir:   dataDir,
		Thumbnail: config.ThumbnailConfig{LongSide: 512},
		Web:       config.WebConfig{StaticDir: distDir},
	}
	apisrv, err := New(Deps{
		Conn: conn, Queries: q, Bus: events.NewBus(nil, 0), Cfg: cfg,
		Thumbs:      thumbnail.NewGenerator(dataDir, nil, thumbnail.Options{}),
		Scanner:     noScanner{},
		MediaSecret: []byte("spa-test-secret-0123456789"),
	})
	if err != nil {
		t.Fatalf("组装服务失败: %v", err)
	}
	// os.Root 持有 dist 目录句柄（Windows 下会阻止目录删除）：测试结束时
	// 必须先释放（生产过程句柄随进程生命周期，退出时由系统回收）。
	t.Cleanup(func() {
		if apisrv.spa != nil {
			_ = apisrv.spa.root.Close()
		}
	})
	ts := httptest.NewServer(apisrv.Handler())
	t.Cleanup(ts.Close)
	return ts
}

// TestSPAServedFromDist 静态目录可用时的完整投放面：
// 入口页直发 + no-cache、hash 静态资源 immutable、非文件路径回退 index.html、
// 带扩展名未命中 404（不被回退吞掉）、/api/ 前缀保持鉴权 401、
// /_debug/ 依然可达内嵌验收页。
func TestSPAServedFromDist(t *testing.T) {
	dist := filepath.Join(t.TempDir(), "dist")
	writeDist(t, dist)
	ts := newSPAEnv(t, dist)

	get := func(t *testing.T, path string) *http.Response {
		t.Helper()
		resp, err := http.Get(ts.URL + path)
		if err != nil {
			t.Fatalf("请求 %s 失败: %v", path, err)
		}
		return resp
	}

	// 入口页与显式 index.html：dist HTML 直发、no-cache。
	for _, p := range []string{"/", "/index.html"} {
		resp := get(t, p)
		body := readAll(t, resp)
		if resp.StatusCode != http.StatusOK || !strings.Contains(string(body), "M2-DIST-INDEX") {
			t.Fatalf("%s 应投放 dist index.html，得到 %d %s", p, resp.StatusCode, body)
		}
		if cc := resp.Header.Get("Cache-Control"); !strings.Contains(cc, "no-cache") {
			t.Errorf("%s 应带 no-cache 缓存头，得到 %q", p, cc)
		}
		if ct := resp.Header.Get("Content-Type"); !strings.Contains(ct, "text/html") {
			t.Errorf("%s 的 Content-Type 应为 text/html，得到 %q", p, ct)
		}
	}

	// hash 静态资源（/assets/**、/icons/**）：immutable 长缓存 + 内容直发。
	for _, c := range []struct{ path, wantCC, wantBody string }{
		{"/assets/app-hash.js", "immutable", "console.log('dist')"},
		{"/icons/favicon.png", "immutable", "PNGDATA"},
	} {
		resp := get(t, c.path)
		body := readAll(t, resp)
		if resp.StatusCode != http.StatusOK || string(body) != c.wantBody {
			t.Fatalf("%s 应直发 dist 文件，得到 %d %q", c.path, resp.StatusCode, body)
		}
		if cc := resp.Header.Get("Cache-Control"); !strings.Contains(cc, "immutable") {
			t.Errorf("%s 应带 immutable 缓存头（hash 文件名内容寻址），得到 %q", c.path, cc)
		}
	}

	// SPA 前端路由回退：未命中且无扩展名的路径 → index.html。
	resp := get(t, "/library/123/album")
	body := readAll(t, resp)
	if resp.StatusCode != http.StatusOK || !strings.Contains(string(body), "M2-DIST-INDEX") {
		t.Fatalf("前端路由应回退 index.html，得到 %d %s", resp.StatusCode, body)
	}

	// 带扩展名的未命中（构建改名后残留引用）→ 404，不回退。
	resp = get(t, "/assets/missing.js")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("缺失的 hash 资源应 404（回退会让浏览器拿 HTML 当 JS），得到 %d", resp.StatusCode)
	}

	// /api/ 前缀不吞：SPA 模式下 API 拼错路径仍走鉴权链（无 token → 401）。
	resp = get(t, "/api/v1/nonexistent")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("SPA 模式下 /api/ 路径仍应要求 Bearer，得到 %d", resp.StatusCode)
	}

	// /_debug/：SPA 可用时验收页依然可达（固定调试挂载点）。
	resp = get(t, "/_debug/")
	body = readAll(t, resp)
	if resp.StatusCode != http.StatusOK || !strings.Contains(string(body), "M1") {
		t.Fatalf("/_debug/ 应服务内嵌验收页，得到 %d", resp.StatusCode)
	}
}

// TestSPAFallbackToEmbedded 静态目录缺失（web/dist 未构建 = 开发常态）
// 时回退内嵌验收页：/、/index.html、/_debug/ 全部可用，服务不挂。
func TestSPAFallbackToEmbedded(t *testing.T) {
	missing := filepath.Join(t.TempDir(), "no-dist-here")
	ts := newSPAEnv(t, missing)
	for _, p := range []string{"/", "/index.html", "/_debug/"} {
		resp, err := http.Get(ts.URL + p)
		if err != nil {
			t.Fatalf("请求 %s 失败: %v", p, err)
		}
		body := readAll(t, resp)
		if resp.StatusCode != http.StatusOK || !strings.Contains(string(body), "M1") {
			t.Fatalf("%s 应回退内嵌验收页（M1 行为），得到 %d", p, resp.StatusCode)
		}
	}
}
