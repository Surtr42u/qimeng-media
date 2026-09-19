// recommendations.go：推荐流端点（M3 十维算法，DOMAIN_RULES §1）。
//
// 数据流：响应缓存查找（recommend_cache.go，键=日界/seed/类型/COS/权重/翻页
// 窗+修订号；命中即回写计数后返回）→ ListAssetsRecommendInput（单行一聚合，
// 含当日展示计数）+ ListAllAssetTags → 组装 []recommend.Item → Recommend
// （纯函数）→ offset 翻页切片 [offset, offset+limit) → 装配响应并登记缓存 →
// 对返回项单事务批量写每日展示计数（先读后写：惩罚基于展示前计数，展示后
// +1）。Assembly 层职责：行→Item 翻译、偏好装载、计数回写；
// 算法本体在 internal/recommend（无 IO），与旧项目 App 的语义差异
// （FNV/确定性 RNG 等）见该包 doc.go。
package httpapi

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/recommend"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// GetApiV1Recommendations 推荐流：十维自适应加权评分 + 同分桶打散 +
// 视频图片混合（参数语义见 openapi：seed 0=稳定序 / >0=刷新打散）。
// 超函数警戒线（>100 行）理由：oapi-codegen 生成的接口签名 + 单请求
// 直线流（参数归一→SQL→打分→三后处理→分页切片→装配）；打分与后
// 处理的复杂度已隔离在 recommend 纯函数包，此处是编排壳。
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

	// 响应缓存查找/单飞计算（语义边界见 recommend_cache.go 头注释）：命中、
	// 或与开机预热共享同一次计算；取得结果后回写当日展示计数（§1.4.3 真实
	// 展示语义——计算与预热本身不计数，谁把结果真正服务给客户端谁回写）。
	key := recommendCacheKey{
		rev:       s.recommendCache.revision(),
		day:       day,
		seed:      seed,
		mediaType: mediaType.String, // Valid=false 时为 "" = 全部类型
		cosOnly:   cosOnly == 1,
		prefs:     recommendPrefsFingerprint(prefs),
		offset:    offset,
		limit:     limit,
	}
	// 计算核脱离请求取消（WithoutCancel，2026-09-18 评审补丁）：单飞计算被
	// 并发同键请求共享，席位持有者的客户端断开不得中止计算——否则共享方
	// 继承取消错误 500。计算核无副作用（计数回写在本函数收尾），脱离安全；
	// 断开方算完的结果照常落缓存供后续请求命中（预热路径用 Background 同理）。
	body, ids, err := s.recommendCache.do(key, func() ([]gen.AssetSummary, []string, error) {
		return s.computeRecommendPage(context.WithoutCancel(r.Context()), day, seed, mediaType, cosOnly, offset, limit, prefs)
	})
	if err != nil {
		s.internalErr(w, "推荐流计算失败", err)
		return
	}
	s.recordDailyShownBatch(r.Context(), ids, day)
	writeJSON(w, http.StatusOK, body)
}

// computeRecommendPage 推荐页计算核（缓存未命中路径的唯一计算体；预热与
// 请求经 recommendCache.do 单飞共享）。除缓存登记外的副作用为零——展示
// 计数是「真实展示」语义（DOMAIN_RULES §1.4.3），由真实服务路径在取得
// 结果后自行回写，预热路径（recommend_prewarm.go）刻意不回写。
func (s *Server) computeRecommendPage(ctx context.Context, day string, seed int, mediaType sql.NullString, cosOnly int64, offset, limit int, prefs *recommend.Weights) ([]gen.AssetSummary, []string, error) {
	rows, err := s.q.ListAssetsRecommendInput(ctx, db.ListAssetsRecommendInputParams{
		Day: day, MediaType: mediaType, CosOnly: cosOnly,
	})
	if err != nil {
		return nil, nil, err
	}
	tagRows, err := s.q.ListAllAssetTags(ctx)
	if err != nil {
		return nil, nil, err
	}
	tagsByAsset := make(map[string][]string, len(tagRows))
	for _, tr := range tagRows {
		tagsByAsset[tr.AssetID] = append(tagsByAsset[tr.AssetID], tr.Name)
	}

	// 行→Item 翻译（按 asset_id 留原文，输出阶段 buildSummary 用）。
	items := make([]recommend.Item, 0, len(rows))
	rowByID := make(map[string]*db.ListAssetsRecommendInputRow, len(rows))
	for i := range rows {
		row := &rows[i]
		rowByID[row.AssetID] = row
		items = append(items, recommend.Item{
			AssetID:    row.AssetID,
			FileName:   row.FileName,
			MediaType:  row.MediaType,
			CreatedAt:  parseStoreTime(row.CreatedAt),
			ModifiedAt: parseStoreTime(row.Mtime),
			Tags:       tagsByAsset[row.AssetID],
			LikeCount:  int(row.LikeCount),
			ShownToday: toInt(row.ShownToday), // 嵌套 COALESCE 的类型为 interface{}，见 recommend.sql 注释
			Stats: recommend.Stats{
				ViewCount:     int(row.ViewCount),
				PlayCount:     int(row.PlayCount),
				BrowseSeconds: int64(toInt(row.BrowseSeconds)),
				LastViewedAt:  parseLastViewed(row.LastViewedAt),
			},
		})
	}

	// 翻页切片（协议 offset，默认 0）：Recommend 产出打分排序后的前
	// offset+limit 条，再切当前页 [offset, offset+limit)。候选查询仍全量，
	// ask 以候选规模为上界——深翻页（offset 超过候选数）自然得到空页，
	// 同时规避 offset 巨大时的 int 溢出。
	ask := offset + limit
	if ask < 0 || ask > len(items) { // ask<0 = offset+limit 溢出，按候选全量处理
		ask = len(items)
	}

	ordered := recommend.Recommend(items, recommend.Params{
		Limit: ask,
		Seed:  seed,
		Prefs: prefs,
		Now:   s.now(),
	})
	ordered = slicePage(ordered, offset, limit)

	out := make([]gen.AssetSummary, 0, len(ordered))
	for _, it := range ordered {
		row := rowByID[it.AssetID]
		item := buildSummary(s, row.AssetID, row.FileName, row.MediaType,
			row.SizeBytes, row.Mtime, row.CreatedAt, row.Source, row.IsFavorite, row.LikeCount, nil, nil)
		if row.DurationMs.Valid {
			item.DurationMs = ptr(row.DurationMs.Int64) // 卡片时长角标数据（仅视频有值）
		}
		out = append(out, item)
	}
	// authorNames：推荐流是首页默认 tab 的卡片数据源，与 GET /assets
	// 同口径填充作者行（协议 AssetSummary.authorNames 描述）。
	s.fillListAuthorNames(ctx, out)
	// cosWork：COS 推荐模式（cos tab）卡片标题数据源，与 GET /assets
	// 同口径批量装配（协议 AssetSummary.cosWork 描述）。
	s.fillListCosWork(ctx, out)

	assetIDs := make([]string, 0, len(ordered))
	for _, it := range ordered {
		assetIDs = append(assetIDs, it.AssetID)
	}
	return out, assetIDs, nil
}

// parseLastViewed 把 sqlc 的 MAX(started_at) 结果翻译为 *time.Time；
// NULL（从未浏览）返回 nil——算法侧 recency 走默认分 0.3。
// 与既有 LastViewedAt 查询（assets.go）同风格：NULL 由 interface{} 的
// nil 表示，字符串空值防御性兜底为 nil。
func parseLastViewed(v any) *time.Time {
	str, ok := v.(string)
	if !ok || str == "" {
		return nil
	}
	t := parseStoreTime(str)
	return &t
}

// resolvePageOffset 已迁往 pagination.go 与 limit 族同处（2026-09-20
// 全库审查清偿其自述的放置债：recommendations 与 rankings 两消费方）。
// slicePage 对已排序结果做 [offset : offset+limit) 切片（推荐/排行端点的
// 翻页语义，openapi 两端点 offset 参数描述）。越界自然为空：offset 落在
// 末页之后返回 nil（调用方输出层 make([]T, 0, ...) 保证 JSON 仍为 []，
// 不落 null）；offset+limit 超出末尾按末页截短。
func slicePage[T any](ordered []T, offset, limit int) []T {
	if offset >= len(ordered) {
		return nil
	}
	end := offset + limit
	if end > len(ordered) {
		end = len(ordered)
	}
	return ordered[offset:end]
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
			_ = tx.Rollback()
			return
		}
	}
	if err := tx.Commit(); err != nil {
		s.logger.Warn("展示计数事务提交失败（忽略，可丢弃重建）", "err", err)
	}
}
