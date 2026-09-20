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
	"database/sql"
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
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/backup"
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
	s       *Server // 暴露给测试直调内部方法（如上传落盘子程的原子性用例）
	token   string
	q       *db.Queries
	conn    *sql.DB
	libID   string
	clock   *fakeClock
	media   string // 库根目录
	dataDir string
	cfg     *config.Config // 暴露给测试按用例调整（如上传大小上限）
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

// newTestEnvRaw 组装基础测试环境（迁移/建库/服务就绪，但不 setup 初始化、
// 不扫描——初始化时序交给用例自己控制）。
func newTestEnvRaw(t *testing.T) *testEnv {
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
		Kind:      "normal",
		CreatedAt: store.FormatTimestamp(time.Now()),
	})
	if err != nil {
		t.Fatalf("建库失败: %v", err)
	}

	clock := &fakeClock{now: time.Date(2026, 8, 22, 12, 0, 0, 0, time.UTC)}
	secret := []byte("test-secret-0123456789abcdef0123456789")
	// Backup 三键对齐生产默认值（任务Q 批B）：备份端点用例消费 schedule
	// 回显，其余用例零感知。
	cfg := &config.Config{
		DataDir:   dataDir,
		Thumbnail: config.ThumbnailConfig{LongSide: 512},
		Backup: config.BackupConfig{
			Enabled:   config.DefaultBackupEnabled,
			Interval:  config.DefaultBackupInterval,
			Retention: config.DefaultBackupRetention,
		},
	}
	fscan := &fakeScanner{q: q, libID: lib.ID}
	// 备份管理器与生产 main 同款装配：快照执行器 = store.VacuumInto（真实
	// 临时库跑通 VACUUM INTO 全链），时钟复用 fakeClock。
	backupMgr, err := backup.NewManager(backup.Options{
		Dir:       filepath.Join(dataDir, backup.DirName),
		Snapshot:  func(ctx context.Context, dest string) error { return store.VacuumInto(conn, dest) },
		Retention: cfg.Backup.Retention,
		Now:       clock.Now,
	})
	if err != nil {
		t.Fatalf("组装备份管理器失败: %v", err)
	}
	apisrv, err := New(Deps{
		Conn: conn, Queries: q, Bus: events.NewBus(nil, 0), Cfg: cfg,
		Thumbs: thumbnail.NewGenerator(dataDir, nil, thumbnail.Options{}), Scanner: fscan,
		Backup:      backupMgr,
		MediaSecret: secret, Now: clock.Now,
	})
	if err != nil {
		t.Fatalf("组装服务失败: %v", err)
	}
	ts := httptest.NewServer(apisrv.Handler())
	t.Cleanup(ts.Close)

	env := &testEnv{s: apisrv, ts: ts, q: q, conn: conn, libID: lib.ID, clock: clock, media: media, dataDir: dataDir, cfg: cfg}
	return env
}

// newTestEnv 标准测试环境：组装 + setup 初始化 + 一次假扫描（3 个文件入库）。
// 需要自定义初始化时序（如 dev 模式免密登录）的用例用 newTestEnvRaw。
func newTestEnv(t *testing.T) *testEnv {
	e := newTestEnvRaw(t)
	e.setupAndSeed(t, &fakeScanner{q: e.q, libID: e.libID})
	return e
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

// TestDeleteLibrary 删库端点：204 + 列表移除 + 级联清资产 + 404 幂等。
// view_events 保留语义由实现保证：DeleteLibrary 仅 DELETE libraries 一行，
// 事件表无外键（0001 migration 注释），单表语句天然触碰不到它。
func TestDeleteLibrary(t *testing.T) {
	env := newTestEnv(t)

	// 删除存在的库 → 204
	resp := env.do(t, "DELETE", "/api/v1/libraries/"+env.libID, "")
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("删库期望 204，得到 %d", resp.StatusCode)
	}
	closeBody(resp)

	// 库列表移除
	resp = env.do(t, "GET", "/api/v1/libraries", "")
	var libs []gen.Library
	if err := decodeBody(resp, &libs); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if len(libs) != 0 {
		t.Fatalf("删库后期望 0 库，得到 %d", len(libs))
	}

	// 级联：该库资产索引清空（browse 空数组）
	resp = env.do(t, "GET", "/api/v1/assets", "")
	var assets gen.AssetPage
	if err := decodeBody(resp, &assets); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if assets.Items != nil && len(*assets.Items) != 0 {
		t.Fatalf("删库后期望 0 资产，得到 %d", len(*assets.Items))
	}

	// 删除已删的库 → 404
	resp = env.do(t, "DELETE", "/api/v1/libraries/"+env.libID, "")
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("重复删库期望 404，得到 %d", resp.StatusCode)
	}
	closeBody(resp)
}

// TestLibraryEnabledFilter 库开关：停用仅隐藏浏览面（列表/计数），记录与详情保留。
func TestLibraryEnabledFilter(t *testing.T) {
	env := newTestEnv(t)

	// 取停用前的首个资产 id（详情不过滤断言用：签名直链/已获取 assetId 保持稳定）
	resp0 := env.do(t, "GET", "/api/v1/assets", "")
	var pageBefore gen.AssetPage
	if err := decodeBody(resp0, &pageBefore); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if pageBefore.Items == nil || len(*pageBefore.Items) == 0 {
		t.Fatal("停用前应先拿到资产列表")
	}
	firstAssetID := (*pageBefore.Items)[0].Id.String()

	// 停用唯一库 → 204
	resp := env.do(t, "PUT", "/api/v1/libraries/"+env.libID+"/enabled", `{"enabled":false}`)
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("停用期望 204，得到 %d", resp.StatusCode)
	}
	closeBody(resp)

	// 浏览列表隐藏（空页）
	resp = env.do(t, "GET", "/api/v1/assets", "")
	var page gen.AssetPage
	if err := decodeBody(resp, &page); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if page.Items != nil && len(*page.Items) != 0 {
		t.Fatalf("停用后期望 0 资产，得到 %d", len(*page.Items))
	}
	// 详情不过滤：停用后已获取的 assetId 仍可访问（签名直链稳定）
	resp = env.do(t, "GET", "/api/v1/assets/"+firstAssetID, "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("停用后详情期望 200，得到 %d", resp.StatusCode)
	}
	closeBody(resp)

	// 管理面库列表不过滤：库仍在且带 enabled=false
	resp = env.do(t, "GET", "/api/v1/libraries", "")
	var libs []gen.Library
	if err := decodeBody(resp, &libs); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if len(libs) != 1 || libs[0].Enabled == nil || *libs[0].Enabled {
		t.Fatalf("停用后库应保留且 enabled=false： %+v", libs)
	}

	// 重新启用 → 浏览列表恢复
	resp = env.do(t, "PUT", "/api/v1/libraries/"+env.libID+"/enabled", `{"enabled":true}`)
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("启用期望 204，得到 %d", resp.StatusCode)
	}
	closeBody(resp)
	resp = env.do(t, "GET", "/api/v1/assets", "")
	if err := decodeBody(resp, &page); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	if page.Items == nil || len(*page.Items) == 0 {
		t.Fatal("启用后浏览列表应恢复")
	}

	// 停用不存在的库 → 404
	resp = env.do(t, "PUT", "/api/v1/libraries/00000000-0000-0000-0000-000000000000/enabled", `{"enabled":false}`)
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("停用未知库期望 404，得到 %d", resp.StatusCode)
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

// TestAssetListSortDefaultIsFileDate：「默认」排序档 = 文件时间（DOMAIN_RULES
// §3，2026-09-17 晚拍板）。夹具三资产 created_at 全同（整批一次性扫描形态）
// 而 mtime 各异——按入库时间排序会退化为 asset_id 字典序（不确定、被扫描
// 顺序支配），按文件时间则恒为 mtime 降序 c.mp4/b.jpg/a.jpg。断言覆盖
// 缺省不传、显式 default、显式 fileDate 三种入参形态（别名后应逐一等价）。
func TestAssetListSortDefaultIsFileDate(t *testing.T) {
	env := newTestEnv(t)
	want := "c.mp4 b.jpg a.jpg"
	for _, q := range []string{"", "?sort=default", "?sort=fileDate"} {
		resp := env.do(t, "GET", "/api/v1/assets"+q, "")
		var page gen.AssetPage
		if err := decodeBody(resp, &page); err != nil {
			t.Fatalf("解析失败: %v", err)
		}
		closeBody(resp)
		names := make([]string, 0, len(deref(page.Items)))
		for _, it := range deref(page.Items) {
			if it.FileName == nil {
				t.Fatal("条目缺 fileName")
			}
			names = append(names, *it.FileName)
		}
		if got := strings.Join(names, " "); got != want {
			t.Fatalf("query %q 默认排序应按 mtime 降序 %q，得到 %q", q, want, got)
		}
	}
}

// TestAssetListAuthorNamesAndDuration：列表端点的卡片增强字段。
// authorNames = 该资产全部作者显示名（常规∪COS，DOMAIN_RULES 6）；
// 无作者 = 空数组（协议约定，与字段省略区分，客户端据此回退出处）。
// durationMs 仅视频有值（时长角标数据，图片为 NULL 不序列化）。
// 搜索（q 参数）走同一查询自然获得，不另设用例。
func TestAssetListAuthorNamesAndDuration(t *testing.T) {
	env := newTestEnv(t)
	a, b, c := testFiles[0], testFiles[1], testFiles[2]
	ctx := context.Background()
	now := store.FormatTimestamp(env.clock.Now())

	regX := authoring.GenerateAuthorID("作者甲")
	regY := authoring.GenerateAuthorID("作者乙")
	cosID := authoring.GenerateCosAuthorID("COS酱")
	for _, au := range []db.UpsertAuthorParams{
		{ID: regX, DisplayName: "作者甲", Type: authoring.AuthorTypeRegular, CreatedAt: now},
		{ID: regY, DisplayName: "作者乙", Type: authoring.AuthorTypeRegular, CreatedAt: now},
		{ID: cosID, DisplayName: "COS酱", Type: authoring.AuthorTypeCos, CreatedAt: now},
	} {
		if err := env.q.UpsertAuthor(ctx, au); err != nil {
			t.Fatalf("UpsertAuthor %s 失败: %v", au.DisplayName, err)
		}
	}
	link := func(assetID, authorID string) {
		t.Helper()
		if err := env.q.AddAssetAuthor(ctx, db.AddAssetAuthorParams{AssetID: assetID, AuthorID: authorID}); err != nil {
			t.Fatalf("AddAssetAuthor 失败: %v", err)
		}
	}
	link(a.id, regX)
	link(a.id, regY)  // a.jpg：多作者（断言全部返回且按 display_name 升序）
	link(b.id, cosID) // b.jpg：仅 COS 作者（常规∪COS 口径）
	// c.mp4：无作者

	// c.mp4 补视频时长：同路径 Upsert 刷新元数据（duration_ms 在 DO
	// UPDATE 列里），asset_id 保持首见——与扫描器重探同路径。
	if _, err := env.q.UpsertAsset(ctx, db.UpsertAssetParams{
		AssetID: c.id, LibraryID: env.libID, RelPath: c.relPath,
		FileName: c.name, MediaType: c.mediaType, SizeBytes: c.size,
		Mtime:      c.mtime,
		DurationMs: sql.NullInt64{Int64: 65000, Valid: true},
		CreatedAt:  now, UpdatedAt: now,
	}); err != nil {
		t.Fatalf("写入视频时长失败: %v", err)
	}

	// includeCos=true：b.jpg 挂了 COS 作者，默认常规流会排除它（DOMAIN_RULES
	// §6）；本用例要断言 COS 作者名，取全量。
	resp := env.do(t, "GET", "/api/v1/assets?limit=10&includeCos=true", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("列表期望 200，得到 %d", resp.StatusCode)
	}
	var page gen.AssetPage
	if err := decodeBody(resp, &page); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	closeBody(resp)
	byName := map[string]gen.AssetSummary{}
	for _, it := range deref(page.Items) {
		if it.FileName != nil {
			byName[*it.FileName] = it
		}
	}
	// display_name 升序 = Unicode 码点序（SQL 默认二进制排序）：
	// 「乙」U+4E59 < 「甲」U+7532，故作者乙在前。
	if an := byName["a.jpg"].AuthorNames; an == nil || len(*an) != 2 ||
		(*an)[0] != "作者乙" || (*an)[1] != "作者甲" {
		t.Fatalf("a.jpg 应返回两位作者名（display_name 升序），得到 %v", byName["a.jpg"].AuthorNames)
	}
	if an := byName["b.jpg"].AuthorNames; an == nil || len(*an) != 1 || (*an)[0] != "COS酱" {
		t.Fatalf("b.jpg 应返回 COS 作者名，得到 %v", byName["b.jpg"].AuthorNames)
	}
	if an := byName["c.mp4"].AuthorNames; an == nil || len(*an) != 0 {
		t.Fatalf("c.mp4 无作者应为空数组，得到 %v", byName["c.mp4"].AuthorNames)
	}
	if d := byName["c.mp4"].DurationMs; d == nil || *d != 65000 {
		t.Fatalf("c.mp4 durationMs 应 65000，得到 %v", byName["c.mp4"].DurationMs)
	}
	if d := byName["a.jpg"].DurationMs; d != nil {
		t.Fatalf("图片 a.jpg 不应带 durationMs，得到 %v", *d)
	}
}

// TestAssetListLikedFilter：liked=true 只返回赞过（likes 表任意日行）的资产，
// liked=false 返回从未赞过的资产（与 favorite 筛选同机关）。
func TestAssetListLikedFilter(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]

	// 直接写 likes 行（PUT /like 是当日 toggle，测试要的多日历史行用查询层写入）
	if _, err := env.q.AddLikeOnDayIdempotent(context.Background(), db.AddLikeOnDayIdempotentParams{
		AssetID: a.id, Day: "2026-08-20", CreatedAt: store.FormatTimestamp(env.clock.Now()),
	}); err != nil {
		t.Fatalf("写赞失败: %v", err)
	}

	resp := env.do(t, "GET", "/api/v1/assets?liked=true", "")
	var page gen.AssetPage
	_ = decodeBody(resp, &page)
	closeBody(resp)
	if n := len(deref(page.Items)); n != 1 || *deref(page.Items)[0].FileName != "a.jpg" {
		t.Fatalf("liked=true 应只含 a.jpg，得到 %v", page.Items)
	}
	if page.TotalMatched == nil || *page.TotalMatched != 1 {
		t.Fatalf("liked=true totalMatched 应 1，得到 %v", page.TotalMatched)
	}

	resp = env.do(t, "GET", "/api/v1/assets?liked=false", "")
	_ = decodeBody(resp, &page)
	closeBody(resp)
	if n := len(deref(page.Items)); n != 2 {
		t.Fatalf("liked=false 应剩 2 条，得到 %d", n)
	}
	for _, it := range deref(page.Items) {
		if *it.FileName == "a.jpg" {
			t.Fatal("liked=false 不应含 a.jpg")
		}
	}
}

// TestAssetListSortFavoriteAt：sort=favoriteAt 按收藏时间排序（仅 favorite=true
// 语义成立）；desc 新收藏在前，asc 旧收藏在前，未收藏资产不进结果。
func TestAssetListSortFavoriteAt(t *testing.T) {
	env := newTestEnv(t)
	a, c := testFiles[0], testFiles[2]

	addFav := func(assetID, created string) {
		t.Helper()
		if _, err := env.q.AddFavorite(context.Background(), db.AddFavoriteParams{
			AssetID: assetID, CreatedAt: created,
		}); err != nil {
			t.Fatalf("写收藏失败: %v", err)
		}
	}
	addFav(a.id, "2026-08-19T10:00:00.000Z")
	addFav(c.id, "2026-08-21T10:00:00.000Z")

	// desc：新收藏（c.mp4）在前
	resp := env.do(t, "GET", "/api/v1/assets?sort=favoriteAt&favorite=true&order=desc", "")
	var page gen.AssetPage
	_ = decodeBody(resp, &page)
	closeBody(resp)
	if n := deref(page.Items); len(n) != 2 || *n[0].FileName != "c.mp4" || *n[1].FileName != "a.jpg" {
		t.Fatalf("favoriteAt desc 顺序应为 c.mp4,a.jpg：%v", page.Items)
	}

	// asc：旧收藏（a.jpg）在前
	resp = env.do(t, "GET", "/api/v1/assets?sort=favoriteAt&favorite=true&order=asc", "")
	_ = decodeBody(resp, &page)
	closeBody(resp)
	if n := deref(page.Items); len(n) != 2 || *n[0].FileName != "a.jpg" || *n[1].FileName != "c.mp4" {
		t.Fatalf("favoriteAt asc 顺序应为 a.jpg,c.mp4：%v", page.Items)
	}

	// favorite=true 未收藏的 b.jpg 不出现（favorite 筛选与 favoriteAt 组合语义）
	for _, it := range deref(page.Items) {
		if *it.FileName == "b.jpg" {
			t.Fatal("favorite=true 不应含未收藏的 b.jpg")
		}
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
	// thumbUrlMd（2026-09-18 协议新增）：详情同时下发 md 档直链，客户端海报
	// 先 md 秒出、lg 就绪后换上（md 为回填预生成档，首开不等现场生成）
	if d.ThumbUrlMd == nil || !strings.Contains(*d.ThumbUrlMd, "size=md") {
		t.Fatalf("thumbUrlMd 应为网格档：%v", d.ThumbUrlMd)
	}
	if d.RelPath == nil || *d.RelPath != "a.jpg" || d.Directory == nil || *d.Directory != "" {
		t.Fatalf("路径字段不符：%v %v", d.RelPath, d.Directory)
	}
	if d.LibraryId == nil || *d.LibraryId != env.libID {
		t.Fatalf("详情应携带归属库 ID：%v, want %s", d.LibraryId, env.libID)
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

// TestAssetLikedToday：AssetSummary.likedToday（点赞按钮初始态）在列表与
// 详情两出口的三态填充——今日已赞（true）/ 曾赞非今日（false，当日口径
// 不含历史行）/ 从未赞（false）。造数：a 走真点赞端点（toggle 首点=今日
// 赞），b 直接插昨日行（AddLike 与点赞端点同一张 likes 表），c 不造数。
// 末段验证取消今日赞后两出口同步回 false（字段与 toggle 状态联动）。
func TestAssetLikedToday(t *testing.T) {
	env := newTestEnv(t)
	a, b, c := testFiles[0], testFiles[1], testFiles[2]
	ctx := context.Background()

	// a.jpg：今日已赞（真端点造数）
	resp := env.do(t, "PUT", "/api/v1/assets/"+a.id+"/like", "")
	closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("a 点赞期望 200，得到 %d", resp.StatusCode)
	}
	// b.jpg：曾赞非今日（昨日行，直接走点赞端点同一写入路径 AddLikeOnDayIdempotent）
	yesterday := store.FormatDay(env.clock.Now().AddDate(0, 0, -1))
	if _, err := env.q.AddLikeOnDayIdempotent(ctx, db.AddLikeOnDayIdempotentParams{
		AssetID: b.id, Day: yesterday, CreatedAt: store.FormatTimestamp(env.clock.Now()),
	}); err != nil {
		t.Fatalf("插入昨日点赞行失败: %v", err)
	}

	// 列表出口：三态断言
	resp = env.do(t, "GET", "/api/v1/assets?limit=10", "")
	var page gen.AssetPage
	if err := decodeBody(resp, &page); err != nil {
		t.Fatalf("解析列表失败: %v", err)
	}
	closeBody(resp)
	byName := map[string]gen.AssetSummary{}
	for _, it := range deref(page.Items) {
		if it.FileName != nil {
			byName[*it.FileName] = it
		}
	}
	for name, want := range map[string]bool{"a.jpg": true, "b.jpg": false, "c.mp4": false} {
		it, ok := byName[name]
		if !ok {
			t.Fatalf("列表缺少 %s", name)
		}
		if it.LikedToday == nil {
			t.Fatalf("%s 的 likedToday 未填充（应为 %v）", name, want)
		}
		if *it.LikedToday != want {
			t.Fatalf("%s 的 likedToday 应 %v，得到 %v", name, want, *it.LikedToday)
		}
	}
	// 曾赞非今日：likedToday=false 但累计 likeCount 保留（两字段不混淆）
	if lc := byName["b.jpg"].LikeCount; lc == nil || *lc != 1 {
		t.Fatalf("b.jpg 累计 likeCount 应保留 1，得到 %v", byName["b.jpg"].LikeCount)
	}

	// 详情出口：三态断言
	for id, want := range map[string]bool{a.id: true, b.id: false, c.id: false} {
		d := env.detail(t, id)
		if d.LikedToday == nil {
			t.Fatalf("详情 %s 的 likedToday 未填充（应为 %v）", id, want)
		}
		if *d.LikedToday != want {
			t.Fatalf("详情 %s 的 likedToday 应 %v，得到 %v", id, want, *d.LikedToday)
		}
	}

	// 取消 a 的今日赞（toggle 另一半）→ 两出口同步回 false
	resp = env.do(t, "PUT", "/api/v1/assets/"+a.id+"/like", "")
	closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("a 取消点赞期望 200，得到 %d", resp.StatusCode)
	}
	resp = env.do(t, "GET", "/api/v1/assets?limit=10", "")
	_ = decodeBody(resp, &page)
	closeBody(resp)
	for _, it := range deref(page.Items) {
		if it.Id != nil && it.Id.String() == a.id && it.LikedToday != nil && *it.LikedToday {
			t.Fatal("取消点赞后列表 likedToday 应回 false")
		}
	}
	if d := env.detail(t, a.id); d.LikedToday != nil && *d.LikedToday {
		t.Fatal("取消点赞后详情 likedToday 应回 false")
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

	// 未实现端点 → 501 的样本断言随作者体系三端点接线（M3）移除：
	// openapi 已定义的全部端点至此均已实现，stubs.go 不再持有占位方法；
	// 若未来新增未接线端点，恢复样本断言（notImplemented 语义见 errors.go）。
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
		Kind:      "normal",
		CreatedAt: store.FormatTimestamp(time.Now()),
	})
	if err != nil {
		t.Fatalf("建库失败: %v", err)
	}
	cfg := &config.Config{DataDir: dataDir, Thumbnail: config.ThumbnailConfig{LongSide: 512}}
	apisrv, err := New(Deps{
		Conn: conn, Queries: q, Bus: events.NewBus(nil, 0), Cfg: cfg,
		Thumbs:      thumbnail.NewGenerator(dataDir, nil, thumbnail.Options{}),
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

// TestAssetListCosWork：列表端点 cosWork 装配（COS 卡片标题数据源）——
// 有作品子目录的 COS 资产返回作品名（assets.cos_work），常规资产字段
// 缺省（协议 null 语义 = 客户端回退 fileName）。默认常规流排除 COS，
// 取 includeCos 全量断言。
func TestAssetListCosWork(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]
	ctx := context.Background()
	now := store.FormatTimestamp(env.clock.Now())

	cosID := authoring.GenerateCosAuthorID("COS酱")
	if err := env.q.UpsertAuthor(ctx, db.UpsertAuthorParams{
		ID: cosID, DisplayName: "COS酱", Type: authoring.AuthorTypeCos, CreatedAt: now,
	}); err != nil {
		t.Fatalf("UpsertAuthor 失败: %v", err)
	}
	if err := env.q.AddAssetAuthor(ctx, db.AddAssetAuthorParams{AssetID: a.id, AuthorID: cosID}); err != nil {
		t.Fatalf("AddAssetAuthor 失败: %v", err)
	}
	if _, err := env.q.UpsertAsset(ctx, db.UpsertAssetParams{
		AssetID: a.id, LibraryID: env.libID, RelPath: a.relPath,
		FileName: a.name, MediaType: a.mediaType, SizeBytes: a.size,
		Mtime:     a.mtime,
		CosWork:   sql.NullString{String: "8-24 手办", Valid: true},
		CreatedAt: now, UpdatedAt: now,
	}); err != nil {
		t.Fatalf("写入 cos_work 失败: %v", err)
	}

	resp := env.do(t, "GET", "/api/v1/assets?limit=10&includeCos=true", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("列表期望 200，得到 %d", resp.StatusCode)
	}
	var page gen.AssetPage
	if err := decodeBody(resp, &page); err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	closeBody(resp)
	byName := map[string]gen.AssetSummary{}
	for _, it := range deref(page.Items) {
		if it.FileName != nil {
			byName[*it.FileName] = it
		}
	}
	if w := byName["a.jpg"].CosWork; w == nil || *w != "8-24 手办" {
		t.Errorf("a.jpg cosWork 应为「8-24 手办」，得到 %v", byName["a.jpg"].CosWork)
	}
	for _, name := range []string{"b.jpg", "c.mp4"} {
		if w := byName[name].CosWork; w != nil {
			t.Errorf("%s（常规/无作品）cosWork 应缺省，得到 %v", name, *w)
		}
	}
}

// TestAssetDetailCosWork：详情端点 cosWork 装配（台账 #15 回归锁）——
// 有作品子目录的 COS 资产 detail 响应返回作品名，常规资产字段缺省
// （协议 null 语义 = 客户端详情页标题回退 fileName）。detail 直取
// assets.cos_work 列，与列表端点（fillListCosWork）同数据源。
func TestAssetDetailCosWork(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]
	b := testFiles[1]
	ctx := context.Background()
	now := store.FormatTimestamp(env.clock.Now())

	if _, err := env.q.UpsertAsset(ctx, db.UpsertAssetParams{
		AssetID: a.id, LibraryID: env.libID, RelPath: a.relPath,
		FileName: a.name, MediaType: a.mediaType, SizeBytes: a.size,
		Mtime:     a.mtime,
		CosWork:   sql.NullString{String: "8-24 手办", Valid: true},
		CreatedAt: now, UpdatedAt: now,
	}); err != nil {
		t.Fatalf("写入 cos_work 失败: %v", err)
	}

	cos := env.detail(t, a.id)
	if cos.CosWork == nil || *cos.CosWork != "8-24 手办" {
		t.Errorf("a.jpg 详情 cosWork 应为「8-24 手办」，得到 %v", cos.CosWork)
	}
	plain := env.detail(t, b.id)
	if plain.CosWork != nil {
		t.Errorf("b.jpg（常规）详情 cosWork 应缺省，得到 %v", *plain.CosWork)
	}
}

// ---------- directory 目录过滤（B-4：目录树「文件行」数据源） ----------

// seedSubdirAssets 直插子目录种子资产：UpsertAsset 是 scanner/filing/upload
// 三写路径共用的入库语句，relPath 含子目录段即可，列表查询只读库、
// 不需要磁盘真文件。返回直接子文件与递归孙文件的资产 ID。
func seedSubdirAssets(t *testing.T, e *testEnv) (direct, nested string) {
	t.Helper()
	now := store.FormatTimestamp(e.clock.Now())
	mk := func(relPath, name, mediaType string) string {
		id := uuid.NewString()
		if _, err := e.q.UpsertAsset(context.Background(), db.UpsertAssetParams{
			AssetID: id, LibraryID: e.libID, RelPath: relPath, FileName: name,
			MediaType: mediaType, SizeBytes: 100, Mtime: now, CreatedAt: now, UpdatedAt: now,
		}); err != nil {
			t.Fatalf("种子资产入库失败: %v", err)
		}
		return id
	}
	return mk("sub/d.jpg", "d.jpg", "image"), mk("sub/deep/e.mp4", "e.mp4", "video")
}

// TestAssetListDirectoryFilter：directory 参数四类语义（B-4 拍板口径）——
// 精确匹配只含该目录直接子文件（不含递归子目录）；空串=库根（库根文件
// 且仅库根文件）；与 mediaType 等既有筛选叠加生效；非法路径（含转义
// 形态的 ../）400 INVALID_PARAM。
func TestAssetListDirectoryFilter(t *testing.T) {
	env := newTestEnv(t)
	seedSubdirAssets(t, env) // sub/d.jpg + sub/deep/e.mp4；库根另有 a.jpg/b.jpg/c.mp4

	fetch := func(t *testing.T, query string) (int, gen.AssetPage, gen.Error) {
		t.Helper()
		resp := env.do(t, "GET", "/api/v1/assets"+query, "")
		defer func() { _ = resp.Body.Close() }()
		var page gen.AssetPage
		var errResp gen.Error
		if resp.StatusCode == http.StatusOK {
			_ = decodeBody(resp, &page)
		} else {
			_ = decodeBody(resp, &errResp)
		}
		return resp.StatusCode, page, errResp
	}
	names := func(p gen.AssetPage) []string {
		out := []string{}
		for _, it := range deref(p.Items) {
			if it.FileName != nil {
				out = append(out, *it.FileName)
			}
		}
		return out
	}

	// ① directory=sub：恰 d.jpg——不含递归子目录 deep/e.mp4、不含库根文件
	code, p, _ := fetch(t, "?directory=sub")
	if code != http.StatusOK {
		t.Fatalf("directory=sub 期望 200，得到 %d", code)
	}
	if got := names(p); len(got) != 1 || got[0] != "d.jpg" {
		t.Fatalf("directory=sub 应恰含 d.jpg（不含 deep/e.mp4），得到 %v", got)
	}
	if p.TotalMatched == nil || *p.TotalMatched != 1 {
		t.Fatalf("directory=sub totalMatched 应 1，得到 %v", p.TotalMatched)
	}

	// ② directory=（空串）=库根：只含库根三文件，不含 sub 下两个种子
	code, p, _ = fetch(t, "?directory=")
	if code != http.StatusOK {
		t.Fatalf("directory= 期望 200，得到 %d", code)
	}
	seen := map[string]bool{}
	for _, n := range names(p) {
		seen[n] = true
	}
	if len(deref(p.Items)) != 3 || !seen["a.jpg"] || !seen["b.jpg"] || !seen["c.mp4"] || seen["d.jpg"] || seen["e.mp4"] {
		t.Fatalf("directory= 应只含库根 a/b/c，得到 %v", names(p))
	}

	// ③ 组合叠加：directory=sub + mediaType=video → 空（d.jpg 是 image；
	// 递归孙文件 e.mp4 虽是 video 但不属于 sub 的直接子文件）
	code, p, _ = fetch(t, "?directory=sub&mediaType=video")
	if code != http.StatusOK {
		t.Fatalf("directory=sub&mediaType=video 期望 200，得到 %d", code)
	}
	if n := len(deref(p.Items)); n != 0 {
		t.Fatalf("directory=sub&mediaType=video 应为空，得到 %v", names(p))
	}

	// ③b 不存在的目录 → 200 + 空 items + totalMatched=0（目录树展开空
	// 目录的正常分支，非错误路径；与 B-4 隔离实例 curl 证据 ev2 对齐）
	code, p, _ = fetch(t, "?directory=no-such-dir")
	if code != http.StatusOK {
		t.Fatalf("directory=no-such-dir 期望 200，得到 %d", code)
	}
	if n := len(deref(p.Items)); n != 0 || p.TotalMatched == nil || *p.TotalMatched != 0 {
		t.Fatalf("directory=no-such-dir 应空列表 totalMatched=0，得到 %v / %v", names(p), p.TotalMatched)
	}

	// ④ 非法路径 → 400 INVALID_PARAM：../x 原文与 %2e%2e%2fx 转义形态
	//（服务端解码一次后即 ../x，NormalizeRelPath 的 .. 逃逸检查兜住）
	for _, q := range []string{"?directory=../x", "?directory=%2e%2e%2fx"} {
		code, _, errResp := fetch(t, q)
		if code != http.StatusBadRequest || errResp.Code != "INVALID_PARAM" {
			t.Fatalf("%s 期望 400 INVALID_PARAM，得到 %d %q", q, code, errResp.Code)
		}
	}
}

// TestAssetListMultiValueFilters：source/character/work 多值协议（2026-09-09
// 协议批，#29）——同维内 OR（数组内任一命中）、跨维 AND；单值=单元素
// 数组向后兼容；'其他' 桶可与具名出处混选。fixture 复用 seedFacetFixture
// （a.jpg 无出处+天使 / b.jpg kemono+天使+黑百合 / c.mp4 视频；COS 库
// 1.jpg 作品P、2.jpg 无作品——GET /assets 缺省排除 COS）。
func TestAssetListMultiValueFilters(t *testing.T) {
	env := newTestEnv(t)
	seedFacetFixture(t, env)

	fetchNames := func(query string) []string {
		t.Helper()
		resp := env.do(t, "GET", "/api/v1/assets"+query, "")
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("%s 期望 200，得到 %d", query, resp.StatusCode)
		}
		var page gen.AssetPage
		if err := decodeBody(resp, &page); err != nil {
			t.Fatalf("解析失败: %v", err)
		}
		closeBody(resp)
		out := make([]string, 0, len(deref(page.Items)))
		for _, it := range deref(page.Items) {
			out = append(out, *it.FileName)
		}
		sort.Strings(out)
		return out
	}
	fetchTotal := func(query string) int {
		t.Helper()
		resp := env.do(t, "GET", "/api/v1/assets"+query, "")
		var page gen.AssetPage
		if err := decodeBody(resp, &page); err != nil {
			t.Fatalf("解析失败: %v", err)
		}
		closeBody(resp)
		if page.TotalMatched == nil {
			t.Fatalf("%s 应返回 totalMatched", query)
		}
		return *page.TotalMatched
	}

	// 空=不过滤：无 source 参数 → 常规三资产（缺省排除 COS）。
	if got := fetchNames(""); len(got) != 3 {
		t.Fatalf("无筛选应 3 条（a/b/c），得到 %v", got)
	}
	// 同维双值 OR：kemono ∪ 其他 → b.jpg + a.jpg（+c.mp4 其他桶）= 3 条。
	if got := fetchNames("?source=kemono&source=" + percentEncode("其他")); len(got) != 3 {
		t.Fatalf("source 双值(kemono,其他) 应 3 条，得到 %v", got)
	}
	// 同维双值 OR（不含其他）：kemono ∪ 不存在 → 只 b.jpg。
	if got := fetchNames("?source=kemono&source=no-such"); len(got) != 1 || got[0] != "b.jpg" {
		t.Fatalf("source 双值(kemono,no-such) 应只 b.jpg，得到 %v", got)
	}
	// 单值 = 单元素数组（向后兼容，旧调用形态不变）。
	if got := fetchNames("?source=kemono"); len(got) != 1 || got[0] != "b.jpg" {
		t.Fatalf("source 单值应只 b.jpg，得到 %v", got)
	}
	// '其他' 单值桶 = 无出处常规文件。
	if got := fetchNames("?source=" + percentEncode("其他")); len(got) != 2 {
		t.Fatalf("source=其他 应 2 条（a/c），得到 %v", got)
	}
	// character 双组合 OR：天使组合 {a,b} ∪ 黑百合组合 {b} = {a,b}。
	if got := fetchNames("?character=" + percentEncode("天使") + "&character=" + percentEncode("黑百合")); len(got) != 2 {
		t.Fatalf("character 双组合应 2 条（a/b），得到 %v", got)
	}
	// character 组合表达式（组内 AND，旧语义不变）：天使+黑百合 → 只 b。
	if got := fetchNames("?character=" + percentEncode("天使+黑百合")); len(got) != 1 || got[0] != "b.jpg" {
		t.Fatalf("character=天使+黑百合 应只 b.jpg，得到 %v", got)
	}
	// 跨维 AND：source=kemono ∩ character=天使 → 只 b；
	// source=其他 ∩ character=黑百合 → 空。
	if got := fetchNames("?source=kemono&character=" + percentEncode("天使")); len(got) != 1 || got[0] != "b.jpg" {
		t.Fatalf("source∩character 应只 b.jpg，得到 %v", got)
	}
	if n := fetchTotal("?source=" + percentEncode("其他") + "&character=" + percentEncode("黑百合")); n != 0 {
		t.Fatalf("其他∩黑百合 应 0 条，得到 %d", n)
	}
	// work 多值（COS 分区须 includeCos=true）：作品P ∪ 不存在 → 只 1.jpg。
	if got := fetchNames("?includeCos=true&work=" + percentEncode("作品P") + "&work=no-such"); len(got) != 1 || got[0] != "1.jpg" {
		t.Fatalf("work 双值应只 1.jpg，得到 %v", got)
	}
	// work 单值兼容。
	if got := fetchNames("?includeCos=true&work=" + percentEncode("作品P")); len(got) != 1 || got[0] != "1.jpg" {
		t.Fatalf("work 单值应只 1.jpg，得到 %v", got)
	}
}
