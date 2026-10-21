package recommendfeed

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"log/slog"
	"time"

	"qimeng-media/server/internal/recommend"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// LoadPage 共享完整顺序，分页只读当前页；WithoutCancel 仅覆盖无副作用的共享计算。
// 第二返回值是原始顺序的下一 offset，nil 表示到底；不依赖资格复核后的条数。
func (c *Cache) LoadPage(ctx context.Context, q *db.Queries, key Key, prefs *recommend.Weights, now time.Time, offset, limit int) ([]db.ListRecommendationPageSummariesRow, *int, error) {
	if offset < 0 || limit <= 0 {
		return nil, nil, errors.New("invalid recommendation page")
	}
	ids, err := c.ordered(ctx, q, key, prefs, now)
	if err != nil {
		return nil, nil, err
	}
	if offset >= len(ids) {
		return []db.ListRecommendationPageSummariesRow{}, nil, nil
	}
	end := len(ids)
	if limit < len(ids)-offset {
		end = offset + limit
	}
	payload, err := json.Marshal(ids[offset:end])
	if err != nil {
		return nil, nil, err
	}
	var next *int
	if end < len(ids) {
		next = &end
	}
	rows, err := q.ListRecommendationPageSummaries(ctx, db.ListRecommendationPageSummariesParams{
		AssetIdsJson: string(payload), MediaType: mediaType(key), CosOnly: cosOnly(key),
	})
	return rows, next, err
}

// Warm 仅生成顺序，不读取页摘要或写展示计数。
func (c *Cache) Warm(ctx context.Context, q *db.Queries, key Key, prefs *recommend.Weights, now time.Time) error {
	_, err := c.ordered(ctx, q, key, prefs, now)
	return err
}

func (c *Cache) ordered(ctx context.Context, q *db.Queries, key Key, prefs *recommend.Weights, now time.Time) ([]string, error) {
	return c.order(key, func() ([]string, error) {
		return loadOrder(context.WithoutCancel(ctx), q, key, prefs, now)
	})
}

func mediaType(key Key) sql.NullString {
	return sql.NullString{String: key.MediaType, Valid: key.MediaType != ""}
}
func cosOnly(key Key) int64 {
	if key.CosOnly {
		return 1
	}
	return 0
}

func loadOrder(ctx context.Context, q *db.Queries, key Key, prefs *recommend.Weights, now time.Time) ([]string, error) {
	start := time.Now()
	input, err := store.LoadRecommendationInputs(ctx, q, key.Day, mediaType(key), cosOnly(key))
	if err != nil {
		return nil, err
	}
	queryElapsed := time.Since(start)
	items := make([]recommend.Item, len(input.Rows))
	for i, row := range input.Rows {
		items[i] = recommend.Item{
			AssetID: row.AssetID, FileName: row.FileName, MediaType: row.MediaType,
			CreatedAt: parseTime(row.CreatedAt), ModifiedAt: parseTime(row.Mtime),
			Tags: input.TagsByAsset[row.AssetID], LikeCount: int(row.LikeCount), ShownToday: number(row.ShownToday),
			Stats: recommend.Stats{ViewCount: int(row.ViewCount), PlayCount: int(row.PlayCount), BrowseSeconds: int64(number(row.BrowseSeconds)), LastViewedAt: lastViewed(row.LastViewedAt)},
		}
	}
	ordered := recommend.Recommend(items, recommend.Params{Limit: len(items), Seed: key.Seed, Prefs: prefs, Now: now})
	ids := make([]string, len(ordered))
	for i := range ordered {
		ids[i] = ordered[i].AssetID
	}
	slog.Info("推荐顺序冷算分段耗时", "seed", key.Seed, "candidates", len(ids),
		"query", queryElapsed.Round(time.Millisecond).String(), "algorithm", (time.Since(start) - queryElapsed).Round(time.Millisecond).String())
	return ids, nil
}

func parseTime(raw string) time.Time {
	t, err := time.Parse(store.TimestampLayout, raw)
	if err != nil {
		return time.Time{}
	}
	return t
}
func lastViewed(value any) *time.Time {
	raw, ok := value.(string)
	if !ok || raw == "" {
		return nil
	}
	t := parseTime(raw)
	return &t
}

// sqlc 的嵌套 COALESCE 可生成为 any；与旧适配层的整数映射保持一致。
func number(value any) int {
	switch v := value.(type) {
	case int64:
		return int(v)
	case int:
		return v
	case float64:
		return int(v)
	default:
		return 0
	}
}
