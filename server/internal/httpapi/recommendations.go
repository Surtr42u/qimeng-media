// recommendations.go：推荐流端点（M3 十维算法，DOMAIN_RULES §1）。
//
// 数据流：ListAssetsRecommendInput（单行一聚合，含当日展示计数）+
// ListAllAssetTags → 组装 []recommend.Item → Recommend（纯函数）→
// 对返回项逐条写每日展示计数（先读后写：惩罚基于展示前计数，展示后 +1）
// → buildSummary 输出。Assembly 层职责：行→Item 翻译、偏好装载、计数回写；
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
func (s *Server) GetApiV1Recommendations(w http.ResponseWriter, r *http.Request, params gen.GetApiV1RecommendationsParams) {
	limit, ok := resolvePageLimit(w, params.Limit)
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

	rows, err := s.q.ListAssetsRecommendInput(r.Context(), db.ListAssetsRecommendInputParams{
		Day: day, MediaType: mediaType, CosOnly: cosOnly,
	})
	if err != nil {
		s.internalErr(w, "查询推荐输入", err)
		return
	}
	tagRows, err := s.q.ListAllAssetTags(r.Context())
	if err != nil {
		s.internalErr(w, "查询全库标签", err)
		return
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

	ordered := recommend.Recommend(items, recommend.Params{
		Limit: limit,
		Seed:  seed,
		Prefs: s.recommendPrefsFromSettings(r.Context()),
		Now:   s.now(),
	})

	// 展示计数回写：先读后写（惩罚基于展示前计数，展示后 +1）。
	// 失败只警告不阻塞响应——计数是缓存性质可丢弃重建（0001 表注释）；
	// 单用户场景逐条写即可，不做事务。
	for _, it := range ordered {
		if err := s.q.IncrementDailyShown(r.Context(), db.IncrementDailyShownParams{
			AssetID: it.AssetID, Day: day,
		}); err != nil {
			s.logger.Warn("推荐展示计数写入失败（忽略，可丢弃重建）", "err", err, "asset_id", it.AssetID)
		}
	}

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
	s.fillListAuthorNames(r.Context(), out)
	// cosWork：COS 推荐模式（cos tab）卡片标题数据源，与 GET /assets
	// 同口径批量装配（协议 AssetSummary.cosWork 描述）。
	s.fillListCosWork(r.Context(), out)
	writeJSON(w, http.StatusOK, out)
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

// paramOr 字段级回落：nil 用默认，否则取 *float32 转 float64。
func paramOr(def float64, v *float32) float64 {
	if v == nil {
		return def
	}
	return float64(*v)
}
