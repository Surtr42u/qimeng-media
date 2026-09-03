// history.go：观看历史端点（M3，DOMAIN_RULES §8 浏览面）。
//
// 历史 = 每资产最近一次 kind='open' 的 ViewEvent 时间倒序，每资产一条
// （GROUP BY asset_id 取 MAX 时间）；已删资产（事件流无 FK，ADR-0005）
// 经 FROM assets 锚定自然排除；默认排除 COS 作者关联文件（与 /assets
// 同口径，DOMAIN_RULES §6），includeCos=true 重新包含。
// 分页 keyset (last_viewed_at, asset_id)，游标形态复用 assets.go 的
// encodeCursor/decodeCursor（k/i 即上一页最后一行的时间串与资产 ID，
// 与前页可稳定续读）。
package httpapi

import (
	"database/sql"
	"net/http"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// GetApiV1History 观看历史分页列表（最近浏览在前）。
func (s *Server) GetApiV1History(w http.ResponseWriter, r *http.Request, params gen.GetApiV1HistoryParams) {
	limit, ok := resolvePageLimit(w, params.Limit)
	if !ok {
		return
	}
	includeCos := int64(0)
	if params.IncludeCos != nil && *params.IncludeCos {
		includeCos = 1
	}
	var cur pageCursor
	if params.Cursor != nil && *params.Cursor != "" {
		c, err := decodeCursor(*params.Cursor)
		if err != nil {
			writeErr(w, http.StatusBadRequest, "INVALID_CURSOR", "分页游标不合法")
			return
		}
		cur = c
	}
	rows, err := s.q.ListHistory(r.Context(), db.ListHistoryParams{
		RowLimit:   int64(limit) + 1, // 多取 1 行探测下一页
		CursorKey:  nullStr(cur.K),
		CursorID:   sql.NullString{String: cur.I, Valid: cur.K != ""},
		IncludeCos: includeCos,
	})
	if err != nil {
		s.logger.Error("查询观看历史失败", "err", err)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	hasMore := len(rows) > limit
	if hasMore {
		rows = rows[:limit]
	}
	items := make([]gen.HistoryItem, 0, len(rows))
	var lastKey, lastID string
	for i := range rows {
		row := &rows[i]
		base := buildSummary(s, row.AssetID, row.FileName, row.MediaType,
			row.SizeBytes, row.Mtime, row.CreatedAt, row.Source, row.IsFavorite,
			row.LikeCount, nil, nil)
		lv := historyMillis(row.LastViewedAt)
		// 已看完徽标/时长徽标数据源（协议 HistoryItem = AssetSummary allOf 含
		// durationMs/lastPositionSeconds）：与资产列表同列，图片/未播过为 null。
		var dur *int64
		if row.DurationMs.Valid {
			dur = ptr(row.DurationMs.Int64)
		}
		var pos *float32
		if row.LastPositionSeconds.Valid {
			f := float32(row.LastPositionSeconds.Float64)
			pos = &f
		}
		items = append(items, gen.HistoryItem{
			// AssetSummary 基础字段（openapi HistoryItem = AssetSummary allOf；
			// 与 GetApiV1AssetsAssetId 的 base 展开同模式）。
			AddedAt:             base.AddedAt,
			DurationMs:          dur,
			FileName:            base.FileName,
			Id:                  base.Id,
			IsFavorite:          base.IsFavorite,
			LastPositionSeconds: pos,
			LikeCount:           base.LikeCount,
			MediaType:           base.MediaType,
			ModifiedAt:          base.ModifiedAt,
			SizeBytes:           base.SizeBytes,
			Source:              base.Source,
			ThumbUrl:            base.ThumbUrl,
			// 扩展字段：最近一次 open 的 started_at（Unix 毫秒）。
			LastViewedAt: ptr(lv),
		})
		lastKey, lastID = toString(row.LastViewedAt), row.AssetID
	}
	page := gen.HistoryPage{Items: &items}
	if hasMore {
		next := encodeCursor(lastKey, lastID)
		page.NextCursor = &next
	}
	writeJSON(w, http.StatusOK, page)
}

// historyMillis 把历史行的 last_viewed_at（store.TimestampLayout 时间串，
// 语义见 migrations/0001 文件头）转为 Unix 毫秒时间戳；脏数据（解析失败）
// 返回 0 兜底，不炸接口（与 parseStoreTime 同风格）。
func historyMillis(v any) int64 {
	if s, ok := v.(string); ok && s != "" {
		if t, err := time.Parse(store.TimestampLayout, s); err == nil {
			return t.UnixMilli()
		}
	}
	return 0
}
