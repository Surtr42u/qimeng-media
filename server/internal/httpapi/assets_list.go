// assets_list.go：资产列表端点（GET /assets）的行装配与分页后处理。
// Asc/Desc 两胞胎 Row 抹平成 listRowView，截断探测/摘要装配/游标锚点
// 收敛在 buildListPage；handler 只做编排，SQL 方向分支收敛在 queryListRows。
package httpapi

import (
	"context"
	"database/sql"
	"net/http"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store/db"
)

// ---- 列表行装配（Asc/Desc 去重） ----

// listRowView 抹平 sqlc Asc/Desc 两胞胎 Row（同一 SQL 按方向生成两个
// 字段同名同型的独立结构体），让截断探测与行→AssetSummary 装配只写
// 一份（代码卫生：禁止复制粘贴）。只搬列表装配用到的字段；SortKey 是
// sqlc 对动态排序列生成的 interface{} 列（sort_key 恒为 TEXT 非空，
// 见 toString）。
type listRowView struct {
	AssetID    string
	FileName   string
	MediaType  string
	SizeBytes  int64
	Mtime      string
	CreatedAt  string
	Source     sql.NullString
	IsFavorite bool
	LikeCount  int64
	DurationMs sql.NullInt64
	SortKey    any
}

// listRowViewFromAsc 两胞胎 Row 的字段搬运，sqlc 固有成本：Asc/Desc 两
// 结构体字段同名同型却无法用泛型收敛（接口无法约束结构体字段），只能
// 各写一份逐字段复制。
func listRowViewFromAsc(r db.ListAssetsFilteredAscRow) listRowView {
	return listRowView{AssetID: r.AssetID, FileName: r.FileName, MediaType: r.MediaType,
		SizeBytes: r.SizeBytes, Mtime: r.Mtime, CreatedAt: r.CreatedAt, Source: r.Source,
		IsFavorite: r.IsFavorite, LikeCount: r.LikeCount, DurationMs: r.DurationMs, SortKey: r.SortKey}
}

// listRowViewFromDesc 同 listRowViewFromAsc，Desc 侧。
func listRowViewFromDesc(r db.ListAssetsFilteredDescRow) listRowView {
	return listRowView{AssetID: r.AssetID, FileName: r.FileName, MediaType: r.MediaType,
		SizeBytes: r.SizeBytes, Mtime: r.Mtime, CreatedAt: r.CreatedAt, Source: r.Source,
		IsFavorite: r.IsFavorite, LikeCount: r.LikeCount, DurationMs: r.DurationMs, SortKey: r.SortKey}
}

// queryListRows 收敛 Asc/Desc 两分支的「组参数 + 查询 + 行搬运」——两分支
// 的语义差异全在 SQL 侧，截断探测与 AssetSummary 装配在 buildListPage。
// 查询失败写 500 INTERNAL 并返回 ok=false，调用方必须立即 return。
// RowLimit 多取 1 行：用来证明还有下一页，不进本页。
func (s *Server) queryListRows(ctx context.Context, w http.ResponseWriter, asc bool,
	sortKey string, limit int, cur pageCursor, filters assetFilters) ([]listRowView, bool) {
	if asc {
		p := db.ListAssetsFilteredAscParams{
			Sort: sortKey, RowLimit: int64(limit) + 1, // 多取 1 行探测下一页
			CursorKey: nullStr(cur.K),
			CursorID:  sql.NullString{String: cur.I, Valid: cur.K != ""},
		}
		applyFilters(&p, filters)
		raw, err := s.q.ListAssetsFilteredAsc(ctx, p)
		if err != nil {
			s.logger.Error("查询资产列表失败", "err", err)
			writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
			return nil, false
		}
		rows := make([]listRowView, len(raw))
		for i := range raw {
			rows[i] = listRowViewFromAsc(raw[i])
		}
		return rows, true
	}
	p := db.ListAssetsFilteredDescParams{
		Sort: sortKey, RowLimit: int64(limit) + 1, // 多取 1 行探测下一页
		CursorKey: nullStr(cur.K),
		CursorID:  sql.NullString{String: cur.I, Valid: cur.K != ""},
	}
	applyFilters(&p, filters)
	raw, err := s.q.ListAssetsFilteredDesc(ctx, p)
	if err != nil {
		s.logger.Error("查询资产列表失败", "err", err)
		writeErr(w, http.StatusInternalServerError, codeInternal, "内部错误")
		return nil, false
	}
	rows := make([]listRowView, len(raw))
	for i := range raw {
		rows[i] = listRowViewFromDesc(raw[i])
	}
	return rows, true
}

// buildListPage 收敛"多取 1 行探测 hasMore + 截断 + 行→AssetSummary
// 装配 + 下一页游标锚点"——Asc/Desc 两分支只差查询本身，这段后处理
// 完全对称。lastKey/lastID 是本页最后一行在当前排序下的锚点（keyset
// 分页的全部信息）。
func buildListPage(s *Server, rows []listRowView, limit int) (items []gen.AssetSummary, lastKey, lastID string, hasMore bool) {
	hasMore = len(rows) > limit
	if hasMore {
		rows = rows[:limit] // 多取的那 1 行只用来证明还有下一页，不进本页
	}
	items = make([]gen.AssetSummary, 0, len(rows))
	for i := range rows {
		row := &rows[i]
		item := buildSummary(s, row.AssetID, row.FileName, row.MediaType,
			row.SizeBytes, row.Mtime, row.CreatedAt, row.Source, row.IsFavorite, row.LikeCount, nil, nil)
		if row.DurationMs.Valid {
			item.DurationMs = ptr(row.DurationMs.Int64) // 卡片时长角标数据（仅视频有值）
		}
		items = append(items, item)
		lastKey, lastID = toString(row.SortKey), row.AssetID
	}
	return items, lastKey, lastID, hasMore
}

// buildSummary 组装 AssetSummary（含签名缩略图直链，网格 md 档）。
// viewCount/playCount 是 AssetSummary 的新增可选字段（openapi）：
// 仅排行榜等需要展示计数的端点传实测值，浏览列表传 nil（字段省略，
// 保持列表查询轻量——计数聚合不在列表 SQL 里）；detail 端点有自己的
// 统计聚合路径，也传 nil。
func buildSummary(s *Server, assetID, fileName, mediaType string, sizeBytes int64,
	mtime, createdAt string, source sql.NullString, isFavorite bool, likeCount int64,
	viewCount, playCount *int) gen.AssetSummary {
	id := uuidOrNil(assetID)
	mt := gen.MediaType(mediaType)
	mod := parseStoreTime(mtime)
	added := parseStoreTime(createdAt)
	src := displaySource(source)
	fav := isFavorite
	like := int(likeCount)
	thumb := s.thumbURL(assetID, "md")
	return gen.AssetSummary{
		Id:         &id,
		FileName:   &fileName,
		MediaType:  &mt,
		SizeBytes:  &sizeBytes,
		ModifiedAt: &mod,
		AddedAt:    &added,
		Source:     &src,
		IsFavorite: &fav,
		LikeCount:  &like,
		ThumbUrl:   &thumb,
		ViewCount:  viewCount,
		PlayCount:  playCount,
	}
}
