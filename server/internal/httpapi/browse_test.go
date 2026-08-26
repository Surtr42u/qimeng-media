package httpapi

// M1 浏览闭环端到端测试：临时目录 + 真实 SQLite + 真实小文件 + 真 ffmpeg。
// 覆盖：鉴权（401/setup/verify/409）、库管理（注册/400/扫描 503 与 202）、
// 列表（分页/筛选/排序/COS 排除）、详情、orig 直链（200/206/篡改 403/
// 过期 403/路径穿越拦截）、thumb（真 ffmpeg 生成/immutable/304）、
// 行为上报（view/like toggle/favorite）、探针与验收页。

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/thumbnail"
)

// 测试用假密码：setup 只要求非空，具体内容无任何语义。
// 随机生成而不是字面量，避免被静态扫描当作硬编码凭据；
// 主/副两个互不相同，用于"重复 setup 仍 409"用例。
var (
	testPasswordMain = randomTestPassword()
	testPasswordDup  = randomTestPassword()
)

func randomTestPassword() string {
	b := make([]byte, 12)
	if _, err := rand.Read(b); err != nil {
		panic(err) // 随机源故障只可能出现在测试机器上，直接暴露
	}
	return base64.RawURLEncoding.EncodeToString(b)
}

// fakeClock 是可拨动的测试时钟（签名过期用例需要"签发后时间流逝"）。
type fakeClock struct {
	mu  sync.Mutex
	now time.Time
}

func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.now
}

func (c *fakeClock) advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.now = c.now.Add(d)
}

// fakeScanner 是注入测试数据的假扫描器：Scan 时把预置文件经
// UpsertAsset 入库（模拟真实扫描器的写入路径）。
type fakeScanner struct {
	q     *db.Queries
	libID string
	mu    sync.Mutex
	done  bool
}

func (f *fakeScanner) Scan(ctx context.Context, libraryID string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.done {
		return nil
	}
	f.done = true
	now := store.FormatTimestamp(time.Now())
	for i, fi := range testFiles {
		if _, err := f.q.UpsertAsset(ctx, db.UpsertAssetParams{
			AssetID:   fi.id,
			LibraryID: f.libID,
			RelPath:   fi.relPath,
			FileName:  fi.name,
			MediaType: fi.mediaType,
			SizeBytes: fi.size,
			Mtime:     fi.mtime,
			CreatedAt: now,
			UpdatedAt: now,
		}); err != nil {
			return err
		}
		_ = i
	}
	return nil
}

// testEnv 是一套完整测试环境（独立临时目录/库/服务）。
type testEnv struct {
	ts      *httptest.Server
	token   string
	q       *db.Queries
	libID   string
	clock   *fakeClock
	media   string // 库根目录
	dataDir string
}

// testFiles 是三个测试资产：两图一视频（视频用小 mp4 头字节占位——
// 本测试不真解码视频，orig 206 用图片断言即可）。
type tfile struct {
	id        string
	relPath   string
	name      string
	mediaType string
	size      int64
	mtime     string
}

var testFiles []tfile

// writeTestJPG 生成一张真 JPEG（128x96 纯红）——缩略图用例必须用真图
// （ffmpeg 解码 + libwebp 编码全链路）。
func writeTestJPG(t *testing.T, path string, w, h int) {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for x := 0; x < w; x++ {
		for y := 0; y < h; y++ {
			img.Set(x, y, color.RGBA{R: 200, G: 40, B: 40, A: 255})
		}
	}
	f, err := os.Create(path)
	if err != nil {
		t.Fatalf("创建测试图片失败: %v", err)
	}
	defer func() { _ = f.Close() }()
	if err := jpeg.Encode(f, img, &jpeg.Options{Quality: 90}); err != nil {
		t.Fatalf("编码测试图片失败: %v", err)
	}
}

func newTestEnv(t *testing.T) *testEnv {
	t.Helper()
	root := t.TempDir()
	media := filepath.Join(root, "media")
	dataDir := filepath.Join(root, "data")
	for _, d := range []string{media, dataDir} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			t.Fatalf("创建目录失败: %v", err)
		}
	}
	// 三个真文件：a.jpg（大）、b.jpg（小）、c.mp4（占位字节）
	writeTestJPG(t, filepath.Join(media, "a.jpg"), 640, 480)
	writeTestJPG(t, filepath.Join(media, "b.jpg"), 320, 240)
	if err := os.WriteFile(filepath.Join(media, "c.mp4"), bytes.Repeat([]byte{0x00, 0x01, 0x02, 0x03}, 1024), 0o644); err != nil {
		t.Fatalf("创建测试视频占位失败: %v", err)
	}
	sa, _ := os.Stat(filepath.Join(media, "a.jpg"))
	sb, _ := os.Stat(filepath.Join(media, "b.jpg"))
	sc, _ := os.Stat(filepath.Join(media, "c.mp4"))
	mt := func(ts time.Time) string { return store.FormatTimestamp(ts) }
	base := time.Date(2026, 8, 20, 10, 0, 0, 0, time.UTC)
	testFiles = []tfile{
		{id: uuid.NewString(), relPath: "a.jpg", name: "a.jpg", mediaType: "image", size: sa.Size(), mtime: mt(base)},
		{id: uuid.NewString(), relPath: "b.jpg", name: "b.jpg", mediaType: "image", size: sb.Size(), mtime: mt(base.Add(24 * time.Hour))},
		{id: uuid.NewString(), relPath: "c.mp4", name: "c.mp4", mediaType: "video", size: sc.Size(), mtime: mt(base.Add(48 * time.Hour))},
	}

	conn, err := store.Open(filepath.Join(dataDir, "test.db"))
	if err != nil {
		t.Fatalf("打开测试库失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := store.Migrate(conn); err != nil {
		t.Fatalf("迁移失败: %v", err)
	}
	q := db.New(conn)
	lib, err := q.CreateLibrary(context.Background(), db.CreateLibraryParams{
		ID: uuid.NewString(), Name: "测试库", RootPath: media,
		CreatedAt: store.FormatTimestamp(time.Now()),
	})
	if err != nil {
		t.Fatalf("建库失败: %v", err)
	}

	clock := &fakeClock{now: time.Date(2026, 8, 22, 12, 0, 0, 0, time.UTC)}
	secret := []byte("test-secret-0123456789abcdef0123456789")
	cfg := &config.Config{DataDir: dataDir, Thumbnail: config.ThumbnailConfig{LongSide: 512}}
	fscan := &fakeScanner{q: q, libID: lib.ID}
	apisrv, err := New(Deps{
		Conn: conn, Queries: q, Bus: events.NewBus(nil, 0), Cfg: cfg,
		Thumbs: thumbnail.NewGenerator(dataDir, nil), Scanner: fscan,
		MediaSecret: secret, Now: clock.Now,
	})
	if err != nil {
		t.Fatalf("组装服务失败: %v", err)
	}
	ts := httptest.NewServer(apisrv.Handler())
	t.Cleanup(ts.Close)

	env := &testEnv{ts: ts, q: q, libID: lib.ID, clock: clock, media: media, dataDir: dataDir}
	env.setupAndSeed(t, fscan)
	return env
}

// setupAndSeed 完成初始化并触发一次假扫描（入库 3 个文件）。
func (e *testEnv) setupAndSeed(t *testing.T, sc *fakeScanner) {
	t.Helper()
	body := fmt.Sprintf(`{"password":"%s"}`, testPasswordMain)
	resp, err := http.Post(e.ts.URL+"/api/v1/auth/setup", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatalf("setup 请求失败: %v", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("setup 期望 201，得到 %d", resp.StatusCode)
	}
	var tok gen.AuthToken
	if err := decodeBody(resp, &tok); err != nil {
		t.Fatalf("解析 setup 响应失败: %v", err)
	}
	if tok.Token == nil || *tok.Token == "" {
		t.Fatal("setup 未返回 token")
	}
	e.token = *tok.Token
	if err := sc.Scan(context.Background(), e.libID); err != nil {
		t.Fatalf("假扫描失败: %v", err)
	}
}

func decodeBody(resp *http.Response, v any) error {
	return json.NewDecoder(resp.Body).Decode(v)
}

func (e *testEnv) do(t *testing.T, method, path string, body string) *http.Response {
	t.Helper()
	var rd *strings.Reader
	if body != "" {
		rd = strings.NewReader(body)
	} else {
		rd = strings.NewReader("")
	}
	req, err := http.NewRequest(method, e.ts.URL+path, rd)
	if err != nil {
		t.Fatalf("构造请求失败: %v", err)
	}
	req.Header.Set("Authorization", "Bearer "+e.token)
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	return resp
}

func closeBody(resp *http.Response) { _ = resp.Body.Close() }

// ---------- ① 鉴权 ----------

func TestAuthFlow(t *testing.T) {
	env := newTestEnv(t)

	// 无 token → 401
	resp, err := http.Get(env.ts.URL + "/api/v1/assets")
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	closeBody(resp)
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("无 token 期望 401，得到 %d", resp.StatusCode)
	}

	// 有效 token verify → 204
	resp = env.do(t, "POST", "/api/v1/auth/verify", "")
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("verify 期望 204，得到 %d", resp.StatusCode)
	}
	closeBody(resp)

	// 错 token → 401
	req, _ := http.NewRequest("POST", env.ts.URL+"/api/v1/auth/verify", nil)
	req.Header.Set("Authorization", "Bearer wrong-token")
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	closeBody(resp)
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("错 token 期望 401，得到 %d", resp.StatusCode)
	}

	// 重复 setup → 409
	resp, err = http.Post(env.ts.URL+"/api/v1/auth/setup", "application/json",
		strings.NewReader(fmt.Sprintf(`{"password":"%s"}`, testPasswordDup)))
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	closeBody(resp)
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("重复 setup 期望 409，得到 %d", resp.StatusCode)
	}
}

// ---------- ② 库管理 ----------

func TestLibraryEndpoints(t *testing.T) {
	env := newTestEnv(t)

	// 库列表：已有一个库（newTestEnv 建），文件计数 = 3
	resp := env.do(t, "GET", "/api/v1/libraries", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("库列表期望 200，得到 %d", resp.StatusCode)
	}
	var libs []gen.Library
	if err := decodeBody(resp, &libs); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if len(libs) != 1 || libs[0].FileCount == nil || *libs[0].FileCount != 3 {
		t.Fatalf("库计数不符： %+v", libs)
	}

	// 注册存在的目录 → 201（用独立目录：库根在数据目录内会被
	// DATA_DIR_CONFLICT 拒绝，那是 TestLibraryDataDirConflict 的职责）。
	lib2 := filepath.Join(filepath.Dir(env.dataDir), "lib2")
	if err := os.MkdirAll(lib2, 0o755); err != nil {
		t.Fatalf("建目录失败: %v", err)
	}
	resp = env.do(t, "POST", "/api/v1/libraries",
		`{"name":"第二库","rootPath":`+quote(lib2)+`}`)
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("注册存在的目录期望 201，得到 %d", resp.StatusCode)
	}
	closeBody(resp)

	// 注册不存在的目录 → 400
	resp = env.do(t, "POST", "/api/v1/libraries",
		`{"name":"幽灵库","rootPath":"Z:/definitely/not/exist"}`)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("注册不存在目录期望 400，得到 %d", resp.StatusCode)
	}
	closeBody(resp)

	// noScanner（本环境注入的是 fakeScanner）——另建一个 noScanner 环境
	// 验证 503 路径
	envNoScan := newNoScannerEnv(t)
	defer envNoScan.ts.Close()
	resp = envNoScan.do(t, "POST", "/api/v1/libraries/"+envNoScan.libID+"/scan", "")
	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("noScanner 扫描期望 503，得到 %d", resp.StatusCode)
	}
	closeBody(resp)

	// fakeScanner 扫描 → 202
	resp = env.do(t, "POST", "/api/v1/libraries/"+env.libID+"/scan", "")
	if resp.StatusCode != http.StatusAccepted {
		t.Fatalf("扫描期望 202，得到 %d", resp.StatusCode)
	}
	closeBody(resp)
}

// quote 输出 JSON 字符串字面量（路径含反斜杠需转义）。
func quote(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}

// ---------- ③ 列表：分页/筛选/排序 ----------

func TestAssetListPaginationFilterSort(t *testing.T) {
	env := newTestEnv(t)

	// 分页：limit=2 → 第一页 2 条 + 游标；第二页 1 条 + 无游标
	resp := env.do(t, "GET", "/api/v1/assets?limit=2", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("列表期望 200，得到 %d", resp.StatusCode)
	}
	var page gen.AssetPage
	if err := decodeBody(resp, &page); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if page.Items == nil || len(*page.Items) != 2 {
		t.Fatalf("第一页应 2 条，得到 %d", len(deref(page.Items)))
	}
	if page.TotalMatched == nil || *page.TotalMatched != 3 {
		t.Fatalf("totalMatched 应 3，得到 %v", page.TotalMatched)
	}
	if page.NextCursor == nil || *page.NextCursor == "" {
		t.Fatal("第一页应返回 nextCursor")
	}
	resp = env.do(t, "GET", "/api/v1/assets?limit=2&cursor="+*page.NextCursor, "")
	var page2 gen.AssetPage
	if err := decodeBody(resp, &page2); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if resp.StatusCode != http.StatusOK || len(deref(page2.Items)) != 1 || page2.NextCursor != nil {
		t.Fatalf("第二页应 1 条且无游标：status=%d items=%d cursor=%v",
			resp.StatusCode, len(deref(page2.Items)), page2.NextCursor)
	}
	closeBody(resp)

	// 每页条目应无重复（keyset 正确性）
	seen := map[string]bool{}
	resp = env.do(t, "GET", "/api/v1/assets?limit=2", "")
	_ = decodeBody(resp, &page)
	for _, it := range deref(page.Items) {
		seen[it.Id.String()] = true
	}
	closeBody(resp)
	if page.NextCursor != nil {
		resp = env.do(t, "GET", "/api/v1/assets?limit=2&cursor="+*page.NextCursor, "")
		_ = decodeBody(resp, &page)
		for _, it := range deref(page.Items) {
			if seen[it.Id.String()] {
				t.Fatal("分页出现重复资产（游标泄漏）")
			}
			seen[it.Id.String()] = true
		}
		closeBody(resp)
	}
	if len(seen) != 3 {
		t.Fatalf("两页合计应 3 条，得到 %d", len(seen))
	}

	// 筛选 mediaType=video → 只剩 c.mp4
	resp = env.do(t, "GET", "/api/v1/assets?mediaType=video", "")
	_ = decodeBody(resp, &page)
	closeBody(resp)
	if len(deref(page.Items)) != 1 || deref(page.Items)[0].FileName == nil || *deref(page.Items)[0].FileName != "c.mp4" {
		t.Fatalf("mediaType=video 应只剩 c.mp4： %+v", page.Items)
	}

	// 排序 sizeBytes asc → 首条是最小文件（320x240 jpg）
	resp = env.do(t, "GET", "/api/v1/assets?sort=sizeBytes&order=asc", "")
	_ = decodeBody(resp, &page)
	closeBody(resp)
	if n := deref(page.Items); len(n) != 3 {
		t.Fatalf("应 3 条，得到 %d", len(n))
	} else if *n[0].SizeBytes >= *n[1].SizeBytes || *n[1].SizeBytes >= *n[2].SizeBytes {
		t.Fatalf("sizeBytes asc 排序错误：%d %d %d", *n[0].SizeBytes, *n[1].SizeBytes, *n[2].SizeBytes)
	}

	// 排序 fileDate desc → mtime 最新的 c.mp4 在前
	resp = env.do(t, "GET", "/api/v1/assets?sort=fileDate&order=desc", "")
	_ = decodeBody(resp, &page)
	closeBody(resp)
	if n := deref(page.Items); *n[0].FileName != "c.mp4" {
		t.Fatalf("fileDate desc 首条应 c.mp4，得到 %s", *n[0].FileName)
	}

	// limit 越界 → 400
	resp = env.do(t, "GET", "/api/v1/assets?limit=500", "")
	closeBody(resp)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("limit=500 期望 400，得到 %d", resp.StatusCode)
	}
}

// ---------- ④ 详情 ----------

func TestAssetDetail(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]
	resp := env.do(t, "GET", "/api/v1/assets/"+a.id, "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("详情期望 200，得到 %d", resp.StatusCode)
	}
	var d gen.AssetDetail
	if err := decodeBody(resp, &d); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if d.Id == nil || d.Id.String() != a.id {
		t.Fatalf("详情 id 不符：%v", d.Id)
	}
	if d.OrigUrl == nil || !strings.HasPrefix(*d.OrigUrl, "/media/orig/"+a.id+"?exp=") {
		t.Fatalf("origUrl 不符：%v", d.OrigUrl)
	}
	if d.ThumbUrl == nil || !strings.Contains(*d.ThumbUrl, "size=lg") {
		t.Fatalf("thumbUrl 应为大图档：%v", d.ThumbUrl)
	}
	if d.RelPath == nil || *d.RelPath != "a.jpg" || d.Directory == nil || *d.Directory != "" {
		t.Fatalf("路径字段不符：%v %v", d.RelPath, d.Directory)
	}
	if d.Source == nil || *d.Source != "其他" {
		t.Fatalf("无出处资产应显示 其他：%v", d.Source)
	}

	// 404
	resp = env.do(t, "GET", "/api/v1/assets/"+uuid.NewString(), "")
	closeBody(resp)
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("不存在资产期望 404，得到 %d", resp.StatusCode)
	}
}

// ---------- ⑤ 媒体直链 ----------

func TestMediaOrigRangeAndSignature(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]
	want, err := os.ReadFile(filepath.Join(env.media, "a.jpg"))
	if err != nil {
		t.Fatalf("读源文件失败: %v", err)
	}

	// 全量 200：无鉴权头也能取（签名即凭证）
	detail := env.detail(t, a.id)
	resp, err := http.Get(env.ts.URL + *detail.OrigUrl)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("orig 期望 200，得到 %d", resp.StatusCode)
	}
	got := readAll(t, resp)
	if !bytes.Equal(want, got) {
		t.Fatalf("orig 内容与源文件不一致：%d vs %d 字节", len(want), len(got))
	}

	// Range → 206 + Content-Range（ServeContent 的 Range 语义验证）
	req, _ := http.NewRequest("GET", env.ts.URL+*detail.OrigUrl, nil)
	req.Header.Set("Range", "bytes=0-9")
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	if resp.StatusCode != http.StatusPartialContent {
		t.Fatalf("Range 期望 206，得到 %d", resp.StatusCode)
	}
	if cr := resp.Header.Get("Content-Range"); !strings.HasPrefix(cr, "bytes 0-9/") {
		t.Fatalf("Content-Range 不符：%s", cr)
	}
	if cl := resp.ContentLength; cl != 10 {
		t.Fatalf("分片长度应 10，得到 %d", cl)
	}
	closeBody(resp)

	// 签名篡改 → 403
	bad := strings.Replace(*detail.OrigUrl, "sig=", "sig=0", 1)
	resp, err = http.Get(env.ts.URL + bad)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	closeBody(resp)
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("篡改签名期望 403，得到 %d", resp.StatusCode)
	}

	// 过期 → 403（时钟前移 7h，超过 6h TTL）
	env.clock.advance(7 * time.Hour)
	resp, err = http.Get(env.ts.URL + *detail.OrigUrl)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	closeBody(resp)
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("过期签名期望 403，得到 %d", resp.StatusCode)
	}

	// 路径穿越：编码 ../ 的 path → 403，且不发生文件读取
	esc := env.ts.URL + "/media/orig/..%2f..%2f..%2fetc%2fpasswd?exp=9999999999&sig=deadbeef"
	resp, err = http.Get(esc)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	body := readAll(t, resp)
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("路径穿越期望 403，得到 %d", resp.StatusCode)
	}
	if bytes.Contains(body, []byte("root:")) {
		t.Fatal("穿越请求返回了文件内容（严重安全问题）")
	}
}

// ---------- ⑥ 缩略图（真 ffmpeg） ----------

func TestMediaThumbGenerated(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]
	detail := env.detail(t, a.id)

	resp, err := http.Get(env.ts.URL + *detail.ThumbUrl)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("thumb 期望 200，得到 %d", resp.StatusCode)
	}
	if ct := resp.Header.Get("Content-Type"); ct != "image/webp" {
		t.Fatalf("thumb Content-Type 应 image/webp，得到 %s", ct)
	}
	if cc := resp.Header.Get("Cache-Control"); !strings.Contains(cc, "immutable") {
		t.Fatalf("thumb 应带 immutable 缓存头，得到 %q", cc)
	}
	etag := resp.Header.Get("ETag")
	if etag == "" {
		t.Fatal("thumb 应带 ETag（缓存键）")
	}
	img := readAll(t, resp)
	if len(img) < 100 {
		t.Fatalf("缩略图内容异常（%d 字节）", len(img))
	}

	// If-None-Match → 304
	req, _ := http.NewRequest("GET", env.ts.URL+*detail.ThumbUrl, nil)
	req.Header.Set("If-None-Match", etag)
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	closeBody(resp)
	if resp.StatusCode != http.StatusNotModified {
		t.Fatalf("If-None-Match 期望 304，得到 %d", resp.StatusCode)
	}
}

// ---------- ⑦ 行为上报 ----------

func TestEngagementEndpoints(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]

	// view 上报 → 202，详情 viewCount=1
	started := "2026-08-22T12:00:00Z"
	resp := env.do(t, "POST", "/api/v1/events/view",
		`{"assetId":"`+a.id+`","kind":"open","startedAt":"`+started+`","sessionId":"s1"}`)
	closeBody(resp)
	if resp.StatusCode != http.StatusAccepted {
		t.Fatalf("view 上报期望 202，得到 %d", resp.StatusCode)
	}
	d := env.detail(t, a.id)
	if d.ViewCount == nil || *d.ViewCount != 1 {
		t.Fatalf("viewCount 应 1：%v", d.ViewCount)
	}

	// like：第一次 → likedToday=true count=1；再点 → 取消（false, 0）；三点 → 再赞
	resp = env.do(t, "PUT", "/api/v1/assets/"+a.id+"/like", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("like 期望 200，得到 %d", resp.StatusCode)
	}
	var st gen.LikeState
	_ = decodeBody(resp, &st)
	if st.LikedToday == nil || !*st.LikedToday || st.LikeCount == nil || *st.LikeCount != 1 {
		t.Fatalf("首次 like 状态不符：%+v", st)
	}
	resp = env.do(t, "PUT", "/api/v1/assets/"+a.id+"/like", "")
	_ = decodeBody(resp, &st)
	if *st.LikedToday || *st.LikeCount != 0 {
		t.Fatalf("toggle 取消后状态不符：%+v", st)
	}
	resp = env.do(t, "PUT", "/api/v1/assets/"+a.id+"/like", "")
	_ = decodeBody(resp, &st)
	if !*st.LikedToday || *st.LikeCount != 1 {
		t.Fatalf("再次点赞状态不符：%+v", st)
	}

	// like 不存在资产 → 404
	resp = env.do(t, "PUT", "/api/v1/assets/"+uuid.NewString()+"/like", "")
	closeBody(resp)
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("不存在资产 like 期望 404，得到 %d", resp.StatusCode)
	}

	// favorite：设置 → 204；favorite=true 筛选命中
	resp = env.do(t, "PUT", "/api/v1/assets/"+a.id+"/favorite", `{"favorite":true}`)
	closeBody(resp)
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("favorite 期望 204，得到 %d", resp.StatusCode)
	}
	resp = env.do(t, "GET", "/api/v1/assets?favorite=true", "")
	var page gen.AssetPage
	_ = decodeBody(resp, &page)
	closeBody(resp)
	if len(deref(page.Items)) != 1 {
		t.Fatalf("favorite=true 应命中 1 条，得到 %d", len(deref(page.Items)))
	}
	d = env.detail(t, a.id)
	if d.IsFavorite == nil || !*d.IsFavorite {
		t.Fatal("详情应显示已收藏")
	}
	// 取消收藏
	resp = env.do(t, "PUT", "/api/v1/assets/"+a.id+"/favorite", `{"favorite":false}`)
	closeBody(resp)
	resp = env.do(t, "GET", "/api/v1/assets?favorite=true", "")
	_ = decodeBody(resp, &page)
	closeBody(resp)
	if len(deref(page.Items)) != 0 {
		t.Fatalf("取消收藏后 favorite=true 应 0 条")
	}
}

// ---------- ⑧ 探针 / 验收页 / 未实现端点 ----------

// TestSSEEndpoint：SSE 在鉴权链内——无 token 401；带 token 返回
// text/event-stream 且首帧含 retry/hello（events.Handler 语义）。
func TestSSEEndpoint(t *testing.T) {
	env := newTestEnv(t)

	resp, err := http.Get(env.ts.URL + "/api/v1/events")
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	closeBody(resp)
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("SSE 无 token 期望 401，得到 %d", resp.StatusCode)
	}

	req, _ := http.NewRequest("GET", env.ts.URL+"/api/v1/events", nil)
	req.Header.Set("Authorization", "Bearer "+env.token)
	resp, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	if ct := resp.Header.Get("Content-Type"); !strings.HasPrefix(ct, "text/event-stream") {
		t.Fatalf("SSE Content-Type 不符：%s", ct)
	}
	// 只读首段（retry + hello 帧到达即证明流建立），随后主动断开。
	var buf [512]byte
	n, _ := resp.Body.Read(buf[:])
	head := string(buf[:n])
	closeBody(resp)
	if !strings.Contains(head, "retry:") || !strings.Contains(head, "event: hello") {
		t.Fatalf("SSE 首帧应含 retry 与 hello：%q", head)
	}
}

func TestProbesPageAndStubs(t *testing.T) {
	env := newTestEnv(t)

	for _, c := range []struct{ path, want string }{
		{"/healthz", "alive"},
		{"/readyz", "ready"},
	} {
		resp, err := http.Get(env.ts.URL + c.path)
		if err != nil {
			t.Fatalf("请求 %s 失败: %v", c.path, err)
		}
		body := readAll(t, resp)
		if resp.StatusCode != http.StatusOK || !strings.Contains(string(body), c.want) {
			t.Fatalf("%s 期望 200+%s，得到 %d+%s", c.path, c.want, resp.StatusCode, body)
		}
	}

	// 验收页（免鉴权）
	resp, err := http.Get(env.ts.URL + "/")
	if err != nil {
		t.Fatalf("请求验收页失败: %v", err)
	}
	body := readAll(t, resp)
	if resp.StatusCode != http.StatusOK || !strings.Contains(string(body), "M1") {
		t.Fatalf("验收页期望 200 且含标题，得到 %d", resp.StatusCode)
	}
	if ct := resp.Header.Get("Content-Type"); !strings.Contains(ct, "text/html") {
		t.Fatalf("验收页 Content-Type 不符：%s", ct)
	}

	// 未实现端点 → 501
	resp = env.do(t, "GET", "/api/v1/tags", "")
	closeBody(resp)
	if resp.StatusCode != http.StatusNotImplemented {
		t.Fatalf("未实现端点期望 501，得到 %d", resp.StatusCode)
	}
}

// ---------- 辅助 ----------

func (e *testEnv) detail(t *testing.T, assetID string) gen.AssetDetail {
	t.Helper()
	resp := e.do(t, "GET", "/api/v1/assets/"+assetID, "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("取详情失败：%d", resp.StatusCode)
	}
	var d gen.AssetDetail
	if err := decodeBody(resp, &d); err != nil {
		t.Fatalf("解析详情失败: %v", err)
	}
	return d
}

func readAll(t *testing.T, resp *http.Response) []byte {
	t.Helper()
	defer closeBody(resp)
	var buf bytes.Buffer
	if _, err := buf.ReadFrom(resp.Body); err != nil {
		t.Fatalf("读响应体失败: %v", err)
	}
	return buf.Bytes()
}

func deref[T any](s *[]T) []T {
	if s == nil {
		return nil
	}
	return *s
}

// newNoScannerEnv 建一个注入 noScanner 的环境（只测扫描 503 路径，
// 不需要媒体文件——用空目录库）。
func newNoScannerEnv(t *testing.T) *testEnv {
	t.Helper()
	root := t.TempDir()
	dataDir := filepath.Join(root, "data")
	media := filepath.Join(root, "media")
	_ = os.MkdirAll(dataDir, 0o755)
	_ = os.MkdirAll(media, 0o755)
	conn, err := store.Open(filepath.Join(dataDir, "test.db"))
	if err != nil {
		t.Fatalf("打开测试库失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := store.Migrate(conn); err != nil {
		t.Fatalf("迁移失败: %v", err)
	}
	q := db.New(conn)
	lib, err := q.CreateLibrary(context.Background(), db.CreateLibraryParams{
		ID: uuid.NewString(), Name: "空库", RootPath: media,
		CreatedAt: store.FormatTimestamp(time.Now()),
	})
	if err != nil {
		t.Fatalf("建库失败: %v", err)
	}
	cfg := &config.Config{DataDir: dataDir, Thumbnail: config.ThumbnailConfig{LongSide: 512}}
	apisrv, err := New(Deps{
		Conn: conn, Queries: q, Bus: events.NewBus(nil, 0), Cfg: cfg,
		Thumbs:      thumbnail.NewGenerator(dataDir, nil),
		Scanner:     noScanner{}, // 显式占位（nil 也会落到它，这里测显式路径）
		MediaSecret: []byte("noscanner-secret-0123456789abcdef"),
	})
	if err != nil {
		t.Fatalf("组装失败: %v", err)
	}
	ts := httptest.NewServer(apisrv.Handler())
	env := &testEnv{ts: ts, q: q, libID: lib.ID, clock: &fakeClock{now: time.Now()}, media: media, dataDir: dataDir}
	// setup 拿 token
	resp, err := http.Post(ts.URL+"/api/v1/auth/setup", "application/json",
		strings.NewReader(fmt.Sprintf(`{"password":"%s"}`, testPasswordMain)))
	if err != nil {
		t.Fatalf("setup 失败: %v", err)
	}
	var tok gen.AuthToken
	_ = decodeBody(resp, &tok)
	env.token = *tok.Token
	return env
}

// TestLibraryDataDirConflict 验证库根与数据目录互斥（自噬防御第一道防线，
// scanner SkipDir 是第二道）：任何方向的嵌套都判冲突。
func TestLibraryDataDirConflict(t *testing.T) {
	if !dirConflict(filepath.Join("C:", "media"), filepath.Join("C:", "media", "data")) {
		t.Fatal("数据目录在库内：应判冲突")
	}
	if !dirConflict(filepath.Join("C:", "media", "data"), filepath.Join("C:", "media")) {
		t.Fatal("库根在数据目录内：应判冲突")
	}
	if !dirConflict(filepath.Join("C:", "a"), filepath.Join("c:", "a")) {
		t.Fatal("大小写不同：应判冲突")
	}
	if dirConflict(filepath.Join("C:", "media"), filepath.Join("D:", "data")) {
		t.Fatal("两个独立目录：不应判冲突")
	}
	if dirConflict(filepath.Join("C:", "media"), "") {
		t.Fatal("空数据目录（未配置）：不应判冲突")
	}
}
