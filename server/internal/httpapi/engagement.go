package httpapi

import (
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"strings"

	"github.com/google/uuid"

	"qimeng-media/server/internal/events"
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
// 逐条插入并把秒数累加进物化表。R10（审计 2026-09-20）：先查后插存在
// TOCTOU 竞态窗口（并发双击双双通过存在性检查 → 双计），migration 0014
// 部分唯一索引 (asset,kind,session,day) WHERE kind IN (open,play) 做数据
// 库层兜底——竞态输家的插入静默 0 行，走下方 inserted==0 路径不计数；
// 先查保留为快路径（省一次写事务）。
//
// 客户端幂等（任务L L5「本地优先合并」，拍板 #7）：ViewEventReport.clientEventId
// 是客户端在事件产生时生成、随本地暂存持久的 UUID，重试/补传携带同一 id。
// 写入走 InsertViewEventIdempotent（唯一索引 + ON CONFLICT DO NOTHING，
// migration 0010）：同 id 重复提交 → RowsAffected==0 → 原样 202 但**不重复
// 入库、不重复计数**（dwell 秒数不重复累加、open/play 不重复计数）——这是
// 客户端「发送成功（2xx）才删本地暂存」的安全前提。幂等判定在会话去重之后、
// 物化累加之前：open/play 的重发大概率已被会话去重挡下，漏网者（如跨会话
// 误用同 id、dwell 重传）由唯一索引兜底。
//
// 旧格式请求（未携带 clientEventId）行为 = **放行**：生成类型是 uuid.UUID，
// 缺失字段解码为零值 uuid.Nil，按 NULL 入库照常计数——SQLite 唯一索引对
// NULL 不做唯一判定，存量行/导入回放（DOMAIN_RULES §10）/旧客户端互不冲突，
// 幂等语义仅对携带 id 的事件生效。该口径由 TestEngagementClientEventIdempotency
// 锁定。
//
// 去重窗口与物化表 day 同源：都按 started_at 的本地日历日取界（而不是
// 服务器当前时间），保证「当日已存在判定」与「聚合行落在哪一天」口径
// 自洽——客户端传昨日时间即参与昨日的去重与昨日聚合行。
//
// 事件插入与 asset_daily_stats 累加在同一事务：物化表是事件流的缓存
// （migrations/0005 表注释），半写状态会让统计口径漂移。
//
// 已删资产的迟到事件：事件入库、统计跳过、原样 202（与 rebuild 的 live
// 过滤同口径，ADR-0005）——实现见下方 upsert 错误分支（2026-09-22）。
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
	// 幂等键：uuid.Nil（未携带/旧格式）→ NULL 放行（见函数头注释），其余原样入库
	clientEventID := sql.NullString{String: req.ClientEventId.String(), Valid: req.ClientEventId != uuid.Nil}

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
	inserted, err := qtx.InsertViewEventIdempotent(r.Context(), db.InsertViewEventIdempotentParams{
		AssetID:       req.AssetId.String(),
		Kind:          string(req.Kind),
		SessionID:     req.SessionId,
		StartedAt:     startedAt,
		Seconds:       seconds,
		ClientEventID: clientEventID,
		// R10（审计 2026-09-20）：本地日历日，与物化表 delta.Day 同源同值；
		// 会话去重部分唯一索引（migration 0014）的判重键。导入回放侧
		// 显式写 NULL（见 import_replay.go）。
		Day: sql.NullString{String: store.FormatDay(req.StartedAt), Valid: true},
	})
	if err != nil {
		s.internalErr(w, "写入浏览事件", err)
		return
	}
	if inserted == 0 {
		// 0 行写入 = 幂等/去重命中，原样 202 但不重复计数——事务内零写入，
		// 提交即空转；跳过物化累加（dwell 不重加秒、open/play 不重计）。
		// 两条命中路径（migration 0010 client_event_id / 0014 会话日索引
		// TOCTOU 兜底）响应语义一致：重发与竞态输家都拿到 202。
		if err := tx.Commit(); err != nil {
			s.internalErr(w, "提交浏览事件", err)
			return
		}
		w.WriteHeader(http.StatusAccepted)
		return
	}
	var delta db.UpsertAssetDailyStatsParams
	delta.AssetID = req.AssetId.String()
	// 与插入侧 day 同一取值（0014 去重键与物化日同源，一个口径两处使用）
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
		// 已删资产的迟到事件（换库/重建后旧客户端补传的典型场景）：事件流
		// 是真相源，孤儿事件照常入库（ADR-0005：view_events 无外键）；物化表
		// 只收存活资产，跳过累加并原样 202——与 RebuildAssetDailyStatsFromEvents
		// 的 live 过滤同口径。返回 202 而非 500 是客户端「2xx 才删本地暂存」
		// 约定的安全前提：500 会让离线队列对一条孤儿事件无限重试
		//（2026-09-22 fnOS 虚拟机彩排实测：旧标签页重放 44 次）。
		// 错误判定沿用 libraries.go 的约束错误字符串匹配惯例（sqlc 查询层
		// 不区分约束种类，驱动错误文本在 modernc sqlite 稳定）。
		if strings.Contains(err.Error(), "FOREIGN KEY constraint failed") {
			if err := tx.Commit(); err != nil {
				s.internalErr(w, "提交浏览事件", err)
				return
			}
			slog.Warn("浏览事件资产已删除：事件入库、统计跳过",
				"assetId", req.AssetId.String(), "kind", string(req.Kind))
			w.WriteHeader(http.StatusAccepted)
			return
		}
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
// 写成功（含竞态输家的幂等吸收）后广播 like.changed（ADR-0029），不发
// library.changed、不动修订号（ADR-0026 语义边界，见下方发布点注释）。
func (s *Server) PutApiV1AssetsAssetIdLike(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	if _, err := s.q.GetAsset(r.Context(), assetID.String()); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeErr(w, http.StatusNotFound, codeNotFound, "资产不存在")
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
	} else if _, err := s.q.AddLikeOnDayIdempotent(r.Context(), db.AddLikeOnDayIdempotentParams{
		AssetID: assetID.String(), Day: day, CreatedAt: store.FormatTimestamp(s.now()),
	}); err != nil {
		s.internalErr(w, "点赞", err)
		return
	}
	// R10（审计 2026-09-20）：AddLikeOnDayIdempotent 的 0 行返回值无需在此
	// 分支——它对应「并发双击竞态输家」（双方都通过 HasLikedOnDay=0 后，
	// 输家撞 (asset_id, day) 主键被静默吸收）。旧行为此处 500；现在输家
	// 自然落到下方统一响应：liked 变量来自写前读=0，LikedToday=liked==0
	// 恰为 true，likeCount 重新计数即含竞态赢家的那一行。
	// likeCount 是推荐打分输入（§1.1 likeScore），点赞/取消后推荐缓存失效
	s.invalidateRecommendCache()
	// 点赞/取消只改行为数据、不改资产集合：广播 like.changed 让其它端即时
	// 刷新（ADR-0029，跨端数据新鲜度收敛第一半）。刻意不发 library.changed
	// ——修订号订阅只挂在该事件上，由此保证 favorite/like 永不 bump 修订号
	//（ADR-0026 语义边界：revision 只保证资产集合面）。发布失败不改变本
	// 请求的成功语义：丢事件的最坏后果回到各端 TTL 兜底重拉（弱失败方向）。
	if err := s.bus.Publish(events.Event{
		Topic:   events.TopicLikeChanged,
		Payload: events.EngagementChangedEvent{AssetID: assetID.String()},
	}); err != nil {
		s.logger.Warn("广播 like.changed 失败", "err", err)
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
// 写成功（含重复收藏的幂等 no-op）后广播 favorite.changed（ADR-0029），
// 不发 library.changed、不动修订号（ADR-0026 语义边界，见下方发布点注释）。
func (s *Server) PutApiV1AssetsAssetIdFavorite(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	var req gen.PutApiV1AssetsAssetIdFavoriteJSONRequestBody
	if !decodeJSON(w, r, &req) {
		return
	}
	if _, err := s.q.GetAsset(r.Context(), assetID.String()); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeErr(w, http.StatusNotFound, codeNotFound, "资产不存在")
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
	// is_favorite 是推荐输入行字段（recommend.sql），收藏变更后推荐缓存失效
	s.invalidateRecommendCache()
	// 收藏/取消广播 favorite.changed（与 like.changed 同构，ADR-0029）：
	// 只带资产 id 的变更信号，不发 library.changed、不动修订号（理由同上）；
	// 重复收藏的幂等 no-op（ON CONFLICT 0 行）也照发——消费方多刷一次幂等
	// 无害，省掉行数判定让发布点与写成功点一一对应更可审计。
	if err := s.bus.Publish(events.Event{
		Topic:   events.TopicFavoriteChanged,
		Payload: events.EngagementChangedEvent{AssetID: assetID.String()},
	}); err != nil {
		s.logger.Warn("广播 favorite.changed 失败", "err", err)
	}
	w.WriteHeader(http.StatusNoContent)
}
