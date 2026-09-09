// assets.go：资产列表端点（GET /assets）handler。
// 职责拆分（纯文件搬家，无行为变化）：
//   - 游标/筛选归一 → assets_filters.go
//   - 列表行装配与分页后处理（含 queryListRows）→ assets_list.go
//   - 签名直链与 sqlc 标量抹平 → assets_media_url.go
//   - 列表条目批量字段装配（fillList* 族）→ assets_list_fill.go
//   - 资产详情端点（GET /assets/{assetId}）→ assets_detail.go
package httpapi

import (
	"context"
	"net/http"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store/db"
)

// GetApiV1Assets 资产列表：动态筛选 + 排序 + keyset 分页。
// 语义唯一权威是 docs/DOMAIN_RULES §3；SQL 侧的取舍见
// internal/store/queries/browse.sql 文件头。
// 超函数警戒线（>100 行）理由：oapi-codegen 生成的接口签名 + 单请求直线
// 流（参数归一→游标→SQL→装配→响应），无嵌套分支复杂度；拆段只会把
// limit/sort/cur 等一串局部状态提升为结构体在函数间传递，可读性反而下降。
// （P2-1 重构后 handler 已压回警戒线内：方向分支进 queryListRows，
// 目录/游标归一进 assets_filters，计数进 fillTotalMatched。）
func (s *Server) GetApiV1Assets(w http.ResponseWriter, r *http.Request, params gen.GetApiV1AssetsParams) {
	limit, ok := resolvePageLimit(w, params.Limit)
	if !ok {
		return
	}
	sortKey := "default"
	if params.Sort != nil {
		sortKey = string(*params.Sort)
	}
	asc := params.Order != nil && *params.Order == gen.Asc

	cur, ok := parseRequestCursor(w, params.Cursor)
	if !ok {
		return
	}
	// q（全文搜索）：语义与词法见 search.ParseQuery 与 DOMAIN_RULES §3；
	// 谓词在 browse.sql 三查询内，与全部筛选叠加生效（AND）。
	// groupByDate：AssetPage 响应结构无分组字段，日期分组标签
	//（DOMAIN_RULES §8）由客户端按 modifiedAt 折叠，服务端无动作。
	// directory（目录过滤）语义见 normalizeDirectoryParam。
	directory, ok := normalizeDirectoryParam(w, params.Directory)
	if !ok {
		return
	}
	filters := newAssetFilters(params, directory)

	// Asc/Desc 只差查询本身；行搬运与后处理见 queryListRows / buildListPage。
	rows, ok := s.queryListRows(r.Context(), w, asc, sortKey, limit, cur, filters)
	if !ok {
		return
	}
	items, lastKey, lastID, hasMore := buildListPage(s, rows, limit)

	// authorNames：列表响应的卡片作者行数据（常规∪COS），页大小一次
	// 批量查询二次装配（协议 GET /assets 描述；搜索走本查询自然获得）。
	s.fillListAuthorNames(r.Context(), items)

	// cosWork：COS 卡片标题数据源（COS 作品子目录名），同页大小批量装配。
	s.fillListCosWork(r.Context(), items)

	// likedToday：点赞按钮初始态（当日已赞判定，likes 表当日行存在性），
	// 同为页大小一次批量查询二次装配。
	if err := s.fillListLikedToday(r.Context(), items); err != nil {
		s.internalErr(w, "查询当日点赞态", err)
		return
	}

	page := gen.AssetPage{Items: &items}
	if hasMore {
		next := encodeCursor(lastKey, lastID)
		page.NextCursor = &next
	}
	// totalMatched：独立 COUNT（同筛选矩阵，不含游标/排序）。首屏才查
	//——翻页时总数不变，省一次全量计数。
	if cur.K == "" {
		if !s.fillTotalMatched(r.Context(), w, &page, filters) {
			return
		}
	}
	writeJSON(w, http.StatusOK, page)
}

// fillTotalMatched 首屏独立 COUNT（同筛选矩阵，不含游标/排序）写入
// page.TotalMatched；失败写 500 INTERNAL 并返回 false，调用方必须 return。
// 收成助手是为让 handler 保持单请求直线流，不把计数错误路径内联膨胀。
func (s *Server) fillTotalMatched(ctx context.Context, w http.ResponseWriter, page *gen.AssetPage, filters assetFilters) bool {
	var cp db.CountAssetsFilteredParams
	applyFilters(&cp, filters)
	total, err := s.q.CountAssetsFiltered(ctx, cp)
	if err != nil {
		s.logger.Error("统计资产总数失败", "err", err)
		writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
		return false
	}
	t := int(total)
	page.TotalMatched = &t
	return true
}
