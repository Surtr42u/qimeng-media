package scanner

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/thumbnail"
)

// ---------- 测试基础设施 ----------

// probeStub 视频探测替身：计数、可控结果、可阻塞（防重入测试用：
// 阻塞住第一次探测即阻塞住整个 Scan，制造"扫描进行中"窗口）。
type probeStub struct {
	calls   atomic.Int32
	res     *thumbnail.ProbeResult
	err     error
	entered chan struct{} // 每次调用投递信号（缓冲，非阻塞）
	block   chan struct{} // 非 nil 时每次调用阻塞到该 channel 关闭
}

func newProbeStub(res *thumbnail.ProbeResult, err error) *probeStub {
	return &probeStub{res: res, err: err, entered: make(chan struct{}, 16)}
}

func (p *probeStub) call(ctx context.Context, path string) (*thumbnail.ProbeResult, error) {
	p.calls.Add(1)
	select {
	case p.entered <- struct{}{}:
	default:
	}
	if p.block != nil {
		<-p.block
	}
	return p.res, p.err
}

// testEnv 一个测试环境：真实 SQLite 库 + 临时目录文件树 + 静音日志的扫描器。
type testEnv struct {
	s    *Scanner
	q    *db.Queries
	bus  *events.Bus
	lib  db.Library
	conn *sql.DB // 富化测试需要置 libraries.kind（COS 库）等库级操作
}

func newTestEnv(t *testing.T, probe ProbeFunc) *testEnv {
	t.Helper()
	conn, err := store.Open(filepath.Join(t.TempDir(), "scan.db"))
	if err != nil {
		t.Fatalf("打开测试库失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := store.Migrate(conn); err != nil {
		t.Fatalf("迁移测试库失败: %v", err)
	}
	q := db.New(conn)

	root := t.TempDir()
	lib, err := q.CreateLibrary(context.Background(), db.CreateLibraryParams{
		ID:        uuid.NewString(),
		Name:      "测试库",
		RootPath:  root,
		Kind:      LibraryKindNormal,
		CreatedAt: store.FormatTimestamp(time.Now()),
	})
	if err != nil {
		t.Fatalf("创建测试库记录失败: %v", err)
	}

	bus := events.NewBus(nil, 128)
	t.Cleanup(bus.Close)
	s := New(q, bus, slog.New(slog.NewTextHandler(io.Discard, nil)), "")
	s.probe = probe
	s.progressMinEvery = 0 // 每文件发进度，测试可稳定收到事件
	return &testEnv{s: s, q: q, bus: bus, lib: lib, conn: conn}
}

// writeFile 在库内相对路径写入指定字节的文件（自动建父目录）。
func (e *testEnv) writeFile(t *testing.T, rel string, size int) {
	t.Helper()
	abs := filepath.Join(e.lib.RootPath, filepath.FromSlash(rel))
	if err := os.MkdirAll(filepath.Dir(abs), 0o755); err != nil {
		t.Fatalf("建目录失败: %v", err)
	}
	if err := os.WriteFile(abs, bytes.Repeat([]byte("x"), size), 0o644); err != nil {
		t.Fatalf("写文件失败: %v", err)
	}
}

func (e *testEnv) abs(rel string) string {
	return filepath.Join(e.lib.RootPath, filepath.FromSlash(rel))
}

func (e *testEnv) assetByPath(t *testing.T, rel string) db.Asset {
	t.Helper()
	a, err := e.q.GetAssetByPath(context.Background(), db.GetAssetByPathParams{
		LibraryID: e.lib.ID, RelPath: rel,
	})
	if err != nil {
		t.Fatalf("查询资产 %s 失败: %v", rel, err)
	}
	return a
}

func (e *testEnv) assetCount(t *testing.T) int {
	t.Helper()
	list, err := e.q.ListAssetsByLibrary(context.Background(), e.lib.ID)
	if err != nil {
		t.Fatalf("ListAssetsByLibrary 失败: %v", err)
	}
	return len(list)
}

// drainTopic 订阅指定主题（必须在触发动作之前订阅——总线不重放历史）。
func drainTopic(t *testing.T, bus *events.Bus, topics ...string) *events.Subscription {
	t.Helper()
	sub, err := bus.Subscribe(topics...)
	if err != nil {
		t.Fatalf("订阅失败: %v", err)
	}
	t.Cleanup(sub.Close)
	return sub
}

// collect 非阻塞收走订阅缓冲里的全部事件。
func collect(sub *events.Subscription) []events.Event {
	var out []events.Event
	for {
		select {
		case ev, ok := <-sub.C:
			if !ok {
				return out
			}
			out = append(out, ev)
		default:
			return out
		}
	}
}

// eventually 轮询等待条件成立（Watch 类测试的事件时序不可控，必须轮询）。
func eventually(t *testing.T, timeout time.Duration, msg string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("等待超时: %s", msg)
}

// ---------- 媒体识别 ----------

func TestClassifyMedia(t *testing.T) {
	cases := []struct {
		name string
		want string
		ok   bool
	}{
		{"a.jpg", MediaTypeImage, true},
		{"a.JPEG", MediaTypeImage, true}, // 大小写不敏感
		{"IMG_001.PNG", MediaTypeImage, true},
		{"b.webp", MediaTypeImage, true},
		{"c.avif", MediaTypeImage, true},
		{"d.gif", MediaTypeAnimatedImage, true}, // gif 单列动图
		{"E.GIF", MediaTypeAnimatedImage, true},
		{"v.mp4", MediaTypeVideo, true},
		{"v.MKV", MediaTypeVideo, true},
		{"v.webm", MediaTypeVideo, true},
		{"v.mov", MediaTypeVideo, true},
		{"v.m4v", MediaTypeVideo, true},
		{"v.avi", MediaTypeVideo, true},
		{"note.txt", "", false},
		{"noext", "", false},
		{"archive.tar.gz", "", false},
	}
	for _, c := range cases {
		got, ok := ClassifyMedia(c.name)
		if got != c.want || ok != c.ok {
			t.Errorf("ClassifyMedia(%q) = (%q,%v), want (%q,%v)", c.name, got, ok, c.want, c.ok)
		}
	}
}

func TestSkipDir(t *testing.T) {
	skip := []string{".hidden", ".trash", ".Recycled", "#recycle", "$RECYCLE.BIN", "_gsdata_", "System Volume Information"}
	for _, name := range skip {
		if !SkipDir(name) {
			t.Errorf("SkipDir(%q) = false, want true", name)
		}
	}
	keep := []string{"photos", "2024", "我的视频"}
	for _, name := range keep {
		if SkipDir(name) {
			t.Errorf("SkipDir(%q) = true, want false", name)
		}
	}
}

// ---------- 全量扫描：入库与分类 ----------

// TestScanIngestsAndClassifies：图片/视频/gif 分类正确、隐藏文件与非白名单跳过、
// 仅视频走探测、进度与完成事件齐发。
func TestScanIngestsAndClassifies(t *testing.T) {
	probe := newProbeStub(&thumbnail.ProbeResult{Duration: 2 * time.Second, Width: 640, Height: 480}, nil)
	env := newTestEnv(t, probe.call)
	// 事件订阅必须在 Scan 之前（总线不重放历史）。
	progSub := drainTopic(t, env.bus, events.TopicScanProgress)
	changedSub := drainTopic(t, env.bus, events.TopicLibraryChanged)

	env.writeFile(t, "a.jpg", 100)
	env.writeFile(t, "b.GIF", 200)      // 大写扩展名 → 动图
	env.writeFile(t, "sub/c.png", 300)  // 子目录
	env.writeFile(t, "v.mp4", 1000)     // 唯一视频
	env.writeFile(t, ".hidden.jpg", 50) // 隐藏：跳过
	env.writeFile(t, "note.txt", 50)    // 非白名单：跳过
	env.writeFile(t, "sub2/d.webp", 10)

	res, err := env.s.Scan(context.Background(), env.lib)
	if err != nil {
		t.Fatalf("Scan 失败: %v", err)
	}
	if res.Added != 5 || res.Scanned != 5 {
		t.Errorf("Added=%d Scanned=%d, want 5/5（隐藏与白名单外文件必须跳过）", res.Added, res.Scanned)
	}
	if got := probe.calls.Load(); got != 1 {
		t.Errorf("视频探测调用 %d 次, want 1（仅视频、图片不探测）", got)
	}
	if env.assetCount(t) != 5 {
		t.Fatalf("库内记录 %d 条, want 5", env.assetCount(t))
	}

	for rel, want := range map[string]string{
		"a.jpg":       MediaTypeImage,
		"b.GIF":       MediaTypeAnimatedImage,
		"sub/c.png":   MediaTypeImage,
		"v.mp4":       MediaTypeVideo,
		"sub2/d.webp": MediaTypeImage,
	} {
		if got := env.assetByPath(t, rel).MediaType; got != want {
			t.Errorf("%s media_type=%q, want %q", rel, got, want)
		}
	}

	// 视频元数据入库；图片元数据为空（M1 图片不探测）。
	va := env.assetByPath(t, "v.mp4")
	if !va.DurationMs.Valid || va.DurationMs.Int64 != 2000 {
		t.Errorf("v.mp4 duration_ms=%v, want 2000", va.DurationMs)
	}
	if !va.Width.Valid || va.Width.Int64 != 640 || !va.Height.Valid || va.Height.Int64 != 480 {
		t.Errorf("v.mp4 宽高=%v/%v, want 640/480", va.Width, va.Height)
	}
	ia := env.assetByPath(t, "a.jpg")
	if ia.Width.Valid || ia.DurationMs.Valid {
		t.Errorf("图片元数据应为空（M1 不探测图片），得到 width=%v duration=%v", ia.Width, ia.DurationMs)
	}

	// 事件：进度（walking + reconciling）与 library.changed。
	progEvents := collect(progSub)
	if len(progEvents) == 0 {
		t.Error("未收到任何 scan.progress 事件")
	}
	phases := map[string]bool{}
	for _, ev := range progEvents {
		if p, ok := ev.Payload.(scanProgressPayload); ok {
			phases[p.Phase] = true
			if p.Total < p.Scanned {
				t.Errorf("进度 total(%d) < scanned(%d)", p.Total, p.Scanned)
			}
		}
	}
	if !phases[phaseWalking] || !phases[phaseReconciling] {
		t.Errorf("进度事件阶段不全: %v（want walking+reconciling）", phases)
	}
	changed := collect(changedSub)
	if len(changed) != 1 {
		t.Fatalf("library.changed 事件 %d 条, want 1", len(changed))
	}
	if payload, ok := changed[0].Payload.(ScanResult); !ok || payload.Added != 5 {
		t.Errorf("library.changed payload 异常: %+v", changed[0].Payload)
	}
}

// ---------- 变更检测 ----------

// TestChangeDetection：size+mtime 未变的文件不重探测；size 变了重探测。
func TestChangeDetection(t *testing.T) {
	probe := newProbeStub(&thumbnail.ProbeResult{Duration: time.Second, Width: 100, Height: 100}, nil)
	env := newTestEnv(t, probe.call)
	env.writeFile(t, "v.mp4", 500)
	env.writeFile(t, "a.jpg", 50)

	if _, err := env.s.Scan(context.Background(), env.lib); err != nil {
		t.Fatalf("首扫失败: %v", err)
	}
	if got := probe.calls.Load(); got != 1 {
		t.Fatalf("首扫探测 %d 次, want 1", got)
	}

	// 什么都不动重扫：mtime/size 一致 → 全部跳过（touch 类操作不改变二者）。
	res, err := env.s.Scan(context.Background(), env.lib)
	if err != nil {
		t.Fatalf("重扫失败: %v", err)
	}
	if got := probe.calls.Load(); got != 1 {
		t.Errorf("无变更重扫后又探测了（calls=%d, want 仍为 1）", got)
	}
	if res.Updated != 0 || res.Added != 0 {
		t.Errorf("无变更重扫 Added=%d Updated=%d, want 0/0", res.Added, res.Updated)
	}

	// 改视频 size（内容追加，mtime 随之前进）→ 仅该文件重探测+更新。
	env.writeFile(t, "v.mp4", 900)
	res, err = env.s.Scan(context.Background(), env.lib)
	if err != nil {
		t.Fatalf("变更后重扫失败: %v", err)
	}
	if got := probe.calls.Load(); got != 2 {
		t.Errorf("size 变更后探测 %d 次, want 2", got)
	}
	if res.Updated != 1 || res.Added != 0 {
		t.Errorf("变更后重扫 Added=%d Updated=%d, want 0/1", res.Added, res.Updated)
	}
	if env.assetByPath(t, "v.mp4").SizeBytes != 900 {
		t.Error("size 更新未落库")
	}
}

// ---------- 移动合并 ----------

// TestMoveMergeRename：rename（同目录/移入子目录）不改 size/mtime →
// 判定移动，asset_id 保留、路径属性更新（ARCHITECTURE §6）。
func TestMoveMergeRename(t *testing.T) {
	probe := newProbeStub(&thumbnail.ProbeResult{Duration: time.Second, Width: 10, Height: 10}, nil)
	env := newTestEnv(t, probe.call)
	env.writeFile(t, "a.mp4", 777)
	if _, err := env.s.Scan(context.Background(), env.lib); err != nil {
		t.Fatalf("首扫失败: %v", err)
	}
	idBefore := env.assetByPath(t, "a.mp4").AssetID

	// 同目录改名。
	if err := os.Rename(env.abs("a.mp4"), env.abs("b.mp4")); err != nil {
		t.Fatalf("rename 失败: %v", err)
	}
	res, err := env.s.Scan(context.Background(), env.lib)
	if err != nil {
		t.Fatalf("重扫失败: %v", err)
	}
	if res.Moved != 1 || res.Added != 0 || res.Removed != 0 {
		t.Errorf("改名后 Moved=%d Added=%d Removed=%d, want 1/0/0", res.Moved, res.Added, res.Removed)
	}
	after := env.assetByPath(t, "b.mp4")
	if after.AssetID != idBefore {
		t.Errorf("改名后 asset_id 变了（%s → %s），移动合并失效", idBefore, after.AssetID)
	}
	if env.assetCount(t) != 1 {
		t.Errorf("改名后库内 %d 条, want 1（旧记录必须只剩一份）", env.assetCount(t))
	}

	// 移入子目录。
	if err := os.Mkdir(env.abs("sub"), 0o755); err != nil {
		t.Fatalf("mkdir 失败: %v", err)
	}
	if err := os.Rename(env.abs("b.mp4"), env.abs("sub/c.mp4")); err != nil {
		t.Fatalf("rename 失败: %v", err)
	}
	res, err = env.s.Scan(context.Background(), env.lib)
	if err != nil {
		t.Fatalf("重扫失败: %v", err)
	}
	if res.Moved != 1 {
		t.Errorf("移入子目录后 Moved=%d, want 1", res.Moved)
	}
	moved := env.assetByPath(t, "sub/c.mp4")
	if moved.AssetID != idBefore {
		t.Errorf("移入子目录后 asset_id 变了，移动合并失效")
	}
	if moved.FileName != "c.mp4" {
		t.Errorf("合并后 file_name=%q, want c.mp4", moved.FileName)
	}
}

// TestMoveWithChangeNotMerged：size/mtime 变了的"移动"不合并，
// 按删除旧记录+新增新记录处理（启发式的防误判底线）。
func TestMoveWithChangeNotMerged(t *testing.T) {
	probe := newProbeStub(&thumbnail.ProbeResult{}, nil)
	env := newTestEnv(t, probe.call)
	env.writeFile(t, "a.mp4", 300)
	if _, err := env.s.Scan(context.Background(), env.lib); err != nil {
		t.Fatalf("首扫失败: %v", err)
	}
	idBefore := env.assetByPath(t, "a.mp4").AssetID

	if err := os.Rename(env.abs("a.mp4"), env.abs("b.mp4")); err != nil {
		t.Fatalf("rename 失败: %v", err)
	}
	env.writeFile(t, "b.mp4", 999) // 内容变化：size+mtime 都变了

	res, err := env.s.Scan(context.Background(), env.lib)
	if err != nil {
		t.Fatalf("重扫失败: %v", err)
	}
	if res.Moved != 0 || res.Added != 1 || res.Removed != 1 {
		t.Errorf("size+mtime 变化的移动: Moved=%d Added=%d Removed=%d, want 0/1/1", res.Moved, res.Added, res.Removed)
	}
	if newID := env.assetByPath(t, "b.mp4").AssetID; newID == idBefore {
		t.Error("变更后的移动不应沿用旧 asset_id（会污染身份语义）")
	}
}

// TestPickMergeCandidate：候选选择纯函数——无匹配/单匹配/多候选取最新。
func TestPickMergeCandidate(t *testing.T) {
	gone := db.Asset{SizeBytes: 100, Mtime: "2026-01-01T00:00:00.000Z"}
	// cand(id, createdAt, mtime)：候选的 mtime 必须等于 gone 的才能匹配，
	// created_at 独立控制（歧义时取最新）。
	mk := func(id, createdAt, mtime string, size int64) db.Asset {
		return db.Asset{AssetID: id, Mtime: mtime, SizeBytes: size, CreatedAt: createdAt}
	}
	if got, n := pickMergeCandidate(nil, gone); got != -1 || n != 0 {
		t.Errorf("空候选池返回 (%d,%d), want (-1,0)", got, n)
	}
	sizeMismatch := mk("a", "2026-02-01T00:00:00.000Z", gone.Mtime, 999)
	if got, n := pickMergeCandidate([]db.Asset{sizeMismatch}, gone); got != -1 || n != 0 {
		t.Errorf("size 不匹配却返回 (%d,%d), want (-1,0)", got, n)
	}
	mtimeMismatch := mk("a", "2026-02-01T00:00:00.000Z", "2026-01-02T00:00:00.000Z", gone.SizeBytes)
	if got, n := pickMergeCandidate([]db.Asset{mtimeMismatch}, gone); got != -1 || n != 0 {
		t.Errorf("mtime 不匹配却返回 (%d,%d), want (-1,0)", got, n)
	}
	// 多候选歧义：取 created_at 最新（同 created_at 时 asset_id 字典序兜底），
	// 匹配数如实返回供调用方 warn。
	cands := []db.Asset{
		mk("id-old", "2026-02-01T00:00:00.000Z", gone.Mtime, gone.SizeBytes), // created_at 较旧
		mk("id-new", "2026-06-01T00:00:00.000Z", gone.Mtime, gone.SizeBytes), // created_at 最新
	}
	if got, n := pickMergeCandidate(cands, gone); got != 1 || n != 2 {
		t.Errorf("多候选返回 (%d,%d), want (1,2)（created_at 最新者+歧义计数）", got, n)
	}
	tie := []db.Asset{
		mk("aaa", "2026-02-01T00:00:00.000Z", gone.Mtime, gone.SizeBytes),
		mk("zzz", "2026-02-01T00:00:00.000Z", gone.Mtime, gone.SizeBytes), // 同 created_at，asset_id 更大
	}
	if got, n := pickMergeCandidate(tie, gone); got != 1 || n != 2 {
		t.Errorf("同 created_at 候选返回 (%d,%d), want (1,2)（asset_id 兜底）", got, n)
	}
}

// ---------- 消失文件 ----------

// TestDisappearRemovesKeepsEvents：文件删除 → 记录删除、view_events 保留
// （外键故意不设，adr/0005）。
func TestDisappearRemovesKeepsEvents(t *testing.T) {
	probe := newProbeStub(nil, nil)
	env := newTestEnv(t, probe.call)
	env.writeFile(t, "a.mp4", 100)
	if _, err := env.s.Scan(context.Background(), env.lib); err != nil {
		t.Fatalf("首扫失败: %v", err)
	}
	id := env.assetByPath(t, "a.mp4").AssetID

	// 造历史行为事件（统计底座，adr/0005）。
	if err := env.q.InsertViewEvent(context.Background(), db.InsertViewEventParams{
		AssetID: id, Kind: "open", SessionID: "sess-1",
		StartedAt: store.FormatTimestamp(time.Now()),
	}); err != nil {
		t.Fatalf("InsertViewEvent 失败: %v", err)
	}

	if err := os.Remove(env.abs("a.mp4")); err != nil {
		t.Fatalf("删除文件失败: %v", err)
	}
	res, err := env.s.Scan(context.Background(), env.lib)
	if err != nil {
		t.Fatalf("重扫失败: %v", err)
	}
	if res.Removed != 1 || res.Moved != 0 {
		t.Errorf("消失处理 Removed=%d Moved=%d, want 1/0", res.Removed, res.Moved)
	}
	if env.assetCount(t) != 0 {
		t.Error("消失文件的记录未删除")
	}
	counts, err := env.q.CountAssetEvents(context.Background(), id)
	if err != nil {
		t.Fatalf("CountAssetEvents 失败: %v", err)
	}
	if len(counts) == 0 || counts[0].Cnt != 1 {
		t.Errorf("view_events 应保留（历史事实），得到 %v", counts)
	}
}

// ---------- 防重入 ----------

// TestConcurrentScanRejected：扫描进行中再次触发返回 ErrAlreadyScanning，
// 只有一个真正执行。
func TestConcurrentScanRejected(t *testing.T) {
	probe := newProbeStub(&thumbnail.ProbeResult{}, nil)
	probe.block = make(chan struct{})
	env := newTestEnv(t, probe.call)
	env.writeFile(t, "v.mp4", 100)

	scanDone := make(chan error, 1)
	go func() {
		_, err := env.s.Scan(context.Background(), env.lib)
		scanDone <- err
	}()

	select {
	case <-probe.entered: // 第一次探测已进入：Scan 正在进行中
	case <-time.After(2 * time.Second):
		t.Fatal("首扫迟迟未进入探测（测试前提不成立）")
	}

	_, err := env.s.Scan(context.Background(), env.lib)
	if !errors.Is(err, ErrAlreadyScanning) {
		t.Errorf("并发第二次 Scan 返回 %v, want ErrAlreadyScanning", err)
	}

	close(probe.block) // 放行首扫
	if err := <-scanDone; err != nil {
		t.Fatalf("首扫不应失败: %v", err)
	}
	if env.assetCount(t) != 1 {
		t.Errorf("并发扫描后库内 %d 条, want 1（只执行一次）", env.assetCount(t))
	}
}

// ---------- 损坏视频 ----------

// TestCorruptVideoStillIngested：探测失败（损坏/半下载）不 panic、
// 资产照常入库、元数据留空待下次变更重探。
func TestCorruptVideoStillIngested(t *testing.T) {
	probe := newProbeStub(nil, errors.New("ffprobe: moov atom not found"))
	env := newTestEnv(t, probe.call)
	env.writeFile(t, "broken.mp4", 4096)

	res, err := env.s.Scan(context.Background(), env.lib)
	if err != nil {
		t.Fatalf("损坏视频不应让扫描失败: %v", err)
	}
	if res.Added != 1 {
		t.Errorf("损坏视频 Added=%d, want 1（必须入库）", res.Added)
	}
	a := env.assetByPath(t, "broken.mp4")
	if a.MediaType != MediaTypeVideo {
		t.Errorf("media_type=%q, want video", a.MediaType)
	}
	if a.DurationMs.Valid || a.Width.Valid || a.Height.Valid {
		t.Errorf("损坏视频元数据应为空, got dur=%v w=%v h=%v", a.DurationMs, a.Width, a.Height)
	}
}

// ---------- Watch 增量监听 ----------

// startWatch 启动监听并注册"等待退出"清理（Windows 上 fsnotify 持目录句柄，
// 必须在 TempDir 清理前释放，否则 RemoveAll 失败）。
func startWatch(t *testing.T, s *Scanner, lib db.Library, debounce time.Duration) {
	t.Helper()
	s.watchDebounce = debounce
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		_ = s.Watch(ctx, lib)
	}()
	// LIFO：本 cleanup 晚于 newTestEnv 注册 → 先执行，保证句柄先释放。
	t.Cleanup(func() {
		cancel()
		select {
		case <-done:
		case <-time.After(3 * time.Second):
			t.Error("Watch 未在取消后 3s 内退出")
		}
	})
	time.Sleep(100 * time.Millisecond) // 等初始子树注册完成
}

// TestWatchDebounceCoalesces：快速连续写同一路径（模拟大文件写入风暴的
// create+write 事件串）只触发一次处理——以探测计数为证。
func TestWatchDebounceCoalesces(t *testing.T) {
	probe := newProbeStub(&thumbnail.ProbeResult{Duration: time.Second, Width: 2, Height: 2}, nil)
	env := newTestEnv(t, probe.call)
	startWatch(t, env.s, env.lib, 50*time.Millisecond)

	// 3 次快速写入（总时长 20ms << 50ms 窗口）应合并为一次处理。
	for i := 0; i < 3; i++ {
		env.writeFile(t, "v.mp4", 100*(i+1))
		time.Sleep(10 * time.Millisecond)
	}
	eventually(t, 2*time.Second, "增量入库 v.mp4", func() bool {
		return env.assetCount(t) == 1
	})
	// 再等两个防抖窗口，确认没有第二波处理。
	time.Sleep(150 * time.Millisecond)
	if got := probe.calls.Load(); got != 1 {
		t.Errorf("防抖后探测 %d 次, want 1（同路径事件风暴必须合并）", got)
	}
	a := env.assetByPath(t, "v.mp4")
	if a.SizeBytes != 300 {
		t.Errorf("入库 size=%d, want 300（最后一次写入的内容）", a.SizeBytes)
	}
}

// TestWatchDetectsNewDirectory：Watch 启动后新建的子目录动态加入监听，
// 其中落盘的新文件能被增量入库。
// 注意测试的时序前提：目录先建并等 Watch 侧注册完成，再写文件——
// "建目录+立刻写入"的窄窗口内事件会丢（fsnotify 只通知已注册目录，
// 子目录注册前的内容变更无从感知），该缺口是监听机制的固有属性，
// 生产上由轮询兜底（ARCHITECTURE §3），不由本测试覆盖。
func TestWatchDetectsNewDirectory(t *testing.T) {
	probe := newProbeStub(&thumbnail.ProbeResult{}, nil)
	env := newTestEnv(t, probe.call)
	startWatch(t, env.s, env.lib, 50*time.Millisecond)

	if err := os.Mkdir(env.abs("新目录"), 0o755); err != nil {
		t.Fatalf("mkdir 失败: %v", err)
	}
	time.Sleep(300 * time.Millisecond) // 等 Create 事件 → addTree 注册完成
	env.writeFile(t, "新目录/v.mp4", 123)
	eventually(t, 2*time.Second, "新子目录内文件增量入库", func() bool {
		return env.assetCount(t) == 1
	})
	if a := env.assetByPath(t, "新目录/v.mp4"); a.MediaType != MediaTypeVideo {
		t.Errorf("media_type=%q, want video", a.MediaType)
	}
}

// TestWatchDetectsRemoval：Watch 模式下删除文件 → 增量删除记录
// （不做移动合并——合并是全量扫描职责，见 watch.go 注释）。
func TestWatchDetectsRemoval(t *testing.T) {
	probe := newProbeStub(&thumbnail.ProbeResult{}, nil)
	env := newTestEnv(t, probe.call)
	env.writeFile(t, "a.jpg", 10)
	if _, err := env.s.Scan(context.Background(), env.lib); err != nil {
		t.Fatalf("首扫失败: %v", err)
	}
	startWatch(t, env.s, env.lib, 50*time.Millisecond)

	if err := os.Remove(env.abs("a.jpg")); err != nil {
		t.Fatalf("删除失败: %v", err)
	}
	eventually(t, 2*time.Second, "增量删除记录", func() bool {
		return env.assetCount(t) == 0
	})
}

// ---------- 轮询兜底 ----------

// TestStartBackgroundPolls：短周期轮询能独立完成入库（模拟 fsnotify
// 完全失效时 Windows 行为差异由轮询兜底）。
func TestStartBackgroundPolls(t *testing.T) {
	probe := newProbeStub(&thumbnail.ProbeResult{}, nil)
	env := newTestEnv(t, probe.call)
	env.writeFile(t, "v.mp4", 42)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() {
		defer close(done)
		_ = env.s.StartBackground(ctx, 30*time.Millisecond)
	}()

	eventually(t, 3*time.Second, "轮询扫描入库", func() bool {
		return env.assetCount(t) == 1
	})
	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Error("StartBackground 未在取消后退出")
	}
}

// ---------- 编译期接口核对（防重构悄悄断依赖） ----------

var _ ProbeFunc = thumbnail.ProbeVideo

// TestDataDirInsideLibrarySkipped 验证数据目录被配置在库内时的自噬防御：
// 缩略图缓存（webp 是媒体白名单格式）与数据库文件绝不能被扫进库。
func TestDataDirInsideLibrarySkipped(t *testing.T) {
	e := newTestEnv(t, nil)
	// 把 dataDir 指到库内子目录，模拟错误配置（正常部署两者是分离的卷）。
	dataDir := filepath.Join(e.lib.RootPath, "data")
	thumbs := filepath.Join(dataDir, "thumbs", "ab")
	if err := os.MkdirAll(thumbs, 0o755); err != nil {
		t.Fatalf("建缓存目录失败: %v", err)
	}
	// 模拟缩略图管线产物 + 数据库文件 + 正常媒体文件。
	if err := os.WriteFile(filepath.Join(thumbs, "cachekey.webp"), []byte("fake"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dataDir, "qimeng.db"), []byte("fake"), 0o644); err != nil {
		t.Fatal(err)
	}
	e.writeFile(t, "normal.jpg", 10)

	e.s.dataDir = dataDir
	res, err := e.s.Scan(context.Background(), e.lib)
	if err != nil {
		t.Fatalf("扫描失败: %v", err)
	}
	if res.Added != 1 {
		t.Fatalf("数据目录内容应被跳过：期望仅 1 个媒体入库，实际 %d", res.Added)
	}
	if _, err := e.q.GetAssetByPath(context.Background(), db.GetAssetByPathParams{
		LibraryID: e.lib.ID, RelPath: "data/thumbs/ab/cachekey.webp",
	}); err == nil {
		t.Fatal("缩略图缓存被扫进库（自噬防御失效）")
	}
}
