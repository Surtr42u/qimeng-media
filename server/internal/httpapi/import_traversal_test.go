// import_traversal_test.go：数据版本穿越回放测试（2026-10-02 手搓治理批新增，
// CI 门禁族之三）。锁定「同一备份载荷跨服务端代际重放，事件计数守恒不叠加」：
//
//	① 旧代库态 → 新代码同批次重导：旧代 = 内容键诞生前（2026-09-18 内容键
//	   幂等落地前）的导入产物——事件无 client_event_id、批次锚（kv_settings
//	   legacy_import_batch）已写。同批次重导必须走快速路径整体跳过（批次锚是
//	   唯一的跨代际保护层），事件计数守恒。
//	② 新代码内容键路径三代链（T1→T2→T3 同载荷）：每代零新增（内容键唯一索引
//	   拦重），计数守恒、dwell 秒数取首写值——「不三代叠加」。
//
// 旧代库态为确定性复刻：时间戳按 §10 既有确定性（正午偏移 replayNoonOffset +
// 逐秒错开 replayStagger）、session = legacyImportSession 常量、client_event_id
// 恒 NULL（0010 列可空，旧代码不经 InsertViewEventIdempotent 写键）。
//
// 已知边界（记档不拦截，超出本门禁「锁定现状」范围）：旧代无键行无法被内容键
// 唯一索引拦重——若旧代导入后**换新批次**（不同 exportedAtMillis）重导同内容，
// 新代码会把同逻辑事件再写一遍（§10「同一来源换新批次只增量补写新事件」隐含
// 前代已走内容键路径；认领无键 legacy 行需要语义扩展，先改 DOMAIN_RULES §10
// 再动代码）。同批次场景被 ① 的批次锚兜住，真实 M6 迁移链（2026-09-20）在
// 内容键落地之后执行，不受此边界影响。
package httpapi

import (
	"net/http"
	"strconv"
	"testing"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// traversalBatchSetting 读批次锚当前值（缺行返回空串）。
func traversalBatchSetting(t *testing.T, e *testEnv) string {
	t.Helper()
	v, err := e.q.GetSetting(t.Context(), settingKeyLegacyImportBatch)
	if err != nil {
		t.Fatalf("读批次锚失败: %v", err)
	}
	return v
}

// seedTraversalAsset 造一个供穿越用例使用的资产。
func seedTraversalAsset(t *testing.T, e *testEnv) string {
	t.Helper()
	id := "01900000-0000-7000-8000-00000000ab01"
	now := e.clock.Now().Format(time.RFC3339Nano)
	if _, err := e.q.UpsertAsset(t.Context(), db.UpsertAssetParams{
		AssetID: id, LibraryID: e.libID,
		RelPath: "a.jpg", FileName: "a.jpg",
		MediaType: "image", SizeBytes: 10, Mtime: now, CreatedAt: now, UpdatedAt: now,
	}); err != nil {
		t.Fatalf("造资产失败: %v", err)
	}
	return id
}

// insertLegacyEvent 直插一条旧代形态事件（无内容键、legacy 会话、§10 确定性
// 时间戳）——旧代码不经 InsertViewEventIdempotent 写键，open/play 的 seconds
// 与现行导入同口径为 NULL（仅 dwell 带秒）。
func insertLegacyEvent(t *testing.T, e *testEnv, assetID, kind string, at time.Time, seconds int64) {
	t.Helper()
	var secs any
	if seconds > 0 {
		secs = seconds
	}
	if _, err := e.conn.Exec(
		`INSERT INTO view_events (asset_id, kind, session_id, started_at, seconds, client_event_id)
		 VALUES (?, ?, ?, ?, ?, NULL)`,
		assetID, kind, legacyImportSession, store.FormatTimestamp(at), secs,
	); err != nil {
		t.Fatalf("直插旧代事件失败: %v", err)
	}
}

// traversalPayload 构造穿越用例的标准载荷：日明细 open 2 + dwell 20s，
// 统计累计 view 3 / 秒 50（缺口 = open 1 + dwell 30s）——全量回放 = 5 条事件
// （open 3、dwell 2），停留秒合计 50（dwell 秒数取首写值）。
func traversalPayload(batch int64) gen.LegacyBackupImport {
	return gen.LegacyBackupImport{
		Format: "qimeng_backup", SchemaVersion: 1, AppIdentifier: "com.qimeng.media",
		ExportedAtMillis: ptr(batch),
		Data: gen.LegacyBackupData{
			MediaFiles: &[]gen.LegacyMediaFile{
				{RecordKey: "a.jpg", FileName: "a.jpg", MediaType: "image", SizeBytes: 100, ModifiedAtMillis: 1},
			},
			DailyBrowse: &[]gen.LegacyDailyBrowse{
				{RecordKey: "a.jpg", FileName: "a.jpg", MediaType: "image", DayStartMillis: 1756377600000, ViewCount: ptr(2), PlayCount: ptr(0), TotalBrowseSeconds: ptr(int64(20))},
			},
			MediaStats: &[]gen.LegacyMediaStats{
				{RecordKey: "a.jpg", FileName: "a.jpg", ViewCount: ptr(3), PlayCount: ptr(0), TotalBrowseSeconds: ptr(int64(50)), LastOpenedAtMillis: ptr(int64(1756460000000))},
			},
		},
	}
}

// TestImport_versionTraversalLegacyStateSameBatchConserved：旧代库态（无内容
// 键 + 批次锚已写）下，新代码同批次重导必须整体跳过、计数守恒——批次锚是
// 无键行面前的唯一跨代际保护层。
func TestImport_versionTraversalLegacyStateSameBatchConserved(t *testing.T) {
	e := newTestEnv(t)
	assetID := seedTraversalAsset(t, e)

	// —— 复刻旧代库态：与载荷逐事件同源的时间表（§10 确定性），无内容键 ——
	dayBase := time.UnixMilli(1756377600000).Add(replayNoonOffset)
	gapBase := time.UnixMilli(1756460000000)
	insertLegacyEvent(t, e, assetID, string(gen.Open), dayBase, 0)
	insertLegacyEvent(t, e, assetID, string(gen.Open), dayBase.Add(replayStagger), 0)
	insertLegacyEvent(t, e, assetID, string(gen.Dwell), dayBase, 20)
	insertLegacyEvent(t, e, assetID, string(gen.Open), gapBase, 0)
	insertLegacyEvent(t, e, assetID, string(gen.Dwell), gapBase, 30)
	// 旧代码同样写批次锚（M3 起即有，e83b99e「事件回放+批次幂等」）
	if err := e.q.UpsertSetting(t.Context(), db.UpsertSettingParams{
		Key: settingKeyLegacyImportBatch, Value: "1000",
		UpdatedAt: store.FormatTimestamp(e.s.now()),
	}); err != nil {
		t.Fatalf("写旧代批次锚失败: %v", err)
	}
	// 基线：open 3 + dwell 2，停留秒 50
	if got := countEvents(t, e, ""); got != 5 {
		t.Fatalf("旧代基线事件 = %d, want 5", got)
	}

	// 同批次重导：快速路径整体跳过，事件零回放零新增
	code, res := importBackup(t, e, traversalPayload(1000))
	if code != http.StatusOK {
		t.Fatalf("同批次重导应 200，got %d", code)
	}
	if got := derefVal(res.EventsReplayed); got != 0 {
		t.Errorf("同批次穿越重导 EventsReplayed = %d, want 0（批次锚快速路径）", got)
	}
	if got := countEvents(t, e, ""); got != 5 {
		t.Errorf("穿越重导后事件数 = %d, want 5（守恒不叠加）", got)
	}
	if got := countEvents(t, e, "open"); got != 3 {
		t.Errorf("open = %d, want 3", got)
	}
	if got := sumDwellSeconds(t, e); got != 50 {
		t.Errorf("停留秒 = %d, want 50", got)
	}
	if got := traversalBatchSetting(t, e); got != "1000" {
		t.Errorf("批次锚 = %q, want 1000（快速路径不改写锚）", got)
	}
	// 无键行原样保留（旧代行不被新代码补键/改写）
	var keyless int
	if err := e.conn.QueryRow(`SELECT COUNT(*) FROM view_events WHERE client_event_id IS NULL`).Scan(&keyless); err != nil || keyless != 5 {
		t.Errorf("无键行 = %d err=%v, want 5（旧代行不被触碰）", keyless, err)
	}
}

// TestImport_versionTraversalContentKeyThreeGenerations：新代码内容键路径下
// 同一载荷三代导入（T1→T2→T3），每代零新增——内容键唯一索引拦重，计数守恒、
// dwell 秒数取首写值（「不三代叠加」）。
func TestImport_versionTraversalContentKeyThreeGenerations(t *testing.T) {
	e := newTestEnv(t)
	seedTraversalAsset(t, e)

	code, res := importBackup(t, e, traversalPayload(1000))
	if code != http.StatusOK {
		t.Fatalf("首代导入应 200，got %d", code)
	}
	if got := derefVal(res.EventsReplayed); got != 5 {
		t.Fatalf("首代 EventsReplayed = %d, want 5", got)
	}

	for _, batch := range []int64{2000, 3000} {
		code, res := importBackup(t, e, traversalPayload(batch))
		if code != http.StatusOK {
			t.Fatalf("批次 %d 导入应 200，got %d", batch, code)
		}
		if got := derefVal(res.EventsReplayed); got != 0 {
			t.Errorf("批次 %d EventsReplayed = %d, want 0（内容键拦重）", batch, got)
		}
		if got := countEvents(t, e, ""); got != 5 {
			t.Errorf("批次 %d 后事件数 = %d, want 5（三代不叠加）", batch, got)
		}
		if got := sumDwellSeconds(t, e); got != 50 {
			t.Errorf("批次 %d 后停留秒 = %d, want 50（dwell 秒数取首写值）", batch, got)
		}
		if got := traversalBatchSetting(t, e); got != strconv.FormatInt(batch, 10) {
			t.Errorf("批次锚 = %q, want %d（新代锚随成功导入前移）", got, batch)
		}
	}

	// 全部事件带 legacy: 内容键（内容键路径的覆盖不变量）
	var total, withKey int
	if err := e.conn.QueryRow(`SELECT COUNT(*) FROM view_events`).Scan(&total); err != nil {
		t.Fatalf("统计事件失败: %v", err)
	}
	if err := e.conn.QueryRow(`SELECT COUNT(*) FROM view_events WHERE client_event_id LIKE 'legacy:%'`).Scan(&withKey); err != nil {
		t.Fatalf("统计内容键失败: %v", err)
	}
	if total != 5 || withKey != total {
		t.Errorf("内容键覆盖 = %d/%d, want 5/5", withKey, total)
	}
}
