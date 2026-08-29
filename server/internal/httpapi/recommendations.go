// recommendations.go：推荐流端点的 M2 热度占位实现。
//
// M2 占位语义（PROJECT_PLAN：M3 前用热度排序占位）：viewCount DESC
// 纯热度序，seed 参数收下但忽略——M3 十维算法（DOMAIN_RULES §1，
// seed 承担同分桶打散）接入时只换本文件实现，参数面与协议不变，
// 三端 SDK 零感知。
package httpapi

import (
	"database/sql"
	"net/http"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store/db"
)

// GetApiV1Recommendations 推荐流（M2 热度占位：浏览量降序）。
func (s *Server) GetApiV1Recommendations(w http.ResponseWriter, r *http.Request, params gen.GetApiV1RecommendationsParams) {
	limit, ok := resolvePageLimit(w, params.Limit)
	if !ok {
		return
	}
	var filters assetFilters
	if params.MediaType != nil {
		filters.MediaType = nullStr(string(*params.MediaType))
	}
	// sort="viewCount" 是 browse.sql 既有排序键（viewCount DESC, asset_id
	// tiebreaker）；无游标（推荐流固定取首页，翻页语义 M3 随算法定义）。
	p := db.ListAssetsFilteredDescParams{
		Sort: "viewCount", RowLimit: int64(limit),
		CursorKey: nullStr(""), CursorID: sql.NullString{},
	}
	applyFilters(&p, filters)
	rows, err := s.q.ListAssetsFilteredDesc(r.Context(), p)
	if err != nil {
		s.internalErr(w, "查询推荐流", err)
		return
	}
	items := make([]gen.AssetSummary, 0, len(rows))
	for i := range rows {
		row := &rows[i]
		items = append(items, buildSummary(s, row.AssetID, row.FileName, row.MediaType,
			row.SizeBytes, row.Mtime, row.CreatedAt, row.Source, row.IsFavorite, row.LikeCount))
	}
	writeJSON(w, http.StatusOK, items)
}
