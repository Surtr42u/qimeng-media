package recommend

// weightedScore 承载一次打分计算的所有中间状态。
// 由 resolveWeights 派生：自定义偏好优先，数据空缺时按
// DOMAIN_RULES §1.2 自动回收（规则逐字遵守）。
type weightedScore struct {
	tagRelevance  float64
	tagCollection float64
	engagement    float64
	recency       float64
	likeScore     float64
	discovery     float64
	freshness     float64
	browseDepth   float64
	maxRandom     float64
}

// reclaimedRatio 是标签维度空缺席时回收权重分给 discovery 与
// randomFactor 的各半比例（DOMAIN_RULES §1.2：回收 ×50% + ×50%）。
const reclaimedRatio = 0.5

// defaultWeights 是设计默认权重（DOMAIN_RULES §1.3 均衡推荐预设，
// 逐字遵守：0.22/0.15/0.10/0.15/0.05/0.20/0.05/0.03/0.30）。
func defaultWeights() Weights {
	return Weights{
		TagRelevance:  0.22,
		TagCollection: 0.15,
		Engagement:    0.10,
		Recency:       0.15,
		LikeScore:     0.05,
		Discovery:     0.20,
		Freshness:     0.05,
		BrowseDepth:   0.03,
		MaxRandom:     0.30,
	}
}

// DefaultWeights 返回设计默认权重（调用方无存储偏好时使用；
// 也作为 GET /recommendations/prefs 无记录时的回显值来源）。
// 导出供 httpapi 复用，保证两处默认值永远同一来源，禁止手抄字面量。
func DefaultWeights() Weights { return defaultWeights() }

// resolveWeights 权重初始化（自定义偏好优先）+ 自适应回收
// （标签/点赞/历史为空时重新分配权重，语义照旧项目：
// 回收按"当前权重"计算——自定义偏好同样参与回收，DOMAIN_RULES §1.2）
//
// 状态检测输入 hasTags/hasLikes/hasHistory 由调用方从库现状汇总
// （有任意一条数据即视为有，缺数据才回收）。
func resolveWeights(custom *Weights, hasTags, hasLikes, hasHistory bool) weightedScore {
	w := defaultWeights()
	if custom != nil {
		w = *custom
	}

	// 标签为空：tagRelevance + tagCollection 半回收给 discovery、半给 maxRandom
	if !hasTags {
		reclaimed := w.TagRelevance + w.TagCollection
		w.TagRelevance = 0
		w.TagCollection = 0
		w.Discovery += reclaimed * reclaimedRatio
		w.MaxRandom += reclaimed * reclaimedRatio
	}
	// 点赞为空：likeScore 全额给 freshness
	if !hasLikes {
		reclaimed := w.LikeScore
		w.LikeScore = 0
		w.Freshness += reclaimed
	}
	// 无浏览历史（viewCount 全为 0）：engagement 无意义，全额给 discovery
	if !hasHistory {
		reclaimed := w.Engagement
		w.Engagement = 0
		w.Discovery += reclaimed
	}
	return weightedScore{
		tagRelevance: w.TagRelevance, tagCollection: w.TagCollection,
		engagement: w.Engagement, recency: w.Recency, likeScore: w.LikeScore,
		discovery: w.Discovery, freshness: w.Freshness, browseDepth: w.BrowseDepth,
		maxRandom: w.MaxRandom,
	}
}

// normDenominators 是归一化分母三件套（engagement/browseSeconds/likes 的
// 全库最大值，coerceAtLeast 1 防除零，DOMAIN_RULES §1.1 逐字遵守）。
type normDenominators struct {
	maxEngagement    float64
	maxBrowseSeconds float64
	maxLikes         float64
}

// computeNormDenominators 从全库汇聚归一化分母。
func computeNormDenominators(items []Item) normDenominators {
	var n normDenominators
	for i := range items {
		it := &items[i]
		if e := float64(it.Stats.ViewCount + it.Stats.PlayCount); e > n.maxEngagement {
			n.maxEngagement = e
		}
		if b := float64(it.Stats.BrowseSeconds); b > n.maxBrowseSeconds {
			n.maxBrowseSeconds = b
		}
		if l := float64(it.LikeCount); l > n.maxLikes {
			n.maxLikes = l
		}
	}
	if n.maxEngagement < 1 {
		n.maxEngagement = 1
	}
	if n.maxBrowseSeconds < 1 {
		n.maxBrowseSeconds = 1
	}
	if n.maxLikes < 1 {
		n.maxLikes = 1
	}
	return n
}
