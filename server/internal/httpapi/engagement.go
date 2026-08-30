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
// 会话去重（DOMAIN_RULES §5「同一会话内只计一次」）：open/play 事件先查
// ExistsViewEventOnDay（assetId+kind+sessionId+事件发生日），当日该会话
// 已有同类事件 → 直接 202 不插入（横滑切走切回不重复计）。dwell 例外：
// 停留时长每次都有效（时长是累加量而非计数，去重会丢真实停留时间），
// 逐条插入并把秒数累加进物化表。
//
// 去重窗口与物化表 day 同源：都按 started_at 的本地日历日取界（而不是
// 服务器当前时间），保证「当日已存在判定」与「聚合行落在哪一天」口径
// 自洽——客户端传昨日时间即参与昨日的去重与昨日聚合行。
//
// 事件插入与 asset_daily_stats 累加在同一事务：物化表是事件流的缓存
// （migrations/0005 表注释），半写状态会让统计口径漂移。
func (s *Server) PostApiV1EventsView(w http.ResponseWriter, r *http.Request) {
	var req gen.PostApiV1EventsViewJSONRequestBody
	if !decodeJSON(w, r, &req) {
		return
	}
	var seconds sql.NullInt64 // 零值 = NULL（open/play 无停留秒数）
	if req.Kind == gen.Dwell && req.Seconds != nil {
		seconds = sql.NullInt64{Int64: int64(*req.Seconds), Valid: true}
	}
	startedAt := store.FormatTimestamp(req.StartedAt)

	// open/play 会话去重：当日同会话已有同类事件 → 原样 202（幂等语义）
	if req.Kind == gen.Open || req.Kind == gen.Play {
		dayStart, dayEnd := localDayBoundsUTC(req.StartedAt)
		exists, err := s.q.ExistsViewEventOnDay(r.Context(), db.ExistsViewEventOnDayParams{
			AssetID:     req.AssetId.String(),
			Kind:        string(req.Kind),
			SessionID:   req.SessionId,
			StartedAt:   dayStart,
			StartedAt_2: dayEnd,
		})
		if err != nil {
			s.internalErr(w, "查询会话去重", err)
			return
		}
		if exists > 0 {
			w.WriteHeader(http.StatusAccepted)
			return
		}
	}

	// 事务：事件流（唯一真相源）+ 物化聚合表同步累加
	tx, err := s.conn.BeginTx(r.Context(), nil)
	if err != nil {
		s.internalErr(w, "开启事件事务", err)
		return
	}
	defer func() { _ = tx.Rollback() }()
	qtx := s.q.WithTx(tx)
	if err := qtx.InsertViewEvent(r.Context(), db.InsertViewEventParams{
		AssetID:   req.AssetId.String(),
		Kind:      string(req.Kind),
		SessionID: req.SessionId,
		StartedAt: startedAt,
		Seconds:   seconds,
	}); err != nil {
		s.internalErr(w, "写入浏览事件", err)
		return
	}
	var delta db.UpsertAssetDailyStatsParams
	delta.AssetID = req.AssetId.String()
	delta.Day = store.FormatDay(req.StartedAt)
	switch req.Kind {
	case gen.Open: // 图片 viewCount：当日同会话去重后 +1
		delta.ViewCount = 1
	case gen.Play: // 视频 playCount：当日同会话去重后 +1
		delta.PlayCount = 1
	default: // dwell：秒数累加（不去重，见函数头注释）
		delta.BrowseSeconds = seconds.Int64
	}
	if err := qtx.UpsertAssetDailyStats(r.Context(), delta); err != nil {
		s.internalErr(w, "累加按天统计", err)
		return
	}
	if err := tx.Commit(); err != nil {
		s.internalErr(w, "提交浏览事件", err)
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
