// assets_list_fill.go：列表端点（GET /assets）条目的批量字段装配（fillList*
// 族：authorNames/cosWork/likedToday）。统一模式——页大小一次 IN 查询后二次
// 装配回 []gen.AssetSummary，避免逐条 N+1；失败策略按字段口径分两级：展示性
// 字段记日志降级（不炸列表），强口径字段上抛 error（见各函数注释）。
package httpapi

import (
	"context"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// fillListAuthorNames 批量装配列表条目的 authorNames（该资产全部作者
// 显示名，常规∪COS——DOMAIN_RULES §6 两类作者统一进 asset_authors）。
// 列表端点协议约定无作者 = 空数组（与"字段省略"区分，客户端据此回退
// 出处展示）。查询失败不炸列表——作者行是展示性增强，记日志保持缺省。
func (s *Server) fillListAuthorNames(ctx context.Context, items []gen.AssetSummary) {
	if len(items) == 0 {
		return
	}
	ids := make([]string, 0, len(items))
	for i := range items {
		ids = append(ids, items[i].Id.String())
	}
	rows, err := s.q.ListAuthorNamesForAssets(ctx, jsonString(ids))
	if err != nil {
		s.logger.Error("查询资产作者名失败", "err", err)
		return
	}
	namesByAsset := make(map[string][]string, len(items))
	for _, r := range rows {
		namesByAsset[r.AssetID] = append(namesByAsset[r.AssetID], r.DisplayName)
	}
	for i := range items {
		names := namesByAsset[items[i].Id.String()]
		if names == nil {
			names = []string{}
		}
		items[i].AuthorNames = &names
	}
}

// fillListCosWork 批量装配列表条目的 cosWork（COS 作品子目录名，COS
// 卡片标题数据源）。仅 COS 库扫描赋值（查询按 cos_work IS NOT NULL
// 过滤）：未命中条目保持 nil——协议 null 语义 = 客户端回退 fileName，
// 常规库资产天然不命中。页大小一次查询二次装配（同 fillListAuthorNames
// 模式）。展示性字段，查询失败记日志降级不阻塞响应（缺字段只影响
// 卡片标题回退到文件名，无行为后果——与 likedToday 的强口径不同）。
func (s *Server) fillListCosWork(ctx context.Context, items []gen.AssetSummary) {
	if len(items) == 0 {
		return
	}
	ids := make([]string, 0, len(items))
	for i := range items {
		ids = append(ids, items[i].Id.String())
	}
	rows, err := s.q.ListCosWorkForAssets(ctx, jsonString(ids))
	if err != nil {
		s.logger.Error("查询资产 COS 作品名失败", "err", err)
		return
	}
	workByAsset := make(map[string]string, len(rows))
	for _, r := range rows {
		workByAsset[r.AssetID] = r.CosWork.String
	}
	for i := range items {
		if w, ok := workByAsset[items[i].Id.String()]; ok {
			ww := w
			items[i].CosWork = &ww
		}
	}
}

// fillListLikedToday 批量装配列表条目的 likedToday（当日是否已点赞，
// 点赞按钮初始态）。口径与 PUT /assets/{assetId}/like 完全一致：likes 表
// 按 (asset_id, day) 判存在，day 是本地日历日（store.FormatDay）——批量
// 查询只是 HasLikedOnDay 的 IN 形式，不引入新口径。页大小一次查询二次
// 装配（同 fillListAuthorNames 模式）。查询失败向上返回 error（handler
// 侧 500）而非记日志降级：该字段影响客户端点赞按钮初始态，静默缺失会让
// "已赞"渲染成"未赞"、用户再点一次即被 toggle 成取消——错误状态有实际
// 行为后果，不作展示性增强降级。
func (s *Server) fillListLikedToday(ctx context.Context, items []gen.AssetSummary) error {
	if len(items) == 0 {
		return nil
	}
	ids := make([]string, 0, len(items))
	for i := range items {
		ids = append(ids, items[i].Id.String())
	}
	rows, err := s.q.ListLikedTodayForAssets(ctx, db.ListLikedTodayForAssetsParams{
		Day:          store.FormatDay(s.now()),
		AssetIdsJson: jsonString(ids),
	})
	if err != nil {
		return err
	}
	likedSet := make(map[string]struct{}, len(rows))
	for _, id := range rows {
		likedSet[id] = struct{}{}
	}
	for i := range items {
		_, liked := likedSet[items[i].Id.String()]
		items[i].LikedToday = ptr(liked)
	}
	return nil
}
