// recommendations.go：推荐流协议适配；输入/顺序快照/分页装载由 recommendfeed 编排。
package httpapi

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/recommend"
	"qimeng-media/server/internal/recommendfeed"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// GetApiV1Recommendations 推荐流：十维自适应加权评分 + 同分桶打散 +
// 视频图片混合（参数语义见 openapi：seed 0=稳定序 / >0=刷新打散）。
func (s *Server) GetApiV1Recommendations(w http.ResponseWriter, r *http.Request, params gen.GetApiV1RecommendationsParams) {
	limit, ok := resolvePageLimit(w, params.Limit)
	if !ok {
		return
	}
	offset, ok := resolvePageOffset(w, params.Offset)
	if !ok {
		return
	}
	seed := 0
	if params.Seed != nil {
		seed = int(*params.Seed)
	}
	mediaType := sql.NullString{}
	if params.MediaType != nil {
		mediaType = sql.NullString{String: string(*params.MediaType), Valid: true}
	}
	// COS 推荐模式（协议 cosOnly，旧版「COS 推荐模式」语义）：恒传 0/1——
	// recommend.sql 的双分支谓词依赖两值逻辑，NULL 会让两侧分支同时不成立、
	// 整库被排除（browse.sql 三态开关注释同因）。
	cosOnly := int64(0)
	if params.CosOnly != nil && *params.CosOnly {
		cosOnly = 1
	}

	// 当日展示计数的"日"（本地时区日界，0001「日」字段约定）；
	// 一次调用内统一一个日界，跨零点请求不漂移。
	day := store.FormatDay(s.now())

	// 推荐偏好快照：无论命中缓存与否都要装载——权重变化即换键（缓存键维度）。
	prefs := s.recommendPrefsFromSettings(r.Context())

	// 共用顺序快照，页摘要现读；只有返回给客户端的资产写展示计数。
	body, ids, next, err := s.computeRecommendPage(r.Context(), day, seed, mediaType, cosOnly, offset, limit, prefs)
	if err != nil {
		s.internalErr(w, "推荐流计算失败", err)
		return
	}
	s.recordDailyShownBatch(r.Context(), ids, day)
	writeNextOffset(w, next)
	writeJSON(w, http.StatusOK, body)
}

// computeRecommendPage 仅调用推荐编排并适配协议；当前页元数据不缓存。
// 顺序快照不含分页窗口，连续页共享输入装载和评分，展示计数仍由请求收尾写入。
func (s *Server) computeRecommendPage(ctx context.Context, day string, seed int, mediaType sql.NullString, cosOnly int64, offset, limit int, prefs *recommend.Weights) ([]gen.AssetSummary, []string, *int, error) {
	rows, next, err := s.recommendCache.order.LoadPage(ctx, s.q, recommendfeed.Key{
		Revision: s.recommendCache.revision(), Day: day, Seed: seed,
		MediaType: mediaType.String, CosOnly: cosOnly == 1, Prefs: recommendPrefsFingerprint(prefs),
	}, prefs, s.now(), offset, limit)
	if err != nil {
		return nil, nil, nil, err
	}
	out := make([]gen.AssetSummary, 0, len(rows))
	ids := make([]string, 0, len(rows))
	for _, row := range rows {
		item := buildSummary(s, row.AssetID, row.FileName, row.MediaType,
			row.SizeBytes, row.Mtime, row.CreatedAt, row.Source, row.IsFavorite, row.LikeCount, nil, nil)
		if row.DurationMs.Valid {
			item.DurationMs = ptr(row.DurationMs.Int64)
		}
		out = append(out, item)
		ids = append(ids, row.AssetID)
	}
	s.fillListAuthorNames(ctx, out)
	s.fillListCosWork(ctx, out)
	return out, ids, next, nil
}

// recommendPrefsFromSettings 从 KV 设置装载用户推荐偏好；无记录或解析
// 失败返回 nil（nil = 算法使用设计默认权重——回收逻辑仍生效，
// DOMAIN_RULES §1.2）。解析失败只警告：偏好是纯调参项，不值得 500。
func (s *Server) recommendPrefsFromSettings(ctx context.Context) *recommend.Weights {
	raw, err := s.q.GetSetting(ctx, settingKeyRecommendPrefs)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		s.logger.Warn("读取推荐偏好失败，使用默认权重", "err", err)
		return nil
	}
	var stored gen.RecommendPrefs
	if err := json.Unmarshal([]byte(raw), &stored); err != nil {
		s.logger.Warn("推荐偏好 JSON 解析失败，使用默认权重", "err", err)
		return nil
	}
	return weightsFromPrefs(&stored)
}

// weightsFromPrefs 把存储格式（gen.RecommendPrefs，字段可空）翻译为
// 算法 Weights：nil 字段回落到设计默认值（字段级补全，与旧项目
// customPrefs?x ?: default 语义一致）。
func weightsFromPrefs(p *gen.RecommendPrefs) *recommend.Weights {
	base := recommend.DefaultWeights()
	w := recommend.Weights{
		TagRelevance:  paramOr(base.TagRelevance, p.TagRelevance),
		TagCollection: paramOr(base.TagCollection, p.TagCollection),
		Engagement:    paramOr(base.Engagement, p.Engagement),
		Recency:       paramOr(base.Recency, p.Recency),
		LikeScore:     paramOr(base.LikeScore, p.LikeScore),
		Discovery:     paramOr(base.Discovery, p.Discovery),
		Freshness:     paramOr(base.Freshness, p.Freshness),
		BrowseDepth:   paramOr(base.BrowseDepth, p.BrowseDepth),
		MaxRandom:     paramOr(base.MaxRandom, p.MaxRandom),
	}
	return &w
}

// paramOr 字段级回落：nil 用默认，否则取 *float64。
func paramOr(def float64, v *float64) float64 {
	if v == nil {
		return def
	}
	return *v
}

// recommendPrefsFingerprint 权重快照的缓存键指纹：JSON 序列化（Weights 全是
// 导出 float64 字段，文本化稳定），nil 权重同样得到稳定串。指纹只用于键判等，
// 不用于解析还原。
func recommendPrefsFingerprint(w *recommend.Weights) string {
	b, err := json.Marshal(w)
	if err != nil {
		// Weights 无自定义类型，序列化按构造不会失败；兜底返回空串（退化为
		// 「所有 nil 权重同一键」的保守判等，不会错命中不同权重）
		return ""
	}
	return string(b)
}

// recordDailyShownBatch 当日展示计数批量回写：每个返回项 +1 的语义与旧逐条
// 写一致，但失败粒度不同并已接受（2026-09-18 性能批）：旧逐条写部分失败时
// 已写的行保留；本实现单事务内任一条失败即整批回滚（warn 不阻塞响应）。
// 计数是缓存性质可丢弃重建（0001 表注释），整批回滚的下一次请求即重算补齐。
// 收进单事务的动机：SQLite 每个隐式事务一次落盘提交，200 条逐条写在手机
// 存储（ADR-0015 单机形态）上是可观测的耗时。
func (s *Server) recordDailyShownBatch(ctx context.Context, assetIDs []string, day string) {
	if len(assetIDs) == 0 {
		return
	}
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		s.logger.Warn("展示计数事务开启失败（忽略，可丢弃重建）", "err", err)
		return
	}
	qtx := s.q.WithTx(tx)
	for _, id := range assetIDs {
		if err := qtx.IncrementDailyShown(ctx, db.IncrementDailyShownParams{AssetID: id, Day: day}); err != nil {
			s.logger.Warn("推荐展示计数写入失败（忽略，可丢弃重建）", "err", err, "asset_id", id)
			s.rollbackTx(ctx, "推荐展示计数", tx)
			return
		}
	}
	if err := tx.Commit(); err != nil {
		s.logger.Warn("展示计数事务提交失败（忽略，可丢弃重建）", "err", err)
	}
}
