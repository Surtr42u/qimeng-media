package httpapi

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net/http"

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
// 已删资产的迟到事件：事件入库、统计跳过、202 outcome=unmatched（与 rebuild
// 的 live 过滤同口径，ADR-0005）——实现见下方 upsert 错误分支（2026-09-22）。
//
// 跨库兜底重映射（ADR-0037，2026-10-11）：本地优先队列的事件可能被投递到
// 与产生时不同的服务器（App「本机模式」→「NAS/PC」切换后同步），事件
// assetId 属于源库、目标库不存在——旧行为事件入库但物化被外键拒绝，所有
// JOIN assets 的口径（趋势/榜单/作者标签/App 统计页）都不显示，只有
// overview.totalViews（直接数事件流）计入。修复：assetId 未命中且请求携带
// fileName 时按文件名匹配本库资产（唯一命中，或经 relPath 精确等值消歧后
// 唯一）→ 事件记入该资产并正常物化计统计（跨库合并，202 outcome=remapped）；
// 零命中/仍歧义/未携带 → 维持「已删资产迟到事件」口径。歧义绝不猜测，
// 宁少勿错——与 §10 备份导入的「同名多命中取最早」宽松兜底刻意不同：那是
// legacy 批量通道的历史约束，实时通道从严。会话去重与幂等键口径不因重映射
// 改变（去重键=重映射后的目标资产）。全部 202 出口都带响应体
// EventsViewAccepted.outcome（旧客户端忽略响应体，行为不变）。
//
// 超函数警戒线（>100 行）理由：open/play/dwell 三类事件共用一个事务
// 骨架（事件流+物化表原子性，见上），会话去重、幂等命中、已删资产迟到
// 事件三条 202 短路径都内嵌在事务直线流里，拆子函数需把 tx/req/delta
// 上下文传来传去；分支语义已由本头注释逐条承担。ADR-0037 的兜底重映射
// 解析已拆出 resolveViewEventTarget（只读），但解析结果贯穿去重/插入/物化
// 三个写出口、四个 202 出口各带 outcome，仍留在事务直线流内（再拆需把
// targetID/outcome 上下文继续外传，收益为负）。
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

	// 跨库兜底重映射（ADR-0037）：assetId 未命中本库时按 fileName 解析目标
	// 资产（只读三步，见 resolveViewEventTarget）。解析出的目标 id 贯穿下方
	// 全部写路径（会话去重/幂等插入/物化累加同用），去重键 = 目标资产。
	assetID, remapped, err := s.resolveViewEventTarget(r.Context(), &req)
	if err != nil {
		s.internalErr(w, "解析浏览事件目标资产", err)
		return
	}

	// open/play 会话去重：当日同会话已有同类事件 → 202 outcome=ok（幂等语义；
	// 未新增计数，不虚报 remapped）
	if req.Kind == gen.Open || req.Kind == gen.Play {
		dayStart, dayEnd := localDayBoundsUTC(req.StartedAt)
		exists, err := s.q.ExistsViewEventOnDay(r.Context(), db.ExistsViewEventOnDayParams{
			AssetID:     assetID,
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
			writeViewAccepted(w, gen.Ok)
			return
		}
	}

	// 事务：事件流（唯一真相源）+ 物化聚合表同步累加
	tx, err := s.conn.BeginTx(r.Context(), nil)
	if err != nil {
		s.internalErr(w, "开启事件事务", err)
		return
	}
	defer func() { s.rollbackTx(r.Context(), "记录浏览事件", tx) }()
	qtx := s.q.WithTx(tx)
	inserted, err := qtx.InsertViewEventIdempotent(r.Context(), db.InsertViewEventIdempotentParams{
		AssetID:       assetID,
		Kind:          string(req.Kind),
		SessionID:     req.SessionId,
		StartedAt:     startedAt,
		Seconds:       seconds,
		ClientEventID: clientEventID,
		// R10（审计 2026-09-20）：本地日历日，与物化表 delta.Day 同源同值；
		// 会话去重部分唯一索引（migration 0014）的判重键。便携导入按原始
		// 事件透传day，已入库历史的NULL继续保留（portablebackup/portable_backup.go）。
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
		// TOCTOU 兜底）响应语义一致：重发与竞态输家都拿到 202 outcome=ok。
		if err := tx.Commit(); err != nil {
			s.internalErr(w, "提交浏览事件", err)
			return
		}
		writeViewAccepted(w, gen.Ok)
		return
	}
	var delta db.UpsertAssetDailyStatsParams
	delta.AssetID = assetID
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
		// 资产不存在（已删资产的迟到事件，或 ADR-0037 重映射未命中的孤儿）：
		// 事件流是真相源，孤儿事件照常入库（ADR-0005：view_events 无外键）；
		// 物化表只收存活资产，跳过累加并 202 outcome=unmatched——与
		// RebuildAssetDailyStatsFromEvents 的 live 过滤同口径。返回 202 而非
		// 500 是客户端「2xx 才删本地暂存」约定的安全前提：500 会让离线队列
		// 对一条孤儿事件无限重试（2026-09-22 fnOS 虚拟机彩排实测：旧标签页
		// 重放 44 次）。错误判定走 isSQLiteConstraint 的错误码分类
		//（errors.go 单一来源；原字符串匹配惯例已于 2026-10 治理批收敛，
		// sqlc 查询层不区分约束种类、由驱动错误码承载）。
		if isSQLiteConstraint(err, sqliteCodeConstraintForeignKey) {
			if err := tx.Commit(); err != nil {
				s.internalErr(w, "提交浏览事件", err)
				return
			}
			slog.Warn("浏览事件未计入统计：资产不存在或未匹配到本库（事件入库、统计跳过）",
				"assetId", req.AssetId.String(), "fileName", stringValue(req.FileName),
				"kind", string(req.Kind))
			writeViewAccepted(w, gen.Unmatched)
			return
		}
		s.internalErr(w, "累加按天统计", err)
		return
	}
	if err := tx.Commit(); err != nil {
		s.internalErr(w, "提交浏览事件", err)
		return
	}
	// 计入了就报计入类：重映射成功报 remapped（跨库合并发生），其余成功报
	// ok；只在未计入（上方 FK 失败分支）时报 unmatched。幂等/去重命中在
	// 插入前就已返回，不会走到这里虚报 remapped。
	outcome := gen.Ok
	if remapped {
		outcome = gen.Remapped
		slog.Info("浏览事件兜底重映射", "assetId", req.AssetId.String(),
			"fileName", stringValue(req.FileName), "localAssetId", assetID)
	}
	writeViewAccepted(w, outcome)
}

// resolveViewEventTarget 解析浏览事件应记入的目标资产 id（ADR-0037 跨库兜底
// 重映射的只读第一步）。返回 (targetID, remapped, err)：
//   - assetId 命中本库 → (原 id, false)——带 fileName 也不重映射；
//   - 未命中且携带非空 fileName → 按文件名精确匹配：
//     唯一命中，或（多命中时）经 relPath 精确等值消歧后唯一 → (命中的本库 id, true)；
//     零命中/仍歧义 → (原 id, false)（走上层「已删资产迟到事件」口径）；
//   - 未命中且无 fileName → (原 id, false)。
//
// 歧义绝不猜测、宁少勿错：与 §10 备份导入的「同名多命中取最早入库行」宽松
// 兜底刻意不同（那是 legacy 批量通道的历史约束，实时通道从严）。file_name
// 无索引——ListAssetIDsByFileName 的全表扫描只在「assetId 未命中」的罕见
// 路径发生（跨库切换后的首轮补传），属可接受成本，不为它单独加索引。
func (s *Server) resolveViewEventTarget(ctx context.Context, req *gen.PostApiV1EventsViewJSONRequestBody) (string, bool, error) {
	assetID := req.AssetId.String()
	exists, err := s.q.AssetExists(ctx, assetID)
	if err != nil {
		return assetID, false, err
	}
	if exists > 0 || req.FileName == nil || *req.FileName == "" {
		return assetID, false, nil
	}
	rows, err := s.q.ListAssetIDsByFileName(ctx, *req.FileName)
	if err != nil {
		return assetID, false, err
	}
	matched := rows
	if len(matched) > 1 && req.RelPath != nil && *req.RelPath != "" {
		filtered := make([]db.ListAssetIDsByFileNameRow, 0, len(matched))
		for _, row := range matched {
			if row.RelPath == *req.RelPath {
				filtered = append(filtered, row)
			}
		}
		matched = filtered
	}
	if len(matched) == 1 {
		return matched[0].AssetID, true, nil
	}
	return assetID, false, nil
}

// writeViewAccepted 写浏览事件受理响应（202 + EventsViewAccepted body，
// ADR-0037）：所有 202 出口统一带 outcome，旧客户端忽略响应体、行为不变。
func writeViewAccepted(w http.ResponseWriter, outcome gen.EventsViewAcceptedOutcome) {
	writeJSON(w, http.StatusAccepted, gen.EventsViewAccepted{Outcome: &outcome})
}

// stringValue 解引用可空字符串（slog 字段用；nil → 空串，避免 nil 解引用）。
func stringValue(s *string) string {
	if s == nil {
		return ""
	}
	return *s
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
