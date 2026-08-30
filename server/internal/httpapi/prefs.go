// prefs.go：推荐偏好读写端点（M3）。
//
// 存储：kv_settings 表（migrations/0003），键 recommend_prefs，值为
// gen.RecommendPrefs 的 JSON（与 openapi RecommendPrefs 结构一致）。
// 无记录时 GET 回显设计默认值（recommend.DefaultWeights()——
// DOMAIN_RULES §1.3 均衡推荐），PUT 权值一律钳制到 [0,1]。
package httpapi

import (
	"encoding/json"
	"net/http"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/recommend"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// settingKeyRecommendPrefs 是推荐偏好在 kv_settings 表的键名。
// 取值范围约定见 migrations/0003_settings_kv.up.sql（<功能点>_<设置名>）。
const settingKeyRecommendPrefs = "recommend_prefs"

// clampWeightRange 权重值域上限（DOMAIN_RULES §1.3：权重是"分配比例"，
// 允许超过 1 会破坏"权重合计收敛"的假设，故钳制而非仅校验）。
const clampWeightRange = 1.0

// GetApiV1RecommendationsPrefs 读取推荐偏好：有存储返回存储值，
// 无记录/解析失败返回设计默认值（字段指针全部填充，客户端可盲读）。
func (s *Server) GetApiV1RecommendationsPrefs(w http.ResponseWriter, r *http.Request) {
	wgt := recommend.DefaultWeights()
	if stored := s.recommendPrefsFromSettings(r.Context()); stored != nil {
		wgt = *stored
	}
	writeJSON(w, http.StatusOK, gen.RecommendPrefs{
		TagRelevance:  ptr(float32(wgt.TagRelevance)),
		TagCollection: ptr(float32(wgt.TagCollection)),
		Engagement:    ptr(float32(wgt.Engagement)),
		Recency:       ptr(float32(wgt.Recency)),
		LikeScore:     ptr(float32(wgt.LikeScore)),
		Discovery:     ptr(float32(wgt.Discovery)),
		Freshness:     ptr(float32(wgt.Freshness)),
		BrowseDepth:   ptr(float32(wgt.BrowseDepth)),
		MaxRandom:     ptr(float32(wgt.MaxRandom)),
	})
}

// PutApiV1RecommendationsPrefs 更新推荐偏好：body 按 RecommendPrefs
// 解析，非 nil 字段钳制 [0,1]（防客户端乱传破坏评分量纲），序列化后
// 落 kv_settings，成功 204。
func (s *Server) PutApiV1RecommendationsPrefs(w http.ResponseWriter, r *http.Request) {
	var body gen.RecommendPrefs
	if !decodeJSON(w, r, &body) {
		return
	}
	clampPrefs(&body)
	raw, err := json.Marshal(body)
	if err != nil {
		s.internalErr(w, "序列化推荐偏好", err)
		return
	}
	if err := s.q.UpsertSetting(r.Context(), db.UpsertSettingParams{
		Key:       settingKeyRecommendPrefs,
		Value:     string(raw),
		UpdatedAt: store.FormatTimestamp(s.now()),
	}); err != nil {
		s.internalErr(w, "保存推荐偏好", err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// clampPrefs 把全部非 nil 权值钳制到 [0,1]（maxRandom 同为权重语义）。
func clampPrefs(p *gen.RecommendPrefs) {
	clamp := func(v **float32) {
		if *v == nil {
			return
		}
		if **v < 0 {
			**v = 0
		} else if **v > clampWeightRange {
			**v = clampWeightRange
		}
	}
	clamp(&p.TagRelevance)
	clamp(&p.TagCollection)
	clamp(&p.Engagement)
	clamp(&p.Recency)
	clamp(&p.LikeScore)
	clamp(&p.Discovery)
	clamp(&p.Freshness)
	clamp(&p.BrowseDepth)
	clamp(&p.MaxRandom)
}
