// rankings.go：排行榜端点（M3，DOMAIN_RULES §2）。
//
// 热度 = 窗口内 open+play 事件计数 + 窗口内点赞计数，降序（2026-09-18
// 窗口化：非 all 周期由 handler 从按天数据——asset_daily_stats 物化表与
// likes 的「资产×日」行，day ≥ cutoffDay——预算窗口计数填进 Item.Window，
// recommend.Rank 只排序；period=all 走原全量累计路径，零额外查询）。
// AssetSummary 的 viewCount/playCount 字段仍填全量累计值（卡片角标口径，
// 见 assets.go buildSummary 注释）。数据基座复用 ListAssetsRecommendInput
// （启用库过滤 + 常规流排除 COS 与推荐流同源），排序在 recommend.Rank
// （纯函数）。
package httpapi

import (
	"context"
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

	// 窗口化（DOMAIN_RULES §2）：非 all 周期预算窗口内计数。cutoffDay =
	// now − 窗口 所在的本地日历日，按日取界——cutoff 当日全天活动计入
	// 窗口（按天表的最高粒度）；period=all 跳过，与旧行为零差异。
	if win, ok := recommend.PeriodWindow(period); ok {
		cutoffDay := store.FormatDay(s.now().Add(-win))
		if err := s.fillRankingWindowCounts(r.Context(), items, cutoffDay); err != nil {
			s.internalErr(w, "查询排行窗口计数", err)
			return
		}
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

// fillRankingWindowCounts 把窗口内热度计数就地填进 items（DOMAIN_RULES
// §2）：view/play 取 asset_daily_stats 物化表窗口求和，like 取 likes
// 「资产×日」行窗口计数，均 day ≥ cutoffDay。窗口内无活动的资产保持
// 零值——Rank 的准入过滤（窗口热度 > 0）据此排除。映射按 asset_id 连接：
// 主查询已过滤启用库与常规流（CosOnly=0），聚合行中的多余资产（COS
// 关联等）不会被查到。
func (s *Server) fillRankingWindowCounts(ctx context.Context, items []recommend.Item, cutoffDay string) error {
	statsRows, err := s.q.SumWindowedDailyStats(ctx, cutoffDay)
	if err != nil {
		return err
	}
	likeRows, err := s.q.CountWindowedLikes(ctx, cutoffDay)
	if err != nil {
		return err
	}
	byAsset := make(map[string]recommend.WindowCounts, len(statsRows)+len(likeRows))
	for _, r := range statsRows {
		c := byAsset[r.AssetID]
		c.ViewCount, c.PlayCount = int(r.ViewCount), int(r.PlayCount)
		byAsset[r.AssetID] = c
	}
	for _, r := range likeRows {
		c := byAsset[r.AssetID]
		c.LikeCount = int(r.LikeCount)
		byAsset[r.AssetID] = c
	}
	for i := range items {
		items[i].Window = byAsset[items[i].AssetID]
	}
	return nil
}
