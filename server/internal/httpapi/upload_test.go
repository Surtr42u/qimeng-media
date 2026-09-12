package httpapi

// 上传端点测试：真 JPEG 流式上传入库、冲突自动重命名、四道校验
// （魔数不符/白名单外扩展名/大小上限 413）、libraryId 校验、401、
// 落库后富化（normal=出处/角色、cos=作者关联/cos_work、noScanner 不炸）。

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/scanner"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/thumbnail"
)

// uploadBytes 发起流式上传，返回响应（调用方负责 close）。
func (e *testEnv) uploadBytes(t *testing.T, libraryID, dir, filename string, body []byte) *http.Response {
	t.Helper()
	return e.uploadReader(t, libraryID, dir, filename, strings.NewReader(string(body)))
}

// uploadReader 发起流式上传（body 为任意 io.Reader，供断连/中断模拟）。
func (e *testEnv) uploadReader(t *testing.T, libraryID, dir, filename string, body io.Reader) *http.Response {
	t.Helper()
	url := e.ts.URL + "/api/v1/assets/upload?libraryId=" + libraryID +
		"&dir=" + dir + "&filename=" + filename
	req, err := http.NewRequest(http.MethodPost, url, body)
	if err != nil {
		t.Fatalf("构造上传请求失败: %v", err)
	}
	req.Header.Set("Authorization", "Bearer "+e.token)
	req.Header.Set("Content-Type", "application/octet-stream")
	return e.doRaw(t, req)
}

func (e *testEnv) doRaw(t *testing.T, req *http.Request) *http.Response {
	t.Helper()
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	return resp
}

func makeJPG(t *testing.T, dir string, w, h int) []byte {
	t.Helper()
	p := filepath.Join(t.TempDir(), "x.jpg")
	writeTestJPG(t, p, w, h)
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatalf("读测试图片失败: %v", err)
	}
	return b
}

func TestUploadJPEGToSubdir(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 320, 240)
	resp := env.uploadBytes(t, env.libID, "手机照片", "IMG_0001.jpg", jpg)
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("上传期望 201，得到 %d", resp.StatusCode)
	}
	var d gen.AssetDetail
	if err := json.NewDecoder(resp.Body).Decode(&d); err != nil {
		t.Fatalf("解析上传响应失败: %v", err)
	}
	if d.RelPath == nil || *d.RelPath != "手机照片/IMG_0001.jpg" {
		t.Errorf("relPath = %v, 期望 手机照片/IMG_0001.jpg", d.RelPath)
	}
	if d.OrigUrl == nil || !strings.Contains(*d.OrigUrl, "/media/orig/") {
		t.Errorf("上传响应应带签名原图直链: %v", d.OrigUrl)
	}
	// 磁盘真实存在
	if _, err := os.Stat(filepath.Join(env.media, "手机照片", "IMG_0001.jpg")); err != nil {
		t.Errorf("文件未落盘: %v", err)
	}
	// 列表可见
	if _, ok := env.assetIDByName(t, "IMG_0001.jpg"); !ok {
		t.Error("上传后列表未出现新资产")
	}
}

// TestUploadConflictRename：同名文件存在 → 自动 "基名 (2).ext"。
func TestUploadConflictRename(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 64, 64)
	_ = env.uploadBytes(t, env.libID, "", "same.jpg", jpg)
	resp := env.uploadBytes(t, env.libID, "", "same.jpg", jpg)
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("冲突上传期望 201（自动重命名），得到 %d", resp.StatusCode)
	}
	var d gen.AssetDetail
	if err := json.NewDecoder(resp.Body).Decode(&d); err != nil {
		t.Fatalf("解析响应失败: %v", err)
	}
	if d.RelPath == nil || *d.RelPath != "same (2).jpg" {
		t.Errorf("冲突重命名 relPath = %v, 期望 same (2).jpg", d.RelPath)
	}
}

// flakyReader 先交出全部 head 字节再报错，模拟上传中途客户端断连
// （head ≥512B 保证走 io.Copy 的失败路径，而非"短文件整体完成"分支）。
type flakyReader struct {
	head []byte
}

func (r *flakyReader) Read(p []byte) (int, error) {
	if len(r.head) > 0 {
		n := copy(p, r.head)
		r.head = r.head[n:]
		return n, nil
	}
	return 0, errors.New("模拟客户端断连")
}

// TestUploadAtomicNoTempResidue：SECURITY 红线 4 落盘原子性——成功上传
// 后目标目录无 .qm-upload-* 临时残留；流中途断连（同步直调落盘子程）时
// 最终名不出现、临时文件被清理。
func TestUploadAtomicNoTempResidue(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 64, 64)

	resp := env.uploadBytes(t, env.libID, "", "ok.jpg", jpg)
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("正常上传期望 201，得到 %d", resp.StatusCode)
	}
	assertNoUploadTemp(t, env.media)

	// 失败路径：合法 JPEG 头（≥512B，保证走 io.Copy 失败分支而非"短文件
	// 整体完成"分支）过魔数校验后读流中断。
	head := append(append([]byte{}, jpg...), make([]byte, filing.RecommendedHeadBytes)...)[:filing.RecommendedHeadBytes]
	req := httptest.NewRequest(http.MethodPost, "/api/v1/assets/upload", &flakyReader{head: head})
	w := httptest.NewRecorder()
	// #9 清欠后收流与改名拆分：这里直调收流半程（receiveUploadToTmp），
	// 断连发生在写 tmp 阶段——半成品 tmp 应被清理、最终名不出现（改名段
	// 的原子性由 handler 的 WithLibraryGate 关键段保证，端到端用例另测）。
	if _, ok := env.s.receiveUploadToTmp(w, req, gen.PostApiV1AssetsUploadParams{Filename: "boom.jpg"},
		env.media, 1<<20); ok {
		t.Fatal("中断上传 receiveUploadToTmp 应返回 false")
	}
	if w.Code != http.StatusBadRequest {
		t.Fatalf("断连上传期望 400，得到 %d", w.Code)
	}
	if _, err := os.Stat(filepath.Join(env.media, "boom.jpg")); !os.IsNotExist(err) {
		t.Error("中断上传不应出现最终名文件（原子性：半成品只可能是 .tmp）")
	}
	assertNoUploadTemp(t, env.media)
}

// assertNoUploadTemp 断言目录内无上传临时文件残留（.qm-upload-* 前缀）。
func assertNoUploadTemp(t *testing.T, dir string) {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("读目录 %s 失败: %v", dir, err)
	}
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), uploadTmpPrefix) {
			t.Errorf("目录 %s 存在上传临时残留 %s", dir, e.Name())
		}
	}
}

// TestUploadMimeMismatch：.jpg 里装文本 → 400（魔数与扩展名不符）。
func TestUploadMimeMismatch(t *testing.T) {
	env := newTestEnv(t)
	resp := env.uploadBytes(t, env.libID, "", "evil.jpg", []byte("this is not a jpeg at all........"))
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("魔数不符期望 400，得到 %d", resp.StatusCode)
	}
	// 磁盘无残留
	if _, err := os.Stat(filepath.Join(env.media, "evil.jpg")); !os.IsNotExist(err) {
		t.Error("被拒上传不应留文件")
	}
}

// TestUploadBadExtension：白名单外扩展名 → 400。
func TestUploadBadExtension(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 32, 32)
	resp := env.uploadBytes(t, env.libID, "", "payload.exe", jpg)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("白名单外扩展名期望 400，得到 %d", resp.StatusCode)
	}
}

// TestUploadTooLarge：Content-Length 超限 → 413 且不落盘。
func TestUploadTooLarge(t *testing.T) {
	env := newTestEnv(t)
	env.cfg.Upload.MaxBytes = 1024 // 本用例限 1KB（Server 持有同一 cfg 指针）
	jpg := makeJPG(t, t.TempDir(), 320, 240)
	resp := env.uploadBytes(t, env.libID, "", "big.jpg", jpg)
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("超限上传期望 413，得到 %d", resp.StatusCode)
	}
	if _, err := os.Stat(filepath.Join(env.media, "big.jpg")); !os.IsNotExist(err) {
		t.Error("超限上传不应留文件")
	}
	env.cfg.Upload.MaxBytes = 0 // 恢复默认，避免影响同 env 的其他断言
}

// TestUploadBadLibrary：libraryId 不存在 → 400。
func TestUploadBadLibrary(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 32, 32)
	resp := env.uploadBytes(t, "00000000-0000-0000-0000-000000000000", "", "x.jpg", jpg)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("库不存在期望 400，得到 %d", resp.StatusCode)
	}
}

// TestUploadUnauthorized：无 token 401。
func TestUploadUnauthorized(t *testing.T) {
	env := newTestEnv(t)
	req, _ := http.NewRequest(http.MethodPost,
		env.ts.URL+"/api/v1/assets/upload?libraryId=x&dir=&filename=a.jpg",
		strings.NewReader("x"))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("无 token 期望 401，得到 %d", resp.StatusCode)
	}
}

// realScannerAdapter 测试侧适配器：真实 *scanner.Scanner → httpapi.Scanner。
// 与 cmd wire.go 的 scannerAdapter 同职责；测试环境无需异步化（上传测试
// 不触发扫描端点），Scan 走同步直通，富化三方法全部直通。
type realScannerAdapter struct {
	sc *scanner.Scanner
	q  *db.Queries
}

func (a realScannerAdapter) Scan(ctx context.Context, libraryID string) error {
	lib, err := a.q.GetLibrary(ctx, libraryID)
	if err != nil {
		return err
	}
	_, err = a.sc.Scan(ctx, lib)
	return err
}

func (a realScannerAdapter) EnrichAsset(ctx context.Context, libraryID, assetID string) error {
	return a.sc.EnrichAsset(ctx, libraryID, assetID)
}

func (a realScannerAdapter) UpdateCustomSources(ctx context.Context, names []string) error {
	a.sc.UpdateCustomSources(ctx, names)
	return nil
}

func (a realScannerAdapter) RecomputeEnrichment(ctx context.Context, libraryID string) error {
	return a.sc.RecomputeEnrichment(ctx, libraryID)
}

// newRealScannerEnv 建一个装配真实 scanner 的环境（上传富化测试用：
// SourceMatcher 内置检索表与 COS 作者目录映射必须走生产实现——fakeScanner
// 的富化空实现锁不住"上传落库后富化被真正触发"的契约）。kind 为库类型
// （normal/cos，0005 CHECK 约束只认这两个值）。
func newRealScannerEnv(t *testing.T, kind string) *testEnv {
	t.Helper()
	root := t.TempDir()
	dataDir := filepath.Join(root, "data")
	media := filepath.Join(root, "media")
	for _, d := range []string{media, dataDir} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			t.Fatalf("创建目录失败: %v", err)
		}
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
		ID: uuid.NewString(), Name: "富化测试库", RootPath: media,
		Kind:      kind,
		CreatedAt: store.FormatTimestamp(time.Now()),
	})
	if err != nil {
		t.Fatalf("建库失败: %v", err)
	}
	bus := events.NewBus(nil, 0)
	cfg := &config.Config{DataDir: dataDir, Thumbnail: config.ThumbnailConfig{LongSide: 512}}
	real := scanner.New(q, bus, nil, dataDir, nil)
	apisrv, err := New(Deps{
		Conn: conn, Queries: q, Bus: bus, Cfg: cfg,
		Thumbs:      thumbnail.NewGenerator(dataDir, nil, thumbnail.Options{}),
		Scanner:     realScannerAdapter{sc: real, q: q},
		MediaSecret: []byte("upload-enrich-secret-0123456789abcdef"),
	})
	if err != nil {
		t.Fatalf("组装服务失败: %v", err)
	}
	ts := httptest.NewServer(apisrv.Handler())
	t.Cleanup(ts.Close)
	env := &testEnv{ts: ts, q: q, conn: conn, libID: lib.ID, clock: &fakeClock{now: time.Now()}, media: media, dataDir: dataDir, cfg: cfg}
	// setup 拿 token（时序同 newNoScannerEnv：本环境不扫描，无需 seed）
	resp, err := http.Post(ts.URL+"/api/v1/auth/setup", "application/json",
		strings.NewReader(fmt.Sprintf(`{"password":"%s"}`, testPasswordMain)))
	if err != nil {
		t.Fatalf("setup 失败: %v", err)
	}
	var tok gen.AuthToken
	_ = decodeBody(resp, &tok)
	_ = resp.Body.Close()
	if tok.Token == nil || *tok.Token == "" {
		t.Fatal("setup 未返回 token")
	}
	env.token = *tok.Token
	return env
}

// TestUploadEnrichesNormalLibrary：上传到 normal 库后富化按文件名生效——
// 内置检索表命中（守望先锋_天使 → source=守望先锋 + 角色 天使，同 scanner
// enrich_test 的真实命中夹具）；未命中文件 source 保持 NULL、无角色行
// （匹配失败不影响上传成功）。
func TestUploadEnrichesNormalLibrary(t *testing.T) {
	env := newRealScannerEnv(t, "normal")
	jpg := makeJPG(t, t.TempDir(), 64, 64)

	// 命中内置表
	resp := env.uploadBytes(t, env.libID, "", "守望先锋_天使.jpg", jpg)
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("上传期望 201，得到 %d", resp.StatusCode)
	}
	var d gen.AssetDetail
	if err := json.NewDecoder(resp.Body).Decode(&d); err != nil {
		t.Fatalf("解析上传响应失败: %v", err)
	}
	ctx := context.Background()
	hit, err := env.q.GetAsset(ctx, d.Id.String())
	if err != nil {
		t.Fatalf("查询上传资产失败: %v", err)
	}
	if !hit.Source.Valid || hit.Source.String != "守望先锋" {
		t.Errorf("上传资产 source=%v, want 守望先锋（富化按文件名命中）", hit.Source)
	}
	names, err := env.q.ListAssetCharacterNames(ctx, hit.AssetID)
	if err != nil {
		t.Fatalf("ListAssetCharacterNames 失败: %v", err)
	}
	if len(names) != 1 || names[0] != "天使" {
		t.Errorf("上传资产角色=%v, want [天使]", names)
	}

	// 未命中：source NULL、无角色（富化的兜底行为，不炸不误标）
	resp2 := env.uploadBytes(t, env.libID, "", "randomfile.jpg", jpg)
	defer func() { _ = resp2.Body.Close() }()
	if resp2.StatusCode != http.StatusCreated {
		t.Fatalf("未命中上传期望 201，得到 %d", resp2.StatusCode)
	}
	var d2 gen.AssetDetail
	if err := json.NewDecoder(resp2.Body).Decode(&d2); err != nil {
		t.Fatalf("解析响应失败: %v", err)
	}
	miss, err := env.q.GetAsset(ctx, d2.Id.String())
	if err != nil {
		t.Fatalf("查询未命中资产失败: %v", err)
	}
	if miss.Source.Valid {
		t.Errorf("未命中文件 source=%v, want NULL", miss.Source)
	}
	if got, err := env.q.ListAssetCharacterNames(ctx, miss.AssetID); err != nil || len(got) != 0 {
		t.Errorf("未命中文件角色=%v err=%v, want 空", got, err)
	}
}

// TestUploadEnrichesCosLibrary：上传到 cos 库（rel 带作者/作品两级目录）→
// COS 作者关联建立、cos_work = 第二段目录名、source 保持 NULL（隔离口径，
// 与扫描 ingest 同口径——上传与扫描两条写入路径的富化结果必须一致）。
func TestUploadEnrichesCosLibrary(t *testing.T) {
	env := newRealScannerEnv(t, "cos")
	jpg := makeJPG(t, t.TempDir(), 64, 64)
	resp := env.uploadBytes(t, env.libID, "水淼Aqua/不知火舞", "1.jpg", jpg)
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("上传期望 201，得到 %d", resp.StatusCode)
	}
	var d gen.AssetDetail
	if err := json.NewDecoder(resp.Body).Decode(&d); err != nil {
		t.Fatalf("解析上传响应失败: %v", err)
	}
	if d.RelPath == nil || *d.RelPath != "水淼Aqua/不知火舞/1.jpg" {
		t.Fatalf("relPath = %v, want 水淼Aqua/不知火舞/1.jpg", d.RelPath)
	}
	ctx := context.Background()
	a, err := env.q.GetAsset(ctx, d.Id.String())
	if err != nil {
		t.Fatalf("查询上传资产失败: %v", err)
	}
	if !a.CosWork.Valid || a.CosWork.String != "不知火舞" {
		t.Errorf("cos_work=%v, want 不知火舞（rel 第二段目录）", a.CosWork)
	}
	if a.Source.Valid {
		t.Errorf("cos 库文件 source=%v, want NULL（隔离口径）", a.Source)
	}
	refs, err := env.q.ListAssetAuthorRefs(ctx, a.AssetID)
	if err != nil {
		t.Fatalf("ListAssetAuthorRefs 失败: %v", err)
	}
	wantID := authoring.GenerateCosAuthorID("水淼Aqua")
	if len(refs) != 1 || refs[0].ID != wantID || refs[0].Type != authoring.AuthorTypeCos {
		t.Errorf("关联作者=%+v, want [{%s cos}]", refs, wantID)
	}
}

// TestUploadWithNoScannerStillCreated：扫描器未装配（noScanner 占位返回
// ErrScannerUnavailable）时上传仍 201 且资产已入库——富化尽力而为，
// 不得拖垮上传主流程（同 trash 恢复语义）。
func TestUploadWithNoScannerStillCreated(t *testing.T) {
	env := newNoScannerEnv(t)
	jpg := makeJPG(t, t.TempDir(), 32, 32)
	resp := env.uploadBytes(t, env.libID, "", "plain.jpg", jpg)
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("noScanner 上传期望 201，得到 %d", resp.StatusCode)
	}
	var d gen.AssetDetail
	if err := json.NewDecoder(resp.Body).Decode(&d); err != nil {
		t.Fatalf("解析上传响应失败: %v", err)
	}
	if _, err := env.q.GetAsset(context.Background(), d.Id.String()); err != nil {
		t.Errorf("noScanner 上传资产应已入库: %v", err)
	}
}
