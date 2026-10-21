// rankings.go：排行榜协议适配。窗口/累计取数、纯函数选页与摘要装配由
// ranking 编排；HTTP 只校验参数、组装签名摘要并序列化（DOMAIN_RULES §2）。
package httpapi

import (
	"net/http"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/ranking"
)

// GetApiV1Rankings 排行榜：period 默认 week（openapi default）。
func (s *Server) GetApiV1Rankings(w http.ResponseWriter, r *http.Request, params gen.GetApiV1RankingsParams) {
	limit, ok := resolveRankingLimit(w, params.Limit)
	if !ok {
		return
	}
	offset, ok := resolvePageOffset(w, params.Offset)
	if !ok {
		return
	}
	period := string(gen.GetApiV1RankingsParamsPeriodWeek)
	if params.Period != nil {
		period = string(*params.Period)
	}

	rows, next, err := ranking.LoadPage(r.Context(), s.q, period, s.now(), offset, limit)
	if err != nil {
		s.internalErr(w, "查询排行页", err)
		return
	}
	out := make([]gen.AssetSummary, 0, len(rows))
	for _, row := range rows {
		// 排行榜是 AssetSummary 新增 viewCount/playCount 字段的填充端点
		//（当前页专用查询含全量累计计数；浏览列表保持省略，
		// 口径见 assets.go buildSummary 注释）。
		out = append(out, buildSummary(s, row.AssetID, row.FileName, row.MediaType,
			row.SizeBytes, row.Mtime, row.CreatedAt, row.Source, row.IsFavorite, row.LikeCount,
			ptr(int(row.ViewCount)), ptr(int(row.PlayCount))))
	}
	writeNextOffset(w, next)
	writeJSON(w, http.StatusOK, out)
}
