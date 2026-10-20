package ranking

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"qimeng-media/server/internal/recommend"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// LoadPage 完整读取热度键后选页，仅为返回页读取元数据与全量累计卡片计数。
// 两次读取之间的删除/库开关/COS 归属变更由摘要 SQL 再校验；可能不足一页，
// 不会返回已失去资格的资产。与原多次聚合读取一样，不承诺跨查询事务快照。
func LoadPage(ctx context.Context, q *db.Queries, period string, now time.Time, offset, limit int) ([]db.ListRankingPageSummariesRow, error) {
	if offset < 0 || limit <= 0 {
		return nil, errors.New("invalid ranking page")
	}
	rows, windowed, err := loadKeys(ctx, q, period, now)
	if err != nil {
		return nil, err
	}
	if offset >= len(rows) {
		return []db.ListRankingPageSummariesRow{}, nil
	}
	items := make([]recommend.Item, len(rows))
	for i, row := range rows {
		modified, parseErr := time.Parse(store.TimestampLayout, row.Mtime)
		if parseErr != nil {
			modified = time.Time{} // 与原 HTTP parseStoreTime 的脏时间兜底一致。
		}
		items[i] = recommend.Item{AssetID: row.AssetID, ModifiedAt: modified}
		if windowed {
			items[i].Window = recommend.WindowCounts{ViewCount: int(row.ViewCount), PlayCount: int(row.PlayCount), LikeCount: int(row.LikeCount)}
		} else {
			items[i].Stats = recommend.Stats{ViewCount: int(row.ViewCount), PlayCount: int(row.PlayCount)}
			items[i].LikeCount = int(row.LikeCount)
		}
	}
	ask := len(items)
	if limit < len(items)-offset {
		ask = offset + limit
	}
	ordered := recommend.RankTop(items, period, now, ask)
	if offset >= len(ordered) {
		return []db.ListRankingPageSummariesRow{}, nil
	}
	ordered = ordered[offset:]
	ids := make([]string, len(ordered))
	for i := range ordered {
		ids[i] = ordered[i].AssetID
	}
	payload, err := json.Marshal(ids)
	if err != nil {
		return nil, err
	}
	return q.ListRankingPageSummaries(ctx, string(payload))
}

func loadKeys(ctx context.Context, q *db.Queries, period string, now time.Time) ([]db.ListRankingAllInputRow, bool, error) {
	window, windowed := recommend.PeriodWindow(period)
	if !windowed {
		rows, err := q.ListRankingAllInput(ctx)
		return rows, false, err
	}
	raw, err := q.ListRankingWindowInput(ctx, store.FormatDay(now.Add(-window)))
	if err != nil {
		return nil, true, err
	}
	rows := make([]db.ListRankingAllInputRow, len(raw))
	for i := range raw {
		rows[i] = db.ListRankingAllInputRow(raw[i])
	}
	return rows, true, nil
}
