package store

import (
	"context"
	"database/sql"
	"testing"
	"time"

	"qimeng-media/server/internal/store/db"
)

// TestListHistorySemantics：ListHistory 改写（2026-10-02 治本批，相关标量
// 子查询 → latest CTE 预聚合 JOIN）的语义等价锁定。DOMAIN_RULES §8 口径
// 逐条断言：每资产一条 = 最近一次 kind='open' 的 started_at；play/dwell
// 不计入；已删资产的事件（事件流无 FK，adr/0005）不出现；排序 =
// (last_viewed_at DESC, asset_id DESC) 同毫秒按资产 ID 决胜；keyset 游标
// 严格续读（严格小于 / 同时间戳资产 ID 严格小于）。
func TestListHistorySemantics(t *testing.T) {
	_, q := openTestDB(t)
	ctx := context.Background()
	libID := newLibrary(t, q)

	base := time.Date(2026, 9, 30, 12, 0, 0, 0, time.UTC)
	t1 := FormatTimestamp(base)
	t2 := FormatTimestamp(base.Add(1 * time.Millisecond))
	t3 := FormatTimestamp(base.Add(2 * time.Millisecond))
	t4 := FormatTimestamp(base.Add(3 * time.Millisecond)) // 晚于全部 open，仅用于验证 play 不计

	assets := []struct {
		id string
	}{{"h1"}, {"h2"}, {"h3"}, {"h4"}}
	for _, a := range assets {
		if _, err := q.UpsertAsset(ctx, db.UpsertAssetParams{
			AssetID: a.id, LibraryID: libID, RelPath: "f/" + a.id + ".jpg",
			FileName: a.id + ".jpg", MediaType: "image", SizeBytes: 1,
			Mtime: t1, CreatedAt: t1, UpdatedAt: t1,
		}); err != nil {
			t.Fatalf("插入资产 %s 失败: %v", a.id, err)
		}
	}

	events := []db.InsertViewEventParams{
		// h1：两次 open 取 MAX=t3；其后 play@t4 不得抬高 last_viewed_at
		{AssetID: "h1", Kind: "open", SessionID: "s1", StartedAt: t1},
		{AssetID: "h1", Kind: "open", SessionID: "s2", StartedAt: t3},
		{AssetID: "h1", Kind: "play", SessionID: "s3", StartedAt: t4},
		// h2 / h3：同毫秒 open@t2 → 排序落入 asset_id 决胜（h3 > h2）
		{AssetID: "h2", Kind: "open", SessionID: "s1", StartedAt: t2},
		{AssetID: "h3", Kind: "open", SessionID: "s1", StartedAt: t2},
		// h3 的 dwell@t3 不得计入
		{AssetID: "h3", Kind: "dwell", SessionID: "s1", StartedAt: t3, Seconds: sqlInt64(5)},
		// h4：只有 play（从未打开过）→ 不出现在历史
		{AssetID: "h4", Kind: "play", SessionID: "s1", StartedAt: t3},
		// ghost：无资产行的孤儿事件（删除后残留，adr/0005）→ 不出现
		{AssetID: "ghost", Kind: "open", SessionID: "s1", StartedAt: t3},
	}
	for _, e := range events {
		if err := q.InsertViewEvent(ctx, e); err != nil {
			t.Fatalf("插入事件 %+v 失败: %v", e, err)
		}
	}
	if _, err := q.AddFavorite(ctx, db.AddFavoriteParams{AssetID: "h2", CreatedAt: t1}); err != nil {
		t.Fatalf("收藏 h2 失败: %v", err)
	}

	// 首页（limit=2）：期望 [h1(t3), h3(t2)]——同 t2 的 h3 靠资产 ID 压过 h2。
	page1, err := q.ListHistory(ctx, db.ListHistoryParams{
		IncludeCos: int64(1), CosOnly: int64(0), SourceIsOther: int64(0), RowLimit: 2,
	})
	if err != nil {
		t.Fatalf("ListHistory 首页失败: %v", err)
	}
	if len(page1) != 2 {
		t.Fatalf("首页行数 = %d, 期望 2: %+v", len(page1), page1)
	}
	want1 := []struct {
		id string
		ts string
	}{{"h1", t3}, {"h3", t2}}
	for i, w := range want1 {
		if page1[i].AssetID != w.id {
			t.Fatalf("首页第 %d 行 = %s, 期望 %s（排序或每资产 MAX 口径错误）", i, page1[i].AssetID, w.id)
		}
		if got, _ := page1[i].LastViewedAt.(string); got != w.ts {
			t.Fatalf("%s last_viewed_at = %v, 期望 %s（play/dwell 不得计入，open 取 MAX）",
				w.id, page1[i].LastViewedAt, w.ts)
		}
	}

	// 翻页：游标 = 首页末行 (t2, h3) → 期望只剩 [h2]（严格续读不重复）。
	page2, err := q.ListHistory(ctx, db.ListHistoryParams{
		IncludeCos: int64(1), CosOnly: int64(0), SourceIsOther: int64(0), RowLimit: 2,
		CursorKey: t2,
		CursorID:  sql.NullString{String: "h3", Valid: true},
	})
	if err != nil {
		t.Fatalf("ListHistory 翻页失败: %v", err)
	}
	if len(page2) != 1 || page2[0].AssetID != "h2" {
		t.Fatalf("翻页结果 = %+v, 期望仅 [h2]（游标严格续读或同毫秒决胜键失效）", page2)
	}
	if got, _ := page2[0].LastViewedAt.(string); got != t2 {
		t.Fatalf("h2 last_viewed_at = %v, 期望 %s", page2[0].LastViewedAt, t2)
	}
	if !page2[0].IsFavorite {
		t.Fatalf("h2 的 is_favorite = false, 期望 true（投影字段随改写丢失）")
	}

	// 游标落在 h1 (t3, h1)：t2 < t3 的两行都应返回，顺序仍 (t2, h3) → (t2, h2)。
	pageAfterH1, err := q.ListHistory(ctx, db.ListHistoryParams{
		IncludeCos: int64(1), CosOnly: int64(0), SourceIsOther: int64(0), RowLimit: 2,
		CursorKey: t3,
		CursorID:  sql.NullString{String: "h1", Valid: true},
	})
	if err != nil {
		t.Fatalf("ListHistory 以 h1 为游标翻页失败: %v", err)
	}
	if len(pageAfterH1) != 2 || pageAfterH1[0].AssetID != "h3" || pageAfterH1[1].AssetID != "h2" {
		t.Fatalf("h1 之后应剩 [h3 h2], 实得 %+v", pageAfterH1)
	}
}
