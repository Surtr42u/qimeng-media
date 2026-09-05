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
	offset, ok := resolvePageOffset(w, params.Offset)
	if !ok {
		return
	}
	period := string(gen.GetApiV1RankingsParamsPeriodWeek)
	if params.Period != nil {
		period = string(*params.Period)
	}

	// 排行不参与每日展示计数，day 参数无实际用途；仍传当前日界避免
	// 查询参数歧义（shown_today 仅推荐流消费）。CosOnly 恒传 0：排行榜
	// 维持既有「常规流排除 COS」口径不变（recommend.sql 双分支谓词依赖
	// 两值逻辑，缺省 NULL 会整库排除——与推荐端点同一约束）。
	rows, err := s.q.ListAssetsRecommendInput(r.Context(), db.ListAssetsRecommendInputParams{
		Day:       store.FormatDay(s.now()),
		MediaType: nullStr(""),
		CosOnly:   0,
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

	// 翻页切片（协议 offset，默认 0）：Rank 返回全量热度降序，当前页 =
	// [offset, offset+limit)。offset=0 时与既有「截前 limit 条」行为一致；
	// 越界（offset 落在末页之后）自然为空数组。排行榜不写每日展示计数，
	// 无「切片后回写」一说。
	ranked := slicePage(recommend.Rank(items, period, s.now()), offset, limit)
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
