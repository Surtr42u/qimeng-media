package httpapi

import (
	"database/sql"
	"errors"
	"net/http"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// PostApiV1EventsView 上报浏览事件。
//
// M1 简化：插入即 202，不做会话去重判断——去重口径（assetId+kind+
// sessionId+当日，DOMAIN_RULES §5）在 M3 统计侧统一裁决，事件流保持
// 只追加的原始记录（adr/0005：判定规则集中一处，不散落写入点）。
// seconds 仅 dwell 事件携带，open/play 落库为 NULL。
func (s *Server) PostApiV1EventsView(w http.ResponseWriter, r *http.Request) {
	var req gen.PostApiV1EventsViewJSONRequestBody
	if !decodeJSON(w, r, &req) {
		return
	}
	var seconds sql.NullInt64 // 零值 = NULL（open/play 无停留秒数）
	if req.Kind == gen.Dwell && req.Seconds != nil {
		seconds = sql.NullInt64{Int64: int64(*req.Seconds), Valid: true}
	}
	if err := s.q.InsertViewEvent(r.Context(), db.InsertViewEventParams{
		AssetID:   req.AssetId.String(),
		Kind:      string(req.Kind),
		SessionID: req.SessionId,
		StartedAt: store.FormatTimestamp(req.StartedAt),
		Seconds:   seconds,
	}); err != nil {
		s.logger.Error("写入浏览事件失败", "err", err)
		writeErr(w, http.StatusInternalServerError, "INTERNAL", "内部错误")
		return
	}
	w.WriteHeader(http.StatusAccepted)
}

// PutApiV1AssetsAssetIdLike 点赞（toggle）。
//
// DOMAIN_RULES §5：每资产每日可赞一次、次日重置（day 是本地日历日），
// likeCount 为累计值。openapi 语义是 toggle——当日已赞时本次请求即
// "取消今日赞"（删除当日行，累计数相应回退）；当日未赞则记录一次。
// 「每资产每日一次」由 (asset_id, day) 主键兜底，并发双击不会翻倍。
func (s *Server) PutApiV1AssetsAssetIdLike(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	if _, err := s.q.GetAsset(r.Context(), assetID.String()); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeErr(w, http.StatusNotFound, "NOT_FOUND", "资产不存在")
			return
		}
		s.internalErr(w, "查询资产", err)
		return
	}
	day := store.FormatDay(s.now())
	liked, err := s.q.HasLikedOnDay(r.Context(), db.HasLikedOnDayParams{
		AssetID: assetID.String(), Day: day,
	})
	if err != nil {
		s.internalErr(w, "查询当日点赞", err)
		return
	}
	if liked > 0 {
		// 取消今日赞（toggle 的另一半）
		if _, err := s.q.RemoveLikeOnDay(r.Context(), db.RemoveLikeOnDayParams{
			AssetID: assetID.String(), Day: day,
		}); err != nil {
			s.internalErr(w, "取消点赞", err)
			return
		}
	} else if err := s.q.AddLike(r.Context(), db.AddLikeParams{
		AssetID: assetID.String(), Day: day, CreatedAt: store.FormatTimestamp(s.now()),
	}); err != nil {
		s.internalErr(w, "点赞", err)
		return
	}
	count, err := s.q.CountAssetLikes(r.Context(), assetID.String())
	if err != nil {
		s.internalErr(w, "统计点赞数", err)
		return
	}
	state := gen.LikeState{LikedToday: ptr(liked == 0), LikeCount: ptr(int(count))}
	writeJSON(w, http.StatusOK, state)
}

// PutApiV1AssetsAssetIdFavorite 设置/取消收藏（布尔标记，DOMAIN_RULES §7）。
func (s *Server) PutApiV1AssetsAssetIdFavorite(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	var req gen.PutApiV1AssetsAssetIdFavoriteJSONRequestBody
	if !decodeJSON(w, r, &req) {
		return
	}
	if _, err := s.q.GetAsset(r.Context(), assetID.String()); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeErr(w, http.StatusNotFound, "NOT_FOUND", "资产不存在")
			return
		}
		s.internalErr(w, "查询资产", err)
		return
	}
	if req.Favorite {
		// ON CONFLICT DO NOTHING：重复收藏幂等
		if _, err := s.q.AddFavorite(r.Context(), db.AddFavoriteParams{
			AssetID: assetID.String(), CreatedAt: store.FormatTimestamp(s.now()),
		}); err != nil {
			s.internalErr(w, "收藏", err)
			return
		}
	} else if _, err := s.q.RemoveFavorite(r.Context(), assetID.String()); err != nil {
		s.internalErr(w, "取消收藏", err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
