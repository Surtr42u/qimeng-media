// stats_top.go：统计聚合榜单端点（协议批 P2，2026-09-09：most-viewed /
// top-authors / top-tags）。口径见 DOMAIN_RULES §5「统计聚合榜单口径」：
// 数据源 = view_events 事件流（唯一真相源），窗口复用 /stats/trends 的
// 固定 range 窗口表；内连接 assets 限定现存资产（榜单须返回文件信息，
// 已删资产事件不入榜——总览类纯计数口径不受影响，ADR-0005）。
package httpapi

import (
	"net/http"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/stats"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// 榜单 limit 语义（openapi /stats/* 三端点共用）：缺省 20、上限 50；
// <1（含 0/负数）回落缺省——服务端钳制而非 400，客户端传错拿全量首部
// 比报错更接近榜单语义。
const (
	statsTopLimitDefault = 20
	statsTopLimitMax     = 50
)

// validStatsRange 判定 range 取值合法（openapi enum 与 stats.Range 双同步，
// 与 GetApiV1StatsTrends 既有校验同一集合）。
func validStatsRange(raw string) bool {
	switch stats.Range(raw) {
	case stats.RangeDay, stats.RangeWeek, stats.RangeMonth,
		stats.RangeQuarter, stats.RangeYear, stats.RangeAll,
		stats.Range7d, stats.Range90d:
		return true
	default:
		return false
	}
}

// resolveStatsRange 解析 stats 族共用的 range 查询参数：nil → def
// （榜单端点传 RangeMonth 与 /stats/trends 缺省一致；overview 传 RangeAll
// =全时段累计口径）。非法值写 400 并返回 false。T ~string：gen 的枚举
// 参数类型（GetApiV1StatsXxxParamsRange）都是 string 的 defined type。
func resolveStatsRange[T ~string](w http.ResponseWriter, raw *T, def stats.Range) (stats.Range, bool) {
	rng := def
	if raw != nil {
		rng = stats.Range(*raw)
		if !validStatsRange(string(rng)) {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "range 取值不合法")
			return rng, false
		}
	}
	return rng, true
}

// statsWindowStartTS range → 事件流窗口下界：trendWindowStart 只做「天数
// 回退」（起点仍是当天当前钟点），这里再落回其本地日历日 00:00（同
// localDayBoundsUTC 构造法），与 /stats/trends 的 FormatDay(start) 同一
// 日历日语义——含窗口首日全天。RangeAll → 空串 = 查询不设窗口。
func statsWindowStartTS(rng stats.Range, now time.Time) string {
	start := trendWindowStart(rng, now)
	if start.IsZero() {
		return ""
	}
	local := start.Local()
	dayStart := time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, local.Location())
	return store.FormatTimestamp(dayStart)
}

// clampTopLimit 榜单 limit 钳制（语义见常量注释）。
func clampTopLimit(l *int) int {
	switch {
	case l == nil || *l < 1:
		return statsTopLimitDefault
	case *l > statsTopLimitMax:
		return statsTopLimitMax
	default:
		return *l
	}
}

// GetApiV1StatsMostViewed 常看文件：metric=views 按窗口内 open 次数倒序、
// metric=seconds 按窗口内 dwell seconds 累计倒序（无 dwell 事件的文件
// 不参与 seconds 榜）。
func (s *Server) GetApiV1StatsMostViewed(w http.ResponseWriter, r *http.Request, params gen.GetApiV1StatsMostViewedParams) {
	rng, ok := resolveStatsRange(w, params.Range, stats.RangeMonth)
	if !ok {
		return
	}
	metric := gen.Views // openapi default: views
	if params.Metric != nil {
		// wrapper 只做类型绑定不做枚举校验，非法值必须在此显式拒绝
		//（与 range 同一口径，不静默回退缺省）。
		switch *params.Metric {
		case gen.Views, gen.Seconds:
			metric = *params.Metric
		default:
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "metric 取值不合法")
			return
		}
	}
	limit := clampTopLimit(params.Limit)
	from := statsWindowStartTS(rng, s.now())
	ctx := r.Context()

	var items []gen.MostViewedItem
	appendRow := func(assetID, fileName, mediaType string, value int64) {
		// asset_id 在库里恒为 UUID（ADR-0004 永不引用路径）；解析失败视为
		// 脏行跳过，不让单行坏数据炸掉整条榜单。
		id, err := uuid.Parse(assetID)
		if err != nil {
			return
		}
		mt := gen.MediaType(mediaType)
		thumb := s.thumbURL(assetID, "md")
		items = append(items, gen.MostViewedItem{
			AssetId: id, FileName: fileName,
			MediaType: mt, ThumbUrl: &thumb, Value: int(value),
		})
	}
	switch metric {
	case gen.Seconds:
		rows, err := s.q.TopDwellAssets(ctx, db.TopDwellAssetsParams{FromTs: from, Lim: int64(limit)})
		if err != nil {
			s.internalErr(w, "统计常看文件", err)
			return
		}
		items = make([]gen.MostViewedItem, 0, len(rows))
		for _, row := range rows {
			appendRow(row.AssetID, row.FileName, row.MediaType, row.Value)
		}
	case gen.Views:
		rows, err := s.q.TopOpenAssets(ctx, db.TopOpenAssetsParams{FromTs: from, Lim: int64(limit)})
		if err != nil {
			s.internalErr(w, "统计常看文件", err)
			return
		}
		items = make([]gen.MostViewedItem, 0, len(rows))
		for _, row := range rows {
			appendRow(row.AssetID, row.FileName, row.MediaType, row.Value)
		}
	}
	writeJSON(w, http.StatusOK, items)
}

// GetApiV1StatsTopAuthors 常看作者：窗口内该作者关联资产的 open 次数倒序
// （COS 作者按其 COS 关联资产计——asset_authors 前缀隔离天然成立）。
func (s *Server) GetApiV1StatsTopAuthors(w http.ResponseWriter, r *http.Request, params gen.GetApiV1StatsTopAuthorsParams) {
	rng, ok := resolveStatsRange(w, params.Range, stats.RangeMonth)
	if !ok {
		return
	}
	limit := clampTopLimit(params.Limit)
	rows, err := s.q.TopOpenAuthors(r.Context(), db.TopOpenAuthorsParams{
		FromTs: statsWindowStartTS(rng, s.now()), Lim: int64(limit),
	})
	if err != nil {
		s.internalErr(w, "统计常看作者", err)
		return
	}
	items := make([]gen.TopAuthorItem, 0, len(rows))
	for _, row := range rows {
		items = append(items, gen.TopAuthorItem{
			AuthorId: row.AuthorID, DisplayName: row.DisplayName, Views: int(row.Views),
		})
	}
	writeJSON(w, http.StatusOK, items)
}

// GetApiV1StatsTopTags 常看标签：窗口内带该标签资产的 open 次数倒序。
func (s *Server) GetApiV1StatsTopTags(w http.ResponseWriter, r *http.Request, params gen.GetApiV1StatsTopTagsParams) {
	rng, ok := resolveStatsRange(w, params.Range, stats.RangeMonth)
	if !ok {
		return
	}
	limit := clampTopLimit(params.Limit)
	rows, err := s.q.TopOpenTags(r.Context(), db.TopOpenTagsParams{
		FromTs: statsWindowStartTS(rng, s.now()), Lim: int64(limit),
	})
	if err != nil {
		s.internalErr(w, "统计常看标签", err)
		return
	}
	items := make([]gen.TopTagItem, 0, len(rows))
	for _, row := range rows {
		items = append(items, gen.TopTagItem{Tag: row.Tag, Views: int(row.Views)})
	}
	writeJSON(w, http.StatusOK, items)
}
