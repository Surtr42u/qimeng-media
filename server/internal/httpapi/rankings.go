// rankings.go：排行榜端点（M3，DOMAIN_RULES §2）。
//
// 热度 = viewCount + playCount + likeCount 降序（纯热度，不个性化）；
// 非 all 周期按 lastViewedAt >= now − 窗口 过滤（open 事件聚合）。
// 数据复用 ListAssetsRecommendInput（与推荐流同一输入行，统计口径
// 同源），排序在 recommend.Rank（纯函数）。
package httpapi

import (
	"net/http"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/recommend"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// GetApiV1Rankings 排行榜：period 默认 week（openapi default）。
func (s *Server) GetApiV1Rankings(w http.ResponseWriter, r *http.Request, params gen.GetApiV1RankingsParams) {
	limit, ok := resolveRankingLimit(w, params.Limit)
	if !ok {
		return
	}
	period := string(gen.GetApiV1RankingsParamsPeriodWeek)
	if params.Period != nil {
		period = string(*params.Period)
	}

	// 排行不参与每日展示计数，day 参数无实际用途；仍传当前日界避免
	// 查询参数歧义（shown_today 仅推荐流消费）。
	rows, err := s.q.ListAssetsRecommendInput(r.Context(), db.ListAssetsRecommendInputParams{
		Day:       store.FormatDay(s.now()),
		MediaType: nullStr(""),
	})
	if err != nil {
		s.internalErr(w, "查询排行输入", err)
		return
	}

	items := make([]recommend.Item, 0, len(rows))
	rowByID := make(map[string]*db.ListAssetsRecommendInputRow, len(rows))
	for i := range rows {
		row := &rows[i]
		rowByID[row.AssetID] = row
		items = append(items, recommend.Item{
			AssetID:    row.AssetID,
			FileName:   row.FileName,
			MediaType:  row.MediaType,
			CreatedAt:  parseStoreTime(row.CreatedAt),
			ModifiedAt: parseStoreTime(row.Mtime),
			LikeCount:  int(row.LikeCount),
			Stats: recommend.Stats{
				ViewCount:    int(row.ViewCount),
				PlayCount:    int(row.PlayCount),
				LastViewedAt: parseLastViewed(row.LastViewedAt),
			},
		})
	}

	ranked := recommend.Rank(items, period, s.now())
	if len(ranked) > limit {
		ranked = ranked[:limit]
	}
	out := make([]gen.AssetSummary, 0, len(ranked))
	for _, it := range ranked {
		row := rowByID[it.AssetID]
		// 排行榜是 AssetSummary 新增 viewCount/playCount 字段的填充端点
		//（数据源 ListAssetsRecommendInput 已含聚合；浏览列表保持省略，
		// 口径见 assets.go buildSummary 注释）。
		out = append(out, buildSummary(s, row.AssetID, row.FileName, row.MediaType,
			row.SizeBytes, row.Mtime, row.CreatedAt, row.Source, row.IsFavorite, row.LikeCount,
			ptr(int(row.ViewCount)), ptr(int(row.PlayCount))))
	}
	writeJSON(w, http.StatusOK, out)
}
