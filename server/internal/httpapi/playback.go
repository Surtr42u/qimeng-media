package httpapi

import (
	"database/sql"
	"net/http"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store/db"
)

// PutApiV1AssetsAssetIdProgress 上报播放进度（断点续播"最新状态"）。
//
// 与 ViewEvent 事件流的分工（migration 0006 文件头）：进度是最新状态而非
// 统计事实——每资产只保留一个最新位置值，不进 view_events、不参与
// playCount（ADR-0005 口径不变，播放计数走 POST /events/view 的 play 事件）。
// updated_at 刻意不 bump：进度是播放器状态而非内容变化。
// RowsAffected==0 → 404：assets 行删除即回收站/物理删除后（DOMAIN_RULES
// 恢复语义：库行硬删），无需先查存在性多一次往返。
func (s *Server) PutApiV1AssetsAssetIdProgress(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	var req gen.PutApiV1AssetsAssetIdProgressJSONRequestBody
	if !decodeJSON(w, r, &req) {
		return
	}
	rows, err := s.q.UpdatePlaybackProgress(r.Context(), db.UpdatePlaybackProgressParams{
		LastPositionSeconds: sql.NullFloat64{Float64: float64(req.PositionSeconds), Valid: true},
		AssetID:             assetID.String(),
	})
	if err != nil {
		s.internalErr(w, "写入播放进度", err)
		return
	}
	if rows == 0 {
		writeErr(w, http.StatusNotFound, "NOT_FOUND", "资产不存在")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
