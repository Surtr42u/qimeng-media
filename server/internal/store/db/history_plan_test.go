package db

// history_plan_test：查询计划锁定（2026-10-02 治本批，ADR-0011「计划影响
// 横向复查」修订的常态化落点）。
//
// 背景：0013 迁移给 view_events 加 (kind, started_at) 索引时，无 ANALYZE 的
// 启发式 planner 把 /history 的每资产相关子查询静默带进坏计划（真机
// 4300 万索引步/请求、App 10s 读超时、浏览历史页空白）；0015 覆盖索引
// (asset_id, kind, started_at) 应急止血。本测试把 ListHistory（含游标
// 分支）与 facets 六查询共用的 history_subset EXISTS 探测的计划钉死在
// 覆盖索引上——未来任何影响 view_events 计划的索引增删都会在这里被 CI
// 拦截，迁移作者必须按 ADR-0011 修订复查受影响查询族后再改断言。
//
// 断言全部是确定性计划文本比对（EXPLAIN QUERY PLAN 不依赖表内数据；
// 全仓无 ANALYZE/sqlite_stat1，planner 纯启发式，同 schema 同驱动必出
// 同计划），不做任何耗时断言。
//
// 本文件必须住在 package db：被断言的 SQL 常量（listHistory 等）由 sqlc
// 生成且未导出，手改生成物被 ADR-0009 禁止；store 包导入 db，测试不能
// 反向导入 store，故迁移装配在此自含（见 openMigratedDB）。

import (
	"context"
	"database/sql"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	_ "modernc.org/sqlite" // 本测试二进制独立建库，需在此注册 "sqlite" 驱动

	"qimeng-media/server/migrations"
)

// openMigratedDB 建临时库并按文件名序应用全部 up 迁移。生产装配在
// store.newMigrator（golang-migrate）；此处平铺执行 up 文件得到相同
// schema——查询计划只取决于 schema 对象，与迁移器装配方式无关。
func openMigratedDB(t *testing.T) *sql.DB {
	t.Helper()
	conn, err := sql.Open("sqlite", filepath.Join(t.TempDir(), "plan.db"))
	if err != nil {
		t.Fatalf("打开测试库失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	entries, err := migrations.FS.ReadDir(".")
	if err != nil {
		t.Fatalf("读取 migrations 失败: %v", err)
	}
	var ups []string
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".up.sql") {
			ups = append(ups, e.Name())
		}
	}
	sort.Strings(ups)
	for _, name := range ups {
		content, err := migrations.FS.ReadFile(name)
		if err != nil {
			t.Fatalf("读取迁移 %s 失败: %v", name, err)
		}
		if _, err := conn.ExecContext(context.Background(), string(content)); err != nil {
			t.Fatalf("应用迁移 %s 失败: %v", name, err)
		}
	}
	return conn
}

// explainPlan 返回 EXPLAIN QUERY PLAN 的每行 detail 文本。
func explainPlan(t *testing.T, conn *sql.DB, query string, args ...any) []string {
	t.Helper()
	rows, err := conn.Query("EXPLAIN QUERY PLAN "+query, args...)
	if err != nil {
		t.Fatalf("EXPLAIN QUERY PLAN 失败: %v", err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var id, parent, notused int
		var detail string
		if err := rows.Scan(&id, &parent, &notused, &detail); err != nil {
			t.Fatalf("扫描计划行失败: %v", err)
		}
		out = append(out, detail)
	}
	if err := rows.Err(); err != nil {
		t.Fatalf("遍历计划失败: %v", err)
	}
	return out
}

// assertPlanShape 计划文本断言：mustHave 逐个存在（子串），mustNotHave
// 逐个不存在。失败时打印全量计划便于定位。
func assertPlanShape(t *testing.T, plan []string, mustHave, mustNotHave []string) {
	t.Helper()
	joined := strings.Join(plan, "\n")
	for _, want := range mustHave {
		if !strings.Contains(joined, want) {
			t.Fatalf("计划缺少关键形态 %q，全量计划：\n%s", want, joined)
		}
	}
	for _, banned := range mustNotHave {
		if strings.Contains(joined, banned) {
			t.Fatalf("计划出现被禁形态 %q（索引增删翻坏了查询计划，见 ADR-0011 计划影响横向复查），全量计划：\n%s", banned, joined)
		}
	}
}

// listHistoryPlanArgs 构造与生成 SQL ?1..?11 一一对应的 EXPLAIN 绑定参数
// （顺序 = ListHistoryParams 字段顺序）。
func listHistoryPlanArgs(cursorKey any, cursorID string, cursorValid bool) []any {
	return []any{
		cursorKey, // ?1 cursor_key
		sql.NullString{String: cursorID, Valid: cursorValid}, // ?2 cursor_id
		int64(1),  // ?3 include_cos（handler 缺省 = 全部分区）
		int64(0),  // ?4 cos_only
		nil,       // ?5 media_type
		nil,       // ?6 sources_json
		int64(0),  // ?7 source_is_other
		nil,       // ?8 cos_works_json
		nil,       // ?9 characters_json
		nil,       // ?10 author_id
		int64(61), // ?11 row_limit（协议默认页大小 +1）
	}
}

// TestListHistoryPlanLocked：ListHistory 首屏（无游标）与翻页（有游标）
// 两个分支的计划都必须是——latest CTE 对 view_events 覆盖索引的单趟
// 顺序扫描（0015 索引，asset_id 序免 GROUP BY 临时树）+ assets 主键探测；
// 禁止任何对 view_events 的逐行探测与 0013 陷阱索引。
func TestListHistoryPlanLocked(t *testing.T) {
	conn := openMigratedDB(t)

	mustHave := []string{
		// CTE 单趟顺序覆盖索引扫（无约束限定 = 全索引序扫描，不是逐资产探测）
		"SCAN ve USING COVERING INDEX idx_view_events_asset_kind_started",
		// latest 与 assets 按主键 1:1 连接
		"SEARCH a USING INDEX sqlite_autoindex_assets_1 (asset_id=?)",
	}
	mustNotHave := []string{
		// GROUP BY 必须由索引序满足；出现临时树 = 分组走了非 asset_id 序索引
		"USE TEMP B-TREE FOR GROUP BY",
		// 对 view_events 的任何逐行探测：旧相关子查询形态（0013 事故签名
		// "SEARCH ve USING INDEX idx_view_events_kind_started (kind=?)"）
		// 或 0015 后旧查询的同型相关探测（COVERING INDEX + 等值约束）
		"SEARCH ve USING",
		// 0013 陷阱索引（kind 前导）不得出现在计划任何位置
		"idx_view_events_kind_started",
	}

	t.Run("first_page", func(t *testing.T) {
		plan := explainPlan(t, conn, listHistory, listHistoryPlanArgs(nil, "", false)...)
		assertPlanShape(t, plan, mustHave, mustNotHave)
	})

	t.Run("cursor_page", func(t *testing.T) {
		plan := explainPlan(t, conn, listHistory,
			listHistoryPlanArgs("2026-10-01T00:00:00.000Z", "asset-x", true)...)
		assertPlanShape(t, plan, mustHave, mustNotHave)
	})
}

// TestFacetsHistorySubsetPlanLocked：facets 六查询的 history_subset EXISTS
// 探测（六处逐字节同型：veh.asset_id=? AND veh.kind='open'）在 0015 后必须
// 走覆盖索引双等值前缀（探测即停）。以 FacetPartitionCounts 为代表断言
// （六查询该子句逐字节相同，计划同型）；GET /assets/facets?history=1 可达。
func TestFacetsHistorySubsetPlanLocked(t *testing.T) {
	conn := openMigratedDB(t)
	// FacetPartitionCounts 参数序：media_type, characters_json, cos_work,
	// source, source_is_other, author_id, q_json, favorite_subset,
	// history_subset —— history_subset=1 激活 EXISTS 探测。
	plan := explainPlan(t, conn, facetPartitionCounts,
		nil, nil, nil, nil, int64(0), nil, nil,
		int64(0), int64(1))
	assertPlanShape(t, plan,
		[]string{
			"SEARCH veh USING COVERING INDEX idx_view_events_asset_kind_started (asset_id=? AND kind=?)",
		},
		[]string{"idx_view_events_kind_started"})
}
