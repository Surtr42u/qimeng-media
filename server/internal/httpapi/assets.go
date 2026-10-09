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

// 「默认」排序的协议枚举字面值（openapi GET /assets sort 枚举 default 档；
// 协议侧改动须同步此处，反之亦然）。
const sortKeyDefault = "default"

// 「文件日期」排序的协议枚举字面值（mtime 档）——「默认」在服务端别名为本档。
const sortKeyFileDate = "fileDate"

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
	sortKey := sortKeyDefault
	if params.Sort != nil {
		sortKey = string(*params.Sort)
	}
	// 「默认」排序语义 = 文件时间（DOMAIN_RULES §3，2026-09-17 晚拍板）：
	// default 档在此单点别名到 fileDate 档（mtime），不再回退入库时间——
	// 单机形态整批一次性扫描的库入库时间趋同且顺序=扫描顺序，按入库时间排
	// 会让默认视图被扫描顺序支配（同目录同类型整段聚堆、跨端表现随机）。
	// 别名收在 handler 而非 browse.sql 加 WHEN 分支：sqlc 解析器对 CASE 内
	// 新增参数行敏感（实测报错），且别名是协议语义层的事，不该进 SQL。
	if sortKey == sortKeyDefault {
		sortKey = sortKeyFileDate
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
	// 时区偏移（协议 2026-10-10 加）：dateFrom/dateTo 的本地日解释与
	// dateCounts 的日界分桶共用同一个已校验值（缺省 0 = UTC 向后兼容）。
	tzOffset, ok := parseTzOffsetMinutes(w, params)
	if !ok {
		return
	}
	filters := newAssetFilters(params, directory, tzOffset)

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
		// dateCounts：相册页日期组头精确计数（协议 dateCounts=true）。同为首屏
		// 才查——分桶是筛选态的函数，翻页不改变它；客户端拿它替换「已加载条数」。
		if params.DateCounts != nil && *params.DateCounts {
			if !s.fillDateCounts(r.Context(), w, &page, filters, tzModifier(tzOffset)) {
				return
			}
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

// fillDateCounts 首屏按本地日聚合的文件计数（协议 dateCounts=true）写入
// page.DateCounts（日期降序）；失败写 500 INTERNAL 并返回 false，调用方
// 必须 return。与 fillTotalMatched 同形：筛选矩阵同源（applyFilters），
// 差别只在 SQL 侧把 COUNT(*) 换成按 date(mtime, tz_modifier) 分桶——
// 客户端据此展示「今天 N 项」的真实总数，不再拿已加载条数冒充。
// modifier 由 tzModifier(tzOffset) 构造，与 dateFrom/dateTo 的日界同源。
func (s *Server) fillDateCounts(
	ctx context.Context,
	w http.ResponseWriter,
	page *gen.AssetPage,
	filters assetFilters,
	modifier string,
) bool {
	var cp db.CountAssetsByLocalDayParams
	applyFilters(&cp, filters)
	cp.TzModifier = modifier
	rows, err := s.q.CountAssetsByLocalDay(ctx, cp)
	if err != nil {
		s.internalErr(w, "统计日期分组计数", err)
		return false
	}
	buckets := make([]gen.DateCountBucket, 0, len(rows))
	for _, row := range rows {
		day := localDayString(row.LocalDay)
		if day == "" {
			// date() 解析不出日界的行（NULL/非法 mtime）：不进任何桶，客户端
			// 「未知日期」组继续用已加载条数兜底（该组量级极小且恒末位）。
			continue
		}
		buckets = append(buckets, gen.DateCountBucket{Date: day, FileCount: int(row.FileCount)})
	}
	page.DateCounts = &buckets
	return true
}
