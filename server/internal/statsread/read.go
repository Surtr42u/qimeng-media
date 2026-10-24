package statsread

import (
	"context"
	"time"

	"qimeng-media/server/internal/stats"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

type Overview struct {
	Assets                                        db.SummarizeAssetsRow
	Sizes                                         db.SummarizeSourceSizesRow
	Opens                                         db.SummarizeOpenWindowRow
	TodayViews, TotalViews, NormalCount, CosCount int64
	Average                                       *float64
	Window                                        stats.Totals
	MediaTypes, Sources                           map[string]stats.Totals
}

// LoadDimensions 先由 SQL 限窗并按天/类型/来源聚合，不搬全量资产/事件到内存。
func LoadDimensions(ctx context.Context, q *db.Queries, rng stats.Range, now time.Time, mediaType, source string) ([]stats.DimensionRow, error) {
	from := ""
	if start := WindowStart(rng, now); !start.IsZero() {
		from = store.FormatDay(start)
	}
	rows, err := q.ListDailyStatsDimensions(ctx, db.ListDailyStatsDimensionsParams{
		FromDay: from, ToDay: store.FormatDay(now), MediaType: mediaType, SourceBucket: source,
	})
	if err != nil {
		return nil, err
	}
	out := make([]stats.DimensionRow, len(rows))
	for i, row := range rows {
		out[i] = stats.DimensionRow{DailyRow: stats.DailyRow{Day: row.Day, ViewCount: row.ViewCount, PlayCount: row.PlayCount, Seconds: row.BrowseSeconds}, MediaType: row.MediaType, Source: row.SourceBucket}
	}
	return out, nil
}

// LoadOverview 不开启取写锁的事务；各计数查询为只读，不承诺多次查询的跨请求快照。
// 平均值分子/分母及文件/作者/标签精确数由同一 SQL 语句返回，不以 Top N 反推。
func LoadOverview(ctx context.Context, q *db.Queries, rng stats.Range, now time.Time) (Overview, error) {
	var result Overview
	var err error
	if result.Assets, err = q.SummarizeAssets(ctx); err != nil {
		return result, err
	}
	if result.Sizes, err = q.SummarizeSourceSizes(ctx); err != nil {
		return result, err
	}
	start, end := DayBounds(now)
	if result.TodayViews, err = q.CountOpenEventsOnDay(ctx, db.CountOpenEventsOnDayParams{StartedAt: start, StartedAt_2: end}); err != nil {
		return result, err
	}
	if result.TotalViews, err = q.CountOpenEventsAll(ctx); err != nil {
		return result, err
	}
	if result.CosCount, err = q.CountCosLinkedAssets(ctx); err != nil {
		return result, err
	}
	if result.NormalCount, err = q.CountRegularAssets(ctx); err != nil {
		return result, err
	}
	if result.Opens, err = q.SummarizeOpenWindow(ctx, OpenWindowStart(rng, now)); err != nil {
		return result, err
	}
	if result.Opens.Files > 0 {
		value := float64(result.Opens.Opens) / float64(result.Opens.Files)
		result.Average = &value
	}
	rows, err := LoadDimensions(ctx, q, rng, now, "", "")
	if err != nil {
		return result, err
	}
	result.Window, result.MediaTypes, result.Sources = stats.SumDimensions(rows)
	return result, nil
}
