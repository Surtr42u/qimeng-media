// localsync_test.go：本机同步通道的集成测试（ADR-0030）。
//
// 直接调用 runLocalSyncCycleOnce（不经 ticker），断言磁盘事实（源文件消失/
// 库内文件出现/归档移动）与 DB 行，不只看状态面板。稳定门需两轮观测相等：
// lsRunCycles 跑两轮；「稳定门槛年龄」用默认 60s + Chtimes 拨旧满足，
// 新鲜文件（mtime=现在）跨多轮都过不了年龄门（等待态可断言）。
package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/localsync"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"

	"github.com/google/uuid"
)

// lsWriteJPG 建父目录后生成真 JPEG（writeTestJPG 是既有辅助，不建目录；
// 同步用例的媒体文件都在同步根的子文件夹里）。
func lsWriteJPG(t *testing.T, path string, w, h int) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatalf("建目录失败: %v", err)
	}
	writeTestJPG(t, path, w, h)
}

// lsSetupSyncRoot 给测试环境接上同步根（独立临时目录，与 dataDir/库根互不
// 嵌套；Interval 留 0 走兜底链）。返回同步根路径。
func lsSetupSyncRoot(t *testing.T, e *testEnv) string {
	t.Helper()
	root := t.TempDir()
	e.cfg.LocalSync = config.LocalSyncConfig{Root: root}
	return root
}

// lsRunCycles 直跑 n 轮同步扫描（稳定门需两轮观测相等：常规成功用例跑 2）。
func lsRunCycles(t *testing.T, e *testEnv, n int) {
	t.Helper()
	for i := 0; i < n; i++ {
		e.s.runLocalSyncCycleOnce(context.Background())
	}
}

// lsStatus 取当前状态面板载荷（与 GET 端点同一装配函数）。
func lsStatus(e *testEnv) gen.LocalSyncStatus {
	return e.s.lsSnapshot(e.cfg.LocalSync.Root, e.s.localSyncInterval())
}

// lsItemOf 在 items/recentSynced 里找指定路径条目。
func lsItemOf(st gen.LocalSyncStatus, rel string) (gen.LocalSyncItem, bool) {
	if st.Items != nil {
		for _, it := range *st.Items {
			if it.Path != nil && *it.Path == rel {
				return it, true
			}
		}
	}
	if st.RecentSynced != nil {
		for _, it := range *st.RecentSynced {
			if it.Path != nil && *it.Path == rel {
				return it, true
			}
		}
	}
	return gen.LocalSyncItem{}, false
}

// lsItemState 取条目状态串。
func lsItemState(t *testing.T, it gen.LocalSyncItem) string {
	t.Helper()
	if it.State == nil {
		t.Fatal("条目缺 state")
	}
	return string(*it.State)
}

// lsItemError 取条目错误文案。
func lsItemError(t *testing.T, it gen.LocalSyncItem) string {
	t.Helper()
	if it.Error == nil {
		return ""
	}
	return *it.Error
}

// lsChtimesOld 把文件 mtime 拨到两小时前（远超默认 60s 稳定门槛）。
func lsChtimesOld(t *testing.T, path string) {
	t.Helper()
	old := time.Now().Add(-2 * time.Hour)
	if err := os.Chtimes(path, old, old); err != nil {
		t.Fatalf("拨旧 mtime 失败: %v", err)
	}
}

// lsCreateLibrary 在独立目录建库（测试用例的多库场景），返回库 ID。
func lsCreateLibrary(t *testing.T, e *testEnv, name, root, kind string) string {
	t.Helper()
	if err := os.MkdirAll(root, 0o755); err != nil {
		t.Fatalf("建库根失败: %v", err)
	}
	lib, err := e.q.CreateLibrary(context.Background(), db.CreateLibraryParams{
		ID: uuid.NewString(), Name: name, RootPath: root, Kind: kind,
		CreatedAt: store.FormatTimestamp(time.Now()),
	})
	if err != nil {
		t.Fatalf("建库失败: %v", err)
	}
	return lib.ID
}

// lsRequireFile 断言文件存在/不存在。
func lsRequireFile(t *testing.T, path string, wantExists bool) {
	t.Helper()
	_, err := os.Stat(path)
	if wantExists && err != nil {
		t.Fatalf("文件应存在: %s（%v）", path, err)
	}
	if !wantExists && err == nil {
		t.Fatalf("文件应已消失: %s", path)
	}
}

// TestLocalSyncMediaSuccess 媒体成功：源消失、库根出现、DB 行存在、
// recentSynced 记录、items 清空。
func TestLocalSyncMediaSuccess(t *testing.T) {
	e := newTestEnvRaw(t)
	root := lsSetupSyncRoot(t, e)
	src := filepath.Join(root, "测试库", "sync.jpg")
	lsWriteJPG(t, src, 320, 240)
	lsChtimesOld(t, src)

	lsRunCycles(t, e, 2)

	lsRequireFile(t, src, false)                               // 源文件已 move 入库
	lsRequireFile(t, filepath.Join(e.media, "sync.jpg"), true) // 库根出现
	asset, err := e.q.GetAssetByPath(context.Background(), db.GetAssetByPathParams{LibraryID: e.libID, RelPath: "sync.jpg"})
	if err != nil {
		t.Fatalf("资产行应存在: %v", err)
	}
	if asset.MediaType != "image" || asset.FileName != "sync.jpg" {
		t.Fatalf("资产行字段错误: %+v", asset)
	}
	st := lsStatus(e)
	it, ok := lsItemOf(st, "测试库/sync.jpg")
	if !ok {
		t.Fatal("recentSynced 应含成功条目")
	}
	if got := lsItemState(t, it); got != localSyncStateSynced {
		t.Fatalf("条目状态 = %q，期望 synced", got)
	}
	if st.Items != nil && len(*st.Items) != 0 {
		t.Fatalf("items 应为空，得到 %d 条", len(*st.Items))
	}
	if st.SyncedTotal == nil || *st.SyncedTotal != 1 {
		t.Fatal("syncedTotal 应为 1")
	}
}

// TestLocalSyncSanitizeAlignmentE2E 净化对齐锚定：库名 a:b（含非法字符）→
// 文件夹 a_b 命中并成功入库（与 Android 归档 sanitizeLibraryDirName 对齐）。
func TestLocalSyncSanitizeAlignmentE2E(t *testing.T) {
	e := newTestEnvRaw(t)
	root := lsSetupSyncRoot(t, e)
	libRoot := filepath.Join(t.TempDir(), "libab")
	libID := lsCreateLibrary(t, e, "a:b", libRoot, "normal")
	src := filepath.Join(root, "a_b", "x.jpg")
	lsWriteJPG(t, src, 320, 240)
	lsChtimesOld(t, src)

	lsRunCycles(t, e, 2)

	lsRequireFile(t, src, false)
	lsRequireFile(t, filepath.Join(libRoot, "x.jpg"), true)
	if _, err := e.q.GetAssetByPath(context.Background(), db.GetAssetByPathParams{LibraryID: libID, RelPath: "x.jpg"}); err != nil {
		t.Fatalf("净化命中库的资产行应存在: %v", err)
	}
}

// TestLocalSyncTxtSuccess TXT 成功：BOM 剥离导入、kv 片段表记录、归档移动。
func TestLocalSyncTxtSuccess(t *testing.T) {
	e := newTestEnvRaw(t)
	root := lsSetupSyncRoot(t, e)
	content := []byte{0xEF, 0xBB, 0xBF}
	content = append(content, []byte("1  作者甲\n")...)
	src := filepath.Join(root, "作者清单.txt")
	if err := os.WriteFile(src, content, 0o644); err != nil {
		t.Fatalf("写 TXT 失败: %v", err)
	}
	lsChtimesOld(t, src)

	lsRunCycles(t, e, 2)

	lsRequireFile(t, src, false) // 源文件已归档
	lsRequireFile(t, filepath.Join(root, ".synced", "作者清单.txt"), true)
	sources, err := authorattach.LoadSources(context.Background(), e.q)
	if err != nil {
		t.Fatalf("读片段表失败: %v", err)
	}
	found := false
	for _, s := range sources {
		if s.Filename == "作者清单.txt" {
			found = true
		}
	}
	if !found {
		t.Fatalf("imported_txt_sources 应含 作者清单.txt：%+v", sources)
	}
	st := lsStatus(e)
	if it, ok := lsItemOf(st, "作者清单.txt"); !ok || lsItemState(t, it) != localSyncStateSynced {
		t.Fatal("recentSynced 应含 TXT 成功条目")
	}
}

// TestLocalSyncLibraryNotFound 未命中库：文件原地、failed 含「未找到」、
// attempts>=1。
func TestLocalSyncLibraryNotFound(t *testing.T) {
	e := newTestEnvRaw(t)
	root := lsSetupSyncRoot(t, e)
	src := filepath.Join(root, "不存在库", "y.jpg")
	lsWriteJPG(t, src, 320, 240)
	lsChtimesOld(t, src)

	lsRunCycles(t, e, 2)

	lsRequireFile(t, src, true) // 失败原地保留
	st := lsStatus(e)
	it, ok := lsItemOf(st, "不存在库/y.jpg")
	if !ok {
		t.Fatal("items 应含失败条目")
	}
	if got := lsItemState(t, it); got != localSyncStateFailed {
		t.Fatalf("状态 = %q，期望 failed", got)
	}
	if msg := lsItemError(t, it); !strings.Contains(msg, "未找到") {
		t.Fatalf("错误文案应含「未找到」：%q", msg)
	}
	if it.Attempts == nil || *it.Attempts < 1 {
		t.Fatal("attempts 应 >= 1")
	}
}

// TestLocalSyncNonMediaIgnored 非媒体扩展名：ignored 且文件原地。
func TestLocalSyncNonMediaIgnored(t *testing.T) {
	e := newTestEnvRaw(t)
	root := lsSetupSyncRoot(t, e)
	src := filepath.Join(root, "note.pdf")
	if err := os.WriteFile(src, []byte("pdf"), 0o644); err != nil {
		t.Fatalf("写文件失败: %v", err)
	}

	lsRunCycles(t, e, 2)

	lsRequireFile(t, src, true)
	st := lsStatus(e)
	it, ok := lsItemOf(st, "note.pdf")
	if !ok || lsItemState(t, it) != localSyncStateIgnored {
		t.Fatal("note.pdf 应为 ignored")
	}
	if msg := lsItemError(t, it); !strings.Contains(msg, "非媒体扩展名") {
		t.Fatalf("忽略原因错误：%q", msg)
	}
}

// TestLocalSyncRootLevelMediaIgnored 根级媒体：ignored「需放入以库名命名」。
func TestLocalSyncRootLevelMediaIgnored(t *testing.T) {
	e := newTestEnvRaw(t)
	root := lsSetupSyncRoot(t, e)
	src := filepath.Join(root, "loose.mp4")
	if err := os.WriteFile(src, bytesRepeat(64), 0o644); err != nil {
		t.Fatalf("写文件失败: %v", err)
	}

	lsRunCycles(t, e, 2)

	lsRequireFile(t, src, true)
	st := lsStatus(e)
	it, ok := lsItemOf(st, "loose.mp4")
	if !ok || lsItemState(t, it) != localSyncStateIgnored {
		t.Fatal("根级媒体应为 ignored")
	}
	if msg := lsItemError(t, it); !strings.Contains(msg, "需放入以库名命名的子文件夹") {
		t.Fatalf("忽略原因错误：%q", msg)
	}
}

// TestLocalSyncStableGate 稳定门：新鲜文件跨轮等待（年龄不足），拨旧后
// 需再经两轮观测相等才入库。
func TestLocalSyncStableGate(t *testing.T) {
	e := newTestEnvRaw(t)
	root := lsSetupSyncRoot(t, e)
	src := filepath.Join(root, "测试库", "fresh.jpg")
	lsWriteJPG(t, src, 320, 240)

	// 第 1 轮：首次观测 → waiting。
	lsRunCycles(t, e, 1)
	lsRequireFile(t, src, true)
	st := lsStatus(e)
	if it, ok := lsItemOf(st, "测试库/fresh.jpg"); !ok || lsItemState(t, it) != localSyncStateWaitingStable {
		t.Fatal("首轮应 waiting-stable")
	}
	// 第 2 轮：观测相等但 mtime 年龄 < 60s → 仍 waiting（年龄门）。
	lsRunCycles(t, e, 1)
	lsRequireFile(t, src, true)
	st = lsStatus(e)
	if it, ok := lsItemOf(st, "测试库/fresh.jpg"); !ok || lsItemState(t, it) != localSyncStateWaitingStable {
		t.Fatal("年龄不足应保持 waiting-stable")
	}
	// 拨旧后：第 3 轮观测到新 mtime（与上轮不等）→ waiting；第 4 轮相等 → 入库。
	lsChtimesOld(t, src)
	lsRunCycles(t, e, 1)
	lsRequireFile(t, src, true)
	lsRunCycles(t, e, 1)
	lsRequireFile(t, src, false)
	lsRequireFile(t, filepath.Join(e.media, "fresh.jpg"), true)
}

// TestLocalSyncRootOverlap 根重叠：通道级错误、文件不动、enabled=false。
func TestLocalSyncRootOverlap(t *testing.T) {
	e := newTestEnvRaw(t)
	e.cfg.LocalSync = config.LocalSyncConfig{Root: e.media} // 指到「测试库」库根（相等即重叠）

	lsRunCycles(t, e, 1)

	lsRequireFile(t, filepath.Join(e.media, "a.jpg"), true) // 未动任何文件
	st := lsStatus(e)
	if st.LastError == nil || *st.LastError == "" {
		t.Fatal("通道级 lastError 应非空")
	}
	if st.Enabled == nil || *st.Enabled {
		t.Fatal("重叠时 enabled 应为 false")
	}
}

// TestLocalSyncEndpoints 端点：trigger 202 / 未配 409 LOCAL_SYNC_DISABLED /
// status 字段齐全。
func TestLocalSyncEndpoints(t *testing.T) {
	e := newTestEnv(t) // 走 setup 拿 token
	root := lsSetupSyncRoot(t, e)

	resp := e.do(t, http.MethodPost, "/api/v1/local-sync/trigger", "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusAccepted {
		t.Fatalf("trigger 期望 202，得到 %d", resp.StatusCode)
	}

	resp = e.do(t, http.MethodGet, "/api/v1/local-sync/status", "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("status 期望 200，得到 %d", resp.StatusCode)
	}
	raw, _ := io.ReadAll(resp.Body)
	var st gen.LocalSyncStatus
	if err := json.Unmarshal(raw, &st); err != nil {
		t.Fatalf("解析 status 失败: %v", err)
	}
	if st.Enabled == nil || !*st.Enabled {
		t.Fatal("已配置同步根且通道健康 → enabled=true")
	}
	if st.Root == nil || *st.Root != root {
		t.Fatalf("root 回显错误：%v", st.Root)
	}
	if st.IntervalSeconds == nil || *st.IntervalSeconds != int(config.DefaultLocalSyncInterval/time.Second) {
		t.Fatalf("intervalSeconds 应回落默认 30：%v", st.IntervalSeconds)
	}
	if st.Counts == nil || st.Counts.Failed == nil || st.Counts.Ignored == nil || st.Counts.WaitingStable == nil {
		t.Fatal("counts 字段应齐全")
	}
	if st.Items == nil || st.RecentSynced == nil || st.Paused == nil || st.SyncedTotal == nil {
		t.Fatal("items/recentSynced/paused/syncedTotal 字段应齐全")
	}
}

// TestLocalSyncTriggerDisabled 未配同步根：trigger 409 LOCAL_SYNC_DISABLED，
// status enabled=false。
func TestLocalSyncTriggerDisabled(t *testing.T) {
	e := newTestEnv(t) // cfg.LocalSync 零值（Root 空）

	resp := e.do(t, http.MethodPost, "/api/v1/local-sync/trigger", "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("未配置时 trigger 期望 409，得到 %d", resp.StatusCode)
	}
	var errBody map[string]string
	raw, _ := io.ReadAll(resp.Body)
	if err := json.Unmarshal(raw, &errBody); err != nil {
		t.Fatalf("解析错误响应失败: %v", err)
	}
	if errBody["code"] != "LOCAL_SYNC_DISABLED" {
		t.Fatalf("错误码应为 LOCAL_SYNC_DISABLED：%v", errBody)
	}

	resp = e.do(t, http.MethodGet, "/api/v1/local-sync/status", "")
	defer func() { _ = resp.Body.Close() }()
	var st gen.LocalSyncStatus
	raw, _ = io.ReadAll(resp.Body)
	if err := json.Unmarshal(raw, &st); err != nil {
		t.Fatalf("解析 status 失败: %v", err)
	}
	if st.Enabled == nil || *st.Enabled {
		t.Fatal("未配置时 enabled=false")
	}
	if st.Root == nil || *st.Root != "" {
		t.Fatal("未配置时 root 应为空串")
	}
}

// TestLocalSyncCosKindReject COS 库文件夹：failed「COS 库」。
func TestLocalSyncCosKindReject(t *testing.T) {
	e := newTestEnvRaw(t)
	root := lsSetupSyncRoot(t, e)
	lsCreateLibrary(t, e, "coslib", filepath.Join(t.TempDir(), "cosroot"), "cos")
	src := filepath.Join(root, "coslib", "z.jpg")
	lsWriteJPG(t, src, 320, 240)
	lsChtimesOld(t, src)

	lsRunCycles(t, e, 2)

	lsRequireFile(t, src, true) // COS 拒绝，原地保留
	st := lsStatus(e)
	it, ok := lsItemOf(st, "coslib/z.jpg")
	if !ok || lsItemState(t, it) != localSyncStateFailed {
		t.Fatal("COS 条目应为 failed")
	}
	if msg := lsItemError(t, it); !strings.Contains(msg, "COS 库") {
		t.Fatalf("错误文案应含「COS 库」：%q", msg)
	}
	if it.LibraryName == nil || *it.LibraryName != "coslib" {
		t.Fatal("拒绝条目应携带命中库名")
	}
}

// TestLocalSyncPausedKeepsMediaWaiting autoAccept=false：媒体保持 waiting
// 不失败、文件原地，TXT 仍成功，paused=true。
func TestLocalSyncPausedKeepsMediaWaiting(t *testing.T) {
	e := newTestEnvRaw(t)
	root := lsSetupSyncRoot(t, e)
	if err := e.q.UpsertSetting(context.Background(), db.UpsertSettingParams{
		Key:       authoring.SettingKeyClientConfig,
		Value:     `{"upload":{"autoAccept":false}}`,
		UpdatedAt: store.FormatTimestamp(time.Now()),
	}); err != nil {
		t.Fatalf("写 client_config 失败: %v", err)
	}
	mediaSrc := filepath.Join(root, "测试库", "p.jpg")
	lsWriteJPG(t, mediaSrc, 320, 240)
	lsChtimesOld(t, mediaSrc)
	txtSrc := filepath.Join(root, "作者.txt")
	if err := os.WriteFile(txtSrc, []byte("1  作者乙\n"), 0o644); err != nil {
		t.Fatalf("写 TXT 失败: %v", err)
	}
	lsChtimesOld(t, txtSrc)

	lsRunCycles(t, e, 2)

	lsRequireFile(t, mediaSrc, true) // 媒体原地等待
	lsRequireFile(t, txtSrc, false)  // TXT 不受闸约束，已归档
	st := lsStatus(e)
	if st.Paused == nil || !*st.Paused {
		t.Fatal("paused 应为 true")
	}
	if it, ok := lsItemOf(st, "测试库/p.jpg"); !ok || lsItemState(t, it) != localSyncStateWaitingStable {
		t.Fatal("媒体应保持 waiting-stable（不判失败）")
	}
	if it, ok := lsItemOf(st, "作者.txt"); !ok || lsItemState(t, it) != localSyncStateSynced {
		t.Fatal("TXT 应已成功同步")
	}
}

// bytesRepeat 生成 n 字节占位内容。
func bytesRepeat(n int) []byte {
	return bytes.Repeat([]byte{'x'}, n)
}

// TestLocalSyncMediaPreCheckSkip 落位前预复核：直接调用 processLocalSyncMedia
// 注入伪造 Entry（Size 与磁盘实际不符、首段命中既有库文件夹）→ 不搬动，
// 源文件原地保留、未入库、条目 failed（源还在同步根，下轮重新观测）。
func TestLocalSyncMediaPreCheckSkip(t *testing.T) {
	e := newTestEnvRaw(t)
	root := lsSetupSyncRoot(t, e)
	src := filepath.Join(root, "测试库", "skip.jpg")
	lsWriteJPG(t, src, 320, 240)
	info, err := os.Stat(src)
	if err != nil {
		t.Fatalf("stat 源文件失败: %v", err)
	}
	libs, err := e.q.ListLibraries(context.Background())
	if err != nil {
		t.Fatalf("查库列表失败: %v", err)
	}
	var lib db.Library
	for _, l := range libs {
		if l.Name == "测试库" {
			lib = l
		}
	}
	if lib.ID == "" {
		t.Fatal("默认测试库「测试库」应存在")
	}
	// Entry.Size 与磁盘实际不符（差 123 字节，仍在上传上限内过四道校验，
	// 让流程走到落位前预复核）；ModTime 任意（预复核只看 size）。
	entry := localsync.Entry{RelPath: "测试库/skip.jpg", Size: info.Size() + 123, ModTime: info.ModTime()}
	e.s.processLocalSyncMedia(context.Background(), entry, lib, "测试库", e.s.resolveUploadPolicy(context.Background()))

	lsRequireFile(t, src, true) // 预复核不符：未搬动，源原地
	lsRequireFile(t, filepath.Join(e.media, "skip.jpg"), false)
	if _, aerr := e.q.GetAssetByPath(context.Background(), db.GetAssetByPathParams{LibraryID: lib.ID, RelPath: "skip.jpg"}); aerr == nil {
		t.Fatal("预复核跳过后不应有资产行")
	}
	st := lsStatus(e)
	it, ok := lsItemOf(st, "测试库/skip.jpg")
	if !ok || lsItemState(t, it) != localSyncStateFailed {
		t.Fatal("条目应为 failed")
	}
	if msg := lsItemError(t, it); !strings.Contains(msg, "文件在处理间隙发生变化") {
		t.Fatalf("错误文案应含「文件在处理间隙发生变化」：%q", msg)
	}
	if it.Attempts == nil || *it.Attempts < 1 {
		t.Fatal("attempts 应 >= 1（折进 lsMarkFailed 的一次完整尝试）")
	}
}

// TestLsReconcilePlacedSize 落位后 size 复核处置（函数级）：一致放行 /
// 不符移回源 / 移回失败保留库位三分支，任何分支绝不删除落位文件。
func TestLsReconcilePlacedSize(t *testing.T) {
	e := newTestEnvRaw(t)
	dir := t.TempDir()
	mkTen := func(p string) {
		t.Helper()
		if err := os.WriteFile(p, []byte("0123456789"), 0o644); err != nil {
			t.Fatalf("写 10 字节文件失败: %v", err)
		}
	}

	// 分支一：实际 10 字节 = 声明 → 放行（proceed），文件原地。
	src := filepath.Join(dir, "ok-src.bin")
	target := filepath.Join(dir, "ok-target.bin")
	mkTen(target)
	disp, reason := e.s.lsReconcilePlacedSize(src, target, 10)
	if disp != lsPlaceProceed || reason != nil {
		t.Fatalf("size 一致应 proceed（reason=%v），得到 %v", reason, disp)
	}
	lsRequireFile(t, target, true)

	// 分支二：不符且移回可行——A(10 字节) 手动挪到 B 后按 expect=999 复核，
	// 应移回 A、B 消失（B 未被删除，是被移回）。
	src = filepath.Join(dir, "back-src.bin")
	target = filepath.Join(dir, "back-target.bin")
	mkTen(src)
	if err := os.Rename(src, target); err != nil {
		t.Fatalf("模拟搬移 A→B 失败: %v", err)
	}
	disp, reason = e.s.lsReconcilePlacedSize(src, target, 999)
	if disp != lsPlaceMovedBack {
		t.Fatalf("移回可行应 movedBack（reason=%v），得到 %v", reason, disp)
	}
	lsRequireFile(t, src, true)
	lsRequireFile(t, target, false)

	// 分支三：不符且移回失败（源路径被目录占用，rename 必败）——保留库位，
	// 绝不删除（库位件是全项目唯一副本）。
	src = filepath.Join(dir, "stuck-src.bin")
	target = filepath.Join(dir, "stuck-target.bin")
	if err := os.Mkdir(src, 0o755); err != nil {
		t.Fatalf("建占用目录失败: %v", err)
	}
	mkTen(target)
	disp, reason = e.s.lsReconcilePlacedSize(src, target, 999)
	if disp != lsPlaceKeptInLibrary {
		t.Fatalf("移回失败应 keptInLibrary（reason=%v），得到 %v", reason, disp)
	}
	lsRequireFile(t, target, true)
	lsRequireFile(t, src, true) // 占用目录未被动过
}

// TestLocalSyncTriggerSecurityCheckFailed 安全校验未通过（同步根与库根重叠
// → 通道级 lastErr 非空）：trigger 409 LOCAL_SYNC_DISABLED 且透传 lastErr
// （与 openapi 409 描述「未配置或安全校验未通过」对齐）。
func TestLocalSyncTriggerSecurityCheckFailed(t *testing.T) {
	e := newTestEnv(t)                                      // 走 setup 拿 token
	e.cfg.LocalSync = config.LocalSyncConfig{Root: e.media} // 指到库根（相等即重叠）
	e.s.runLocalSyncCycleOnce(context.Background())         // 置通道级 lastErr

	resp := e.do(t, http.MethodPost, "/api/v1/local-sync/trigger", "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("安全校验未通过时 trigger 期望 409，得到 %d", resp.StatusCode)
	}
	raw, _ := io.ReadAll(resp.Body)
	var errBody map[string]string
	if err := json.Unmarshal(raw, &errBody); err != nil {
		t.Fatalf("解析错误响应失败: %v", err)
	}
	if errBody["code"] != "LOCAL_SYNC_DISABLED" {
		t.Fatalf("错误码应为 LOCAL_SYNC_DISABLED：%v", errBody)
	}
	if msg := errBody["message"]; !strings.Contains(msg, "重叠") {
		t.Fatalf("409 应透传通道级 lastErr（含「重叠」）：%q", msg)
	}
}
