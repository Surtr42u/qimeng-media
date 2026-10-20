package db

import (
	"context"
	"strings"
	"testing"
)

func TestRankingPlans(t *testing.T) {
	conn := openMigratedDB(t)
	plan := explainPlan(t, conn, listRankingPageSummaries, `["x003","x001"]`)
	assertPlanShape(t, plan, []string{"SEARCH v USING", "asset_id=?", "kind=?"}, []string{"SCAN v"})
	windowPlan := explainPlan(t, conn, listRankingWindowInput, "2026-01-01")
	if strings.Contains(strings.Join(windowPlan, "\n"), "view_events") || strings.Contains(listRankingWindowInput, "view_events") {
		t.Fatal("窗口热度不得先读全量原始事件", windowPlan)
	}
	t.Logf("page plan: %v; window plan: %v", plan, windowPlan)
}

func TestRankingSummaryUsesNumericInputOrder(t *testing.T) {
	q := New(seedFileDateEquivalence(t))
	// 12 项包含 JSON 索引 10/11，避免 ORDER BY 文本 key 的隐蔽错序。
	ids := []string{"x036", "x003", "x006", "x009", "x012", "x018", "x021", "x024", "x027", "x033", "x039", "x042"}
	payload := `["` + strings.Join(ids, `","`) + `"]`
	rows, err := q.ListRankingPageSummaries(context.Background(), payload)
	if err != nil || len(rows) != len(ids) {
		t.Fatalf("rows=%+v err=%v", rows, err)
	}
	for i, r := range rows {
		if r.AssetID != ids[i] {
			t.Fatalf("position %d got=%s want=%s", i, r.AssetID, ids[i])
		}
	}
}
