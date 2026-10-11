package httpapi

// ADR-0037 跨库浏览事件兜底重映射测试（2026-10-11）：
//   - 未知 assetId + fileName 唯一命中 → 202 outcome=remapped：事件记入本库
//     资产、物化累加、重发（幂等键/会话去重）不双计
//   - 同名歧义（两资产同名且无 relPath）→ outcome=unmatched：事件保留原 id、
//     无物化行（既有「已删资产迟到事件」口径回归锁定）
//   - 同名歧义 + relPath 精确等值消歧 → remapped 到正确那条
//   - assetId 命中本库 → outcome=ok（带 fileName 也不重映射，不误配）
//   - 未知 assetId 且无 fileName → outcome=unmatched
//   - 重映射后会话去重按目标资产生效（去重键=重映射后的目标 id）
//
// 全部 202 出口都带 EventsViewAccepted 响应体——见 writeViewAccepted。

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"

	"github.com/google/uuid"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// seedRemapAsset 直插一条种子资产（同 TestAssetListDirectoryFilter 的
// seedSubdirAssets 手法：rel_path 含子目录段即可，重映射路径不读磁盘）。
func seedRemapAsset(t *testing.T, env *testEnv, relPath, name string) string {
	t.Helper()
	id := uuid.NewString()
	now := store.FormatTimestamp(env.clock.Now())
	if _, err := env.q.UpsertAsset(context.Background(), db.UpsertAssetParams{
		AssetID: id, LibraryID: env.libID, RelPath: relPath, FileName: name,
		MediaType: "image", SizeBytes: 100, Mtime: now, CreatedAt: now, UpdatedAt: now,
	}); err != nil {
		t.Fatalf("种子资产入库失败: %v", err)
	}
	return id
}

// postViewReport 上报一条浏览事件，校验 202 并解析响应体的 outcome。
func postViewReport(t *testing.T, env *testEnv, report gen.ViewEventReport) string {
	t.Helper()
	body, err := json.Marshal(report)
	if err != nil {
		t.Fatalf("构造浏览事件请求失败: %v", err)
	}
	resp := env.do(t, http.MethodPost, "/api/v1/events/view", string(body))
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusAccepted {
		t.Fatalf("浏览事件期望 202，得到 %d", resp.StatusCode)
	}
	var acc gen.EventsViewAccepted
	if err := json.NewDecoder(resp.Body).Decode(&acc); err != nil {
		t.Fatalf("解析 202 响应体失败: %v", err)
	}
	if acc.Outcome == nil {
		t.Fatal("202 响应体缺 outcome 字段")
	}
	return string(*acc.Outcome)
}

// TestRemapUnknownAssetIdUniqueFileName：未知 assetId + fileName 唯一命中 →
// remapped（事件+物化都落在本库资产）；重发与幂等键重传都不双计。
func TestRemapUnknownAssetIdUniqueFileName(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]
	today := store.FormatDay(env.clock.Now())
	now := env.clock.Now()
	ghost := "00000000-0000-0000-0000-0000000000b7" // 从未入库的资产（源库身份）

	// open：唯一命中 a.jpg → remapped
	report := gen.ViewEventReport{
		AssetId:       uuid.MustParse(ghost),
		Kind:          gen.Open,
		SessionId:     "sess-remap",
		StartedAt:     now,
		ClientEventId: uuid.MustParse("00000000-0000-0000-0000-0000000000f1"),
		FileName:      ptr("a.jpg"),
	}
	if got := postViewReport(t, env, report); got != "remapped" {
		t.Fatalf("唯一文件名命中期望 outcome=remapped，得到 %q", got)
	}
	if n := countViewEvents(t, env, a.id, "open"); n != 1 {
		t.Fatalf("重映射后本库资产应有 1 行 open，得到 %d", n)
	}
	if n := countViewEvents(t, env, ghost, "open"); n != 0 {
		t.Fatalf("原 assetId 不应有事件行，得到 %d", n)
	}
	if view, _, _, ok := readDailyStats(t, env, a.id, today); !ok || view != 1 {
		t.Fatalf("重映射后物化 view 应 1，得到 view=%d found=%v", view, ok)
	}

	// 同请求再发（同 clientEventId，会话去重先命中）→ 202 outcome=ok，不双计
	if got := postViewReport(t, env, report); got != "ok" {
		t.Fatalf("重发（去重命中）期望 outcome=ok，得到 %q", got)
	}
	if n := countViewEvents(t, env, a.id, "open"); n != 1 {
		t.Fatalf("重发后 open 应仍 1 行，得到 %d", n)
	}

	// dwell + 幂等键重传：走 inserted==0 幂等路径（dwell 不参与会话去重），
	// 秒数不重复累加——幂等口径不因重映射改变。
	dwell := gen.ViewEventReport{
		AssetId:       uuid.MustParse(ghost),
		Kind:          gen.Dwell,
		SessionId:     "sess-remap-dwell",
		StartedAt:     now,
		Seconds:       ptr(float64(30)),
		ClientEventId: uuid.MustParse("00000000-0000-0000-0000-0000000000f2"),
		FileName:      ptr("a.jpg"),
	}
	if got := postViewReport(t, env, dwell); got != "remapped" {
		t.Fatalf("dwell 重映射期望 outcome=remapped，得到 %q", got)
	}
	if got := postViewReport(t, env, dwell); got != "ok" {
		t.Fatalf("同 clientEventId 重传期望 outcome=ok，得到 %q", got)
	}
	if n := countViewEvents(t, env, a.id, "dwell"); n != 1 {
		t.Fatalf("dwell 重传应仍 1 行，得到 %d", n)
	}
	if _, _, secs, ok := readDailyStats(t, env, a.id, today); !ok || secs != 30 {
		t.Fatalf("dwell 秒数期望 30（不双计），得到 secs=%d found=%v", secs, ok)
	}
}

// TestRemapAmbiguousFileNameStaysUnmatched：两资产同名且未携带 relPath →
// 歧义不猜，维持「已删资产迟到事件」口径（unmatched、事件保留原 id、无物化）。
func TestRemapAmbiguousFileNameStaysUnmatched(t *testing.T) {
	env := newTestEnv(t)
	x := seedRemapAsset(t, env, "alpha/同名.jpg", "同名.jpg")
	y := seedRemapAsset(t, env, "beta/同名.jpg", "同名.jpg")
	today := store.FormatDay(env.clock.Now())
	ghost := "00000000-0000-0000-0000-0000000000c1"

	got := postViewReport(t, env, gen.ViewEventReport{
		AssetId:   uuid.MustParse(ghost),
		Kind:      gen.Open,
		SessionId: "sess-ambig",
		StartedAt: env.clock.Now(),
		FileName:  ptr("同名.jpg"),
	})
	if got != "unmatched" {
		t.Fatalf("同名歧义期望 outcome=unmatched，得到 %q", got)
	}
	if n := countViewEvents(t, env, ghost, "open"); n != 1 {
		t.Fatalf("未匹配事件应保留原 id 1 行（真相源），得到 %d", n)
	}
	for _, id := range []string{ghost, x, y} {
		if _, _, _, ok := readDailyStats(t, env, id, today); ok {
			t.Fatalf("未匹配不应产生任何物化行（%s）", id)
		}
	}
	if n := countViewEvents(t, env, x, "open"); n != 0 {
		t.Fatalf("候选资产不应收到事件行（宁少勿错），得到 %d", n)
	}
}

// TestRemapAmbiguityResolvedByRelPath：同名歧义 + relPath 精确等值消歧 →
// remapped 到正确那条；relPath 无命中 → 仍 unmatched。
func TestRemapAmbiguityResolvedByRelPath(t *testing.T) {
	env := newTestEnv(t)
	x := seedRemapAsset(t, env, "alpha/同名.jpg", "同名.jpg")
	y := seedRemapAsset(t, env, "beta/同名.jpg", "同名.jpg")
	today := store.FormatDay(env.clock.Now())
	ghost := "00000000-0000-0000-0000-0000000000c2"

	got := postViewReport(t, env, gen.ViewEventReport{
		AssetId:   uuid.MustParse(ghost),
		Kind:      gen.Open,
		SessionId: "sess-relpath",
		StartedAt: env.clock.Now(),
		FileName:  ptr("同名.jpg"),
		RelPath:   ptr("beta/同名.jpg"),
	})
	if got != "remapped" {
		t.Fatalf("relPath 消歧期望 outcome=remapped，得到 %q", got)
	}
	if n := countViewEvents(t, env, y, "open"); n != 1 {
		t.Fatalf("应重映射到 beta/同名.jpg 恰 1 行，得到 %d", n)
	}
	if n := countViewEvents(t, env, x, "open"); n != 0 {
		t.Fatalf("alpha/同名.jpg 不应收到事件行，得到 %d", n)
	}
	if view, _, _, ok := readDailyStats(t, env, y, today); !ok || view != 1 {
		t.Fatalf("消歧目标物化 view 应 1，得到 view=%d found=%v", view, ok)
	}

	// relPath 过滤后零命中 → unmatched（目标=原 id，不落任何候选）
	ghost2 := "00000000-0000-0000-0000-0000000000c3"
	got = postViewReport(t, env, gen.ViewEventReport{
		AssetId:   uuid.MustParse(ghost2),
		Kind:      gen.Open,
		SessionId: "sess-relpath-miss",
		StartedAt: env.clock.Now(),
		FileName:  ptr("同名.jpg"),
		RelPath:   ptr("gamma/同名.jpg"),
	})
	if got != "unmatched" {
		t.Fatalf("relPath 零命中期望 outcome=unmatched，得到 %q", got)
	}
	if n := countViewEvents(t, env, ghost2, "open"); n != 1 {
		t.Fatalf("relPath 零命中的事件应保留原 id，得到 %d", n)
	}
}

// TestRemapSkippedWhenAssetIdExists：assetId 命中本库 → outcome=ok——即使
// 携带的 fileName 指向另一个本库资产也绝不重映射（不误配）。
func TestRemapSkippedWhenAssetIdExists(t *testing.T) {
	env := newTestEnv(t)
	a, b := testFiles[0], testFiles[1]
	today := store.FormatDay(env.clock.Now())

	got := postViewReport(t, env, gen.ViewEventReport{
		AssetId:   uuid.MustParse(a.id),
		Kind:      gen.Open,
		SessionId: "sess-known",
		StartedAt: env.clock.Now(),
		FileName:  ptr("b.jpg"), // 与 assetId 不一致的名字：只提示身份，不触发重映射
	})
	if got != "ok" {
		t.Fatalf("assetId 命中期望 outcome=ok，得到 %q", got)
	}
	if n := countViewEvents(t, env, a.id, "open"); n != 1 {
		t.Fatalf("事件应记入 assetId 命中资产，得到 %d", n)
	}
	if n := countViewEvents(t, env, b.id, "open"); n != 0 {
		t.Fatalf("fileName 指向的 b 不应收到事件行，得到 %d", n)
	}
	if view, _, _, ok := readDailyStats(t, env, a.id, today); !ok || view != 1 {
		t.Fatalf("assetId 命中资产物化 view 应 1，得到 view=%d found=%v", view, ok)
	}
}

// TestRemapWithoutFileNameStaysUnmatched：未知 assetId 且无 fileName →
// unmatched（既有孤儿行为回归锁定，见 TestEngagementEventForDeletedAsset）。
func TestRemapWithoutFileNameStaysUnmatched(t *testing.T) {
	env := newTestEnv(t)
	today := store.FormatDay(env.clock.Now())
	ghost := "00000000-0000-0000-0000-0000000000c4"

	got := postViewReport(t, env, gen.ViewEventReport{
		AssetId:   uuid.MustParse(ghost),
		Kind:      gen.Open,
		SessionId: "sess-noname",
		StartedAt: env.clock.Now(),
	})
	if got != "unmatched" {
		t.Fatalf("无 fileName 的未知资产期望 outcome=unmatched，得到 %q", got)
	}
	if n := countViewEvents(t, env, ghost, "open"); n != 1 {
		t.Fatalf("孤儿事件应保留 1 行，得到 %d", n)
	}
	if _, _, _, ok := readDailyStats(t, env, ghost, today); ok {
		t.Fatal("未知资产的物化行不应存在")
	}
}

// TestRemapSessionDedupOnTarget：重映射后会话去重按目标资产生效——同会话
// 同 target 第二发（不同 clientEventId，排除幂等键干扰）→ outcome=ok 不双计。
func TestRemapSessionDedupOnTarget(t *testing.T) {
	env := newTestEnv(t)
	a := testFiles[0]
	today := store.FormatDay(env.clock.Now())
	now := env.clock.Now()
	ghost := "00000000-0000-0000-0000-0000000000c5"

	first := gen.ViewEventReport{
		AssetId:       uuid.MustParse(ghost),
		Kind:          gen.Open,
		SessionId:     "sess-dedup-target",
		StartedAt:     now,
		ClientEventId: uuid.MustParse("00000000-0000-0000-0000-0000000000f3"),
		FileName:      ptr("a.jpg"),
	}
	if got := postViewReport(t, env, first); got != "remapped" {
		t.Fatalf("首发布期望 outcome=remapped，得到 %q", got)
	}
	second := first
	second.ClientEventId = uuid.MustParse("00000000-0000-0000-0000-0000000000f4")
	if got := postViewReport(t, env, second); got != "ok" {
		t.Fatalf("同会话同 target 第二发期望 outcome=ok（去重命中），得到 %q", got)
	}
	if n := countViewEvents(t, env, a.id, "open"); n != 1 {
		t.Fatalf("会话去重应按目标资产生效，期望 1 行，得到 %d", n)
	}
	if view, _, _, ok := readDailyStats(t, env, a.id, today); !ok || view != 1 {
		t.Fatalf("去重后物化 view 应仍 1，得到 view=%d found=%v", view, ok)
	}
}
