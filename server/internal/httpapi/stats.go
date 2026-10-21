// stats.go：统计协议适配；窗口装载由 statsread 编排，分桶由 stats 纯函数统一计算。
package httpapi

import (
	"context"
	"net/http"
	"time"

	openapi_types "github.com/oapi-codegen/runtime/types"

	"qimeng-media/server/internal/eventstats"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/stats"
	"qimeng-media/server/internal/statsread"
	"qimeng-media/server/internal/store"
)

func localDayBoundsUTC(now time.Time) (string, string) { return statsread.DayBounds(now) }
func trendWindowStart(rng stats.Range, now time.Time) time.Time {
	return statsread.WindowStart(rng, now)
}

func protocolStatsTotal(value stats.Totals) gen.StatsTotals {
	return gen.StatsTotals{ViewCount: int(value.ViewCount), PlayCount: int(value.PlayCount), Seconds: int(value.Seconds)}
}
func protocolStatsMap(values map[string]stats.Totals) *map[string]gen.StatsTotals {
	out := make(map[string]gen.StatsTotals, len(values))
	for key, value := range values {
		out[key] = protocolStatsTotal(value)
	}
	return &out
}

func (s *Server) GetApiV1StatsOverview(w http.ResponseWriter, r *http.Request, params gen.GetApiV1StatsOverviewParams) {
	rng, ok := resolveStatsRange(w, params.Range, stats.RangeAll)
	if !ok {
		return
	}
	data, err := statsread.LoadOverview(r.Context(), s.q, rng, s.now())
	if err != nil {
		s.internalErr(w, "统计资产总览", err)
		return
	}
	writeJSON(w, http.StatusOK, gen.StatsOverview{
		TotalFiles:         ptr(int(data.Assets.TotalFiles)),
		ImageCount:         ptr(int(data.Assets.ImageCount)), // 旧字段保留动图并入图片的兼容语义。
		StillImageCount:    ptr(int(data.Assets.StillImageCount)),
		AnimatedImageCount: ptr(int(data.Assets.AnimatedImageCount)),
		VideoCount:         ptr(int(data.Assets.VideoCount)),
		TotalSizeBytes:     ptr(data.Assets.TotalSizeBytes),
		TodayViews:         ptr(int(data.TodayViews)), TotalViews: ptr(data.TotalViews),
		SourceNormalCount: ptr(int(data.NormalCount)), SourceCosCount: ptr(int(data.CosCount)),
		AvgViewsPerFile:       data.Average,
		ViewedFilesCount:      ptr(int(data.Opens.Files)),
		ViewedAuthorsCount:    ptr(int(data.Opens.Authors)),
		ViewedTagsCount:       ptr(int(data.Opens.Tags)),
		WindowTotals:          ptr(protocolStatsTotal(data.Window)),
		MediaTypeWindowTotals: protocolStatsMap(data.MediaTypes),
		SourceWindowTotals:    protocolStatsMap(data.Sources),
		PerTypeSizeBytes:      &gen.StatsSizeByType{Image: ptr(data.Assets.ImageSizeBytes), Video: ptr(data.Assets.VideoSizeBytes), AnimatedImage: ptr(data.Assets.AnimatedImageSizeBytes)},
		PerSourceSizeBytes:    &gen.StatsSizeBySource{Normal: ptr(data.Sizes.NormalSizeBytes), Cos: ptr(data.Sizes.CosSizeBytes)},
	})
}

func (s *Server) GetApiV1StatsTrends(w http.ResponseWriter, r *http.Request, params gen.GetApiV1StatsTrendsParams) {
	rng, ok := resolveStatsRange(w, params.Range, stats.RangeMonth)
	if !ok {
		return
	}
	mediaType, source := "", ""
	if params.MediaType != nil {
		mediaType = string(*params.MediaType)
	}
	if params.Source != nil {
		switch *params.Source {
		case gen.GetApiV1StatsTrendsParamsSourceNormal, gen.GetApiV1StatsTrendsParamsSourceCos:
			source = string(*params.Source)
		default:
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "source 取值不合法")
			return
		}
	}
	now := s.now()
	rows, err := statsread.LoadDimensions(r.Context(), s.q, rng, now, mediaType, source)
	if err != nil {
		s.internalErr(w, "统计趋势数据", err)
		return
	}
	buckets := stats.BuildDimensionBuckets(rows, rng, now)
	out := make([]gen.TrendBucket, 0, len(buckets))
	for _, bucket := range buckets {
		out = append(out, gen.TrendBucket{
			Label: ptr(bucket.Label), Start: dayPtr(bucket.Start), End: dayPtr(bucket.End),
			ViewCount: ptr(int(bucket.ViewCount)), PlayCount: ptr(int(bucket.PlayCount)), Seconds: ptr(int(bucket.Seconds)),
			MediaTypes: protocolStatsMap(bucket.MediaTypes), Sources: protocolStatsMap(bucket.Sources),
		})
	}
	writeJSON(w, http.StatusOK, out)
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
	return eventstats.Rebuild(ctx, s.conn)
}
