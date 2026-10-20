package httpapi

import (
	"context"
	"database/sql"
	"net/http"
	"reflect"

	"qimeng-media/server/internal/store/db"
)

// canUseFileDatePage 是窄路径的白名单：只支持库与 COS 分区。未列出的
// 任一有效字段（包括未来新加的字段）都会使结构不等而回退通用查询，
// 不允许为了命中索引静默漏筛选。TagMode 在没有 TagIdsJson 时没有效力。
func canUseFileDatePage(filters assetFilters) bool {
	allowed := assetFilters{
		LibraryID: filters.LibraryID, IncludeCos: filters.IncludeCos,
		CosOnly: filters.CosOnly, TagMode: filters.TagMode,
	}
	return reflect.DeepEqual(filters, allowed)
}

// queryFileDatePage 单条 SQL 内先按 (mtime, asset_id) 索引取页、再装配
// 收藏/点赞。首屏与游标拆为互斥 UNION 分支，深页直接 seek，不用日期哨兵。
// 参数/行的转换只适用于生成的同构 Asc/Desc 类型，不复制业务筛选规则。
func (s *Server) queryFileDatePage(ctx context.Context, w http.ResponseWriter, asc bool,
	limit int, cur pageCursor, filters assetFilters) ([]listRowView, bool) {
	params := db.ListAssetsFileDateDescParams{
		RowLimit: int64(limit) + 1, CursorKey: nullStr(cur.K),
		CursorID:  sql.NullString{String: cur.I, Valid: cur.K != ""},
		LibraryID: filters.LibraryID, IncludeCos: filters.IncludeCos, CosOnly: filters.CosOnly,
	}
	var raw []db.ListAssetsFileDateDescRow
	var err error
	if asc {
		var ascending []db.ListAssetsFileDateAscRow
		ascending, err = s.q.ListAssetsFileDateAsc(ctx, db.ListAssetsFileDateAscParams(params))
		raw = make([]db.ListAssetsFileDateDescRow, len(ascending))
		for i, row := range ascending {
			raw[i] = db.ListAssetsFileDateDescRow(row)
		}
	} else {
		raw, err = s.q.ListAssetsFileDateDesc(ctx, params)
	}
	if err != nil {
		s.internalErr(w, "查询日期分页", err)
		return nil, false
	}
	rows := make([]listRowView, len(raw))
	for i, row := range raw {
		rows[i] = listRowView{
			AssetID: row.AssetID, FileName: row.FileName, MediaType: row.MediaType,
			SizeBytes: row.SizeBytes, Mtime: row.Mtime, CreatedAt: row.CreatedAt,
			Source: row.Source, IsFavorite: row.IsFavorite, LikeCount: row.LikeCount,
			DurationMs: row.DurationMs, SortKey: row.Mtime,
		}
	}
	return rows, true
}
