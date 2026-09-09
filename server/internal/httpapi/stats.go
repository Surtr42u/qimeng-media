// stats.go：M3 统计端点（总览 + 趋势）与「文件×天」物化聚合表重建。
// 分桶口径在 internal/stats 纯函数包（DOMAIN_RULES §5），本文件只做
// 取数窗口计算、类型转换与 IO 编排。
package httpapi

import (
	"context"
	"net/http"
	"time"

	openapi_types "github.com/oapi-codegen/runtime/types"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/stats"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// 趋势取数窗口：api/openapi.yaml /stats/trends 的 range 枚举各自回看的
// 数据宽度（协议侧窗口语义改动须同步此处，反之亦然）。窗口与 stats 固定
// 分桶粒度一一对应（DOMAIN_RULES §5：day 按天 / week 按周 / month 按月 /
// quarter 按季 / year 按年；all = 全跨度不设窗口，由动态粒度选择覆盖）。
const (
	trendDayWindowDays      = 30 // range=day：近 30 天
	trendWeekWindowDays     = 84 // range=week：近 12 周（12×7 天）
	trendMonthWindowMonths  = 24 // range=month：近 24 个月
	trendQuarterWindowMonth = 24 // range=quarter：近 8 个季度（8×3 = 24 个月）
	trendYearWindowYears    = 5  // range=year：近 5 年
	trend7dWindowDays       = 7  // range=7d：近 7 天逐日桶
	trend90dWindowDays      = 90 // range=90d：近 90 天逐日桶
)

// localDayBoundsUTC 返回 t 所在本地日历日 [00:00, 次日 00:00) 的 UTC 时间戳
// 串（store.TimestampLayout 格式；同格式 TEXT 字典序 == 时间序，见
// migrations/0001_init.up.sql 文件头），用于 view_events.started_at 的
// 「当日」区间比较。次日用日期构造而非 +24h，避免 DST 日的窗口漂移。
// 事件去重（engagement.go）与总览今日浏览（本文件）共用这一口径。
func localDayBoundsUTC(t time.Time) (start, end string) {
	local := t.Local()
	dayStart := time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, local.Location())
	nextDay := time.Date(local.Year(), local.Month(), local.Day()+1, 0, 0, 0, 0, local.Location())
	return store.FormatTimestamp(dayStart), store.FormatTimestamp(nextDay)
}

// GetApiV1StatsOverview 统计总览：库形态（文件数/类型分布/总大小）取自
// assets 全表聚合，浏览计数（todayViews/totalViews）直接数 view_events
// 事件流——唯一真相源口径（DOMAIN_RULES §5），且事件表无 FK、可能引用
// 已删资产（ADR-0005），删除历史仍是真实浏览，照数不排除。
// 来源库存（sourceNormalCount/sourceCosCount）与平均浏览（avgViewsPerFile，
// 窗口由 range 参数决定、缺省 all=全时段）为协议批 P2 新增字段（§5）。
func (s *Server) GetApiV1StatsOverview(w http.ResponseWriter, r *http.Request, params gen.GetApiV1StatsOverviewParams) {
	summary, err := s.q.SummarizeAssets(r.Context())
	if err != nil {
		s.internalErr(w, "统计资产总览", err)
		return
	}
	dayStart, dayEnd := localDayBoundsUTC(s.now())
	todayViews, err := s.q.CountOpenEventsOnDay(r.Context(), db.CountOpenEventsOnDayParams{
		StartedAt: dayStart, StartedAt_2: dayEnd,
	})
	if err != nil {
		s.internalErr(w, "统计今日浏览", err)
		return
	}
	totalViews, err := s.q.CountOpenEventsAll(r.Context())
	if err != nil {
		s.internalErr(w, "统计累计浏览", err)
		return
	}
	// 来源库存：§6 分区判定两条独立计数（normal + cos == totalFiles）。
	cosCount, err := s.q.CountCosLinkedAssets(r.Context())
	if err != nil {
		s.internalErr(w, "统计 COS 来源库存", err)
		return
	}
	regCount, err := s.q.CountRegularAssets(r.Context())
	if err != nil {
		s.internalErr(w, "统计常规来源库存", err)
		return
	}
	// 平均浏览：窗口内现存资产 open 总数 ÷ 有 open 的不同文件数；
	// 分母 0 → nil（协议 null，空态语义），分子分母同窗口同限定。
	rng, ok := resolveStatsRange(w, params.Range, stats.RangeAll)
	if !ok {
		return
	}
	agg, err := s.q.SummarizeOpenWindow(r.Context(), statsWindowStartTS(rng, s.now()))
	if err != nil {
		s.internalErr(w, "统计平均浏览", err)
		return
	}
	var avg *float64
	if agg.Files > 0 {
		v := float64(agg.Opens) / float64(agg.Files)
		avg = &v
	}
	writeJSON(w, http.StatusOK, gen.StatsOverview{
		TotalFiles:        ptr(int(summary.TotalFiles)),
		ImageCount:        ptr(int(summary.ImageCount)), // 含 animated_image（DOMAIN_RULES §11）
		VideoCount:        ptr(int(summary.VideoCount)),
		TotalSizeBytes:    ptr(summary.TotalSizeBytes),
		TodayViews:        ptr(int(todayViews)),
		TotalViews:        ptr(totalViews),
		SourceNormalCount: ptr(int(regCount)),
		SourceCosCount:    ptr(int(cosCount)),
		AvgViewsPerFile:   avg,
	})
}

// GetApiV1StatsTrends 趋势折线：按 range 决定取数窗口 → SumDailyStatsBetween
// 拉按天聚合 → stats.BuildTrendBuckets 纯函数分桶 → []gen.TrendBucket。
// source 参数（协议批 P2）按 §6 来源桶过滤物化行（”/缺省 = 不过滤）。
func (s *Server) GetApiV1StatsTrends(w http.ResponseWriter, r *http.Request, params gen.GetApiV1StatsTrendsParams) {
	rng, ok := resolveStatsRange(w, params.Range, stats.RangeMonth)
	if !ok {
		return
	}
	mediaType := "" // 空串 = 不过滤（store 查询的空串哨兵约定）
	if params.MediaType != nil {
		mediaType = string(*params.MediaType)
	}
	sourceBucket := "" // 空串 = 不过滤（常规∪COS）
	if params.Source != nil {
		switch *params.Source {
		case gen.GetApiV1StatsTrendsParamsSourceNormal, gen.GetApiV1StatsTrendsParamsSourceCos:
			sourceBucket = string(*params.Source)
		default:
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "source 取值不合法")
			return
		}
	}

	today := s.now()
	fromDay := ""
	if start := trendWindowStart(rng, today); !start.IsZero() {
		fromDay = store.FormatDay(start)
	}
	rows, err := s.q.SumDailyStatsBetween(r.Context(), db.SumDailyStatsBetweenParams{
		FromDay: fromDay, ToDay: store.FormatDay(today), MediaType: mediaType,
		SourceBucket: sourceBucket,
	})
	if err != nil {
		s.internalErr(w, "统计趋势数据", err)
		return
	}
	daily := make([]stats.DailyRow, len(rows))
	for i, row := range rows {
		daily[i] = stats.DailyRow{
			Day: row.Day, ViewCount: row.ViewCount, PlayCount: row.PlayCount, Seconds: row.BrowseSeconds,
		}
	}
	buckets := stats.BuildTrendBuckets(daily, rng, today)
	out := make([]gen.TrendBucket, 0, len(buckets)) // 空数据也输出 []（前端空态）
	for _, b := range buckets {
		out = append(out, gen.TrendBucket{
			Label:     ptr(b.Label),
			Start:     dayPtr(b.Start),
			End:       dayPtr(b.End),
			ViewCount: ptr(int(b.ViewCount)),
			PlayCount: ptr(int(b.PlayCount)),
			Seconds:   ptr(int(b.Seconds)),
		})
	}
	writeJSON(w, http.StatusOK, out)
}

// trendWindowStart 返回 range 对应取数窗口的起点（本地日历日）；RangeAll
// 返回零值（调用方翻译为不过滤的 fromDay 空串）。窗口含今天（起点回退
// 窗口宽 - 1 天，与旧项目 naturalDayCutoff 的「含今天 N 个完整自然日」口径一致）。
func trendWindowStart(rng stats.Range, today time.Time) time.Time {
	local := today.Local()
	switch rng {
	case stats.RangeDay:
		return local.AddDate(0, 0, -(trendDayWindowDays - 1))
	case stats.Range7d:
		return local.AddDate(0, 0, -(trend7dWindowDays - 1))
	case stats.Range90d:
		return local.AddDate(0, 0, -(trend90dWindowDays - 1))
	case stats.RangeWeek:
		return local.AddDate(0, 0, -(trendWeekWindowDays - 1))
	case stats.RangeMonth:
		return local.AddDate(0, -trendMonthWindowMonths, 0)
	case stats.RangeQuarter:
		return local.AddDate(0, -trendQuarterWindowMonth, 0)
	case stats.RangeYear:
		return local.AddDate(-trendYearWindowYears, 0, 0)
	default:
		return time.Time{}
	}
}

// dayPtr 把 yyyy-MM-dd 串转为协议 Date（格式 = store.DayLayout「日」字段
// 契约，由 stats 包保证；解析失败返回 nil = 字段省略，不让单桶坏格式炸掉整条趋势）。
func dayPtr(day string) *openapi_types.Date {
	t, err := time.ParseInLocation(store.DayLayout, day, time.Local)
	if err != nil {
		return nil
	}
	return &openapi_types.Date{Time: t}
}

// RebuildAssetDailyStatsFromEvents 从 view_events 事件流全量重建
// asset_daily_stats 物化聚合表（DOMAIN_RULES §5：按天聚合表是物化视图，
// 口径变更或疑似漂移时调用；M5 旧数据回放导入完成后也走这里回填）。
//
// 为什么在 Go 侧重建而不是 SQL date()：事件写入路径的 day 在 Go 侧由
// started_at 解析为本地日历日（本地时区日界，migrations/0005 表注释），
// SQL 的 date(started_at,'localtime') 是另一条独立实现——SQLite 引擎的
// 时区语义与 Go time.Local 一旦分叉（DST 规则/容器 TZ 缺失等），重建
// 结果与增量累加就会不一致，物化视图静默偏离真相。重建是管理操作
// （非热路径），全表扫描事件流可接受。
//
// view_events 无 FK、可能引用已删资产（ADR-0005），而 asset_daily_stats
// 的 FK 会拒绝孤儿行——重建先取现存资产集合，丢弃已删资产的事件聚合
// （其历史计数继续由事件流本身承载）。
func (s *Server) RebuildAssetDailyStatsFromEvents(ctx context.Context) error {
	events, err := s.q.ListAllViewEvents(ctx)
	if err != nil {
		return err
	}
	liveAssets, err := s.q.ListAllAssetIDs(ctx)
	if err != nil {
		return err
	}
	live := make(map[string]bool, len(liveAssets))
	for _, id := range liveAssets {
		live[id] = true
	}

	// 按（资产 × 本地日）聚合事件：open→view、play→play、dwell→seconds
	type assetDay struct {
		assetID, day string
	}
	totals := make(map[assetDay]*bucketAcc, len(events))
	for _, ev := range events {
		if !live[ev.AssetID] {
			continue // 已删资产：物化表不收，事件流保留（ADR-0005）
		}
		at, err := time.Parse(store.TimestampLayout, ev.StartedAt)
		if err != nil {
			continue // 格式契约破坏（0001 文件头约定），跳过脏行防重建失败
		}
		key := assetDay{assetID: ev.AssetID, day: store.FormatDay(at)}
		acc := totals[key]
		if acc == nil {
			acc = &bucketAcc{}
			totals[key] = acc
		}
		switch ev.Kind {
		case string(gen.Open):
			acc.view++
		case string(gen.Play):
			acc.play++
		case string(gen.Dwell):
			acc.secs += ev.Seconds.Int64 // NULL 视为 0
		}
	}

	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	qtx := s.q.WithTx(tx)
	if err := qtx.DeleteAllAssetDailyStats(ctx); err != nil {
		return err
	}
	for key, acc := range totals {
		if err := qtx.UpsertAssetDailyStats(ctx, db.UpsertAssetDailyStatsParams{
			AssetID: key.assetID, Day: key.day,
			ViewCount: acc.view, PlayCount: acc.play, BrowseSeconds: acc.secs,
		}); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// bucketAcc 是重建聚合的单（资产×日）累加器。
type bucketAcc struct {
	view, play, secs int64
}
