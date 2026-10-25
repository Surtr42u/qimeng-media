package recommend

import (
	"math"
	"slices"
)

// randomMask 与 randomDivisor 是 randomFactor 的确定性哈希换算参数
// （DOMAIN_RULES §1.1：取 16bit / 65535 × maxRandom，逐字遵守）。
const (
	randomMask    = 0xFFFF
	randomDivisor = 65535
)

// dailyPenaltyStep 是每日去重惩罚的每展示一次扣分（−0.8×n，n=今日
// 已展示次数；DOMAIN_RULES §1.1 逐字遵守）。
const dailyPenaltyStep = 0.8

// scoredItem 只携带输入下标和总分；排序/打散不反复搬运完整 Item，
// 最终输出时才复制命中的条目。输入及其标签在算法内始终只读。
type scoredItem struct {
	index int
	score float64
}

// Recommend 十维自适应加权推荐入口（纯函数，无 IO）。
//
// 流程（DOMAIN_RULES §1 顺序）：空输入短路 → 数据状态检测 → 应权 →
// 逐项打分（Σ维度分×权重 + randomFactor − dailyPenalty）→ 降序 →
// seed>0 时同分桶打散 → 视频/图片自然混合 → 截断 Limit。
//
// 确定性承诺：randomFactor 用 FNV-1a(AssetID) 与 seed 异或取 16bit
// （替代旧项目 String.hashCode——语言无关、跨平台稳定），RNG 一律以
// seed 初始化（seed=0 跳过打散但混合仍确定性），同 seed 输出必可复现。
func Recommend(items []Item, p Params) []Item {
	return Prepare(items, p).Items(p.Seed, p.Limit)
}

// RecommendIDs 共享同一评分/混合核心，直接装配完整或限量 ID，不复制 Item。
func RecommendIDs(items []Item, p Params) []string {
	return Prepare(items, p).IDs(p.Seed, p.Limit)
}

// Prepared 仅由调用者在本轮固定输入/偏好/时刻中复用。输入及标签必须保持只读；
// 每个 seed 独立评分、排序、洗牌与混合，不缓存或合并浮点维度求和结果。
type Prepared struct {
	items      []Item
	params     Params
	tagsByItem [][]string
	weights    weightedScore
	relevance  map[string]float64
	topTags    map[string]bool
	norms      normDenominators
}

// Prepare 只做与 seed 无关的准备；空输入不分配，权重解析立即固定偏好值。
func Prepare(items []Item, p Params) *Prepared {
	if len(items) == 0 {
		return &Prepared{params: p}
	}
	// 数据状态检测（有任意一条即视为有，缺数据才触发回收，语义照旧项目：
	// hasTags 只看库内手动标签；hasHistory 只看 viewCount>0——play 而无
	// open 不算历史，因 engagement 维度强依赖 open 计数）。
	hasTags, hasLikes, hasHistory := false, false, false
	for i := range items {
		if len(items[i].Tags) > 0 {
			hasTags = true
		}
		if items[i].LikeCount > 0 {
			hasLikes = true
		}
		if items[i].Stats.ViewCount > 0 {
			hasHistory = true
		}
	}

	weights := resolveWeights(p.Prefs, hasTags, hasLikes, hasHistory)
	// 每项完整标签只构建一次，供偏好统计和评分复用；不改写输入的手动标签。
	tagsByItem := make([][]string, len(items))
	for i := range items {
		tagsByItem[i] = tagsFor(items[i])
	}
	relevance := buildTagRelevanceMap(items, tagsByItem)
	topTags := topTagsOf(relevance)
	norms := computeNormDenominators(items)

	return &Prepared{items: items, params: p, tagsByItem: tagsByItem, weights: weights, relevance: relevance, topTags: topTags, norms: norms}
}

func (r *Prepared) order(seed int) []scoredItem {
	p := r.params
	p.Seed = seed

	scored := make([]scoredItem, len(r.items))
	for i := range r.items {
		scored[i] = scoredItem{index: i, score: scoreItem(&r.items[i], r.tagsByItem[i], r.weights, r.relevance, r.topTags, r.norms, p)}
	}
	slices.SortStableFunc(scored, func(a, b scoredItem) int {
		switch {
		case a.score > b.score:
			return -1
		case a.score < b.score:
			return 1
		default:
			return 0
		}
	})

	if p.Seed > 0 {
		shuffleBuckets(scored, p.Seed)
	}
	return scored
}

// Items 保留原 Recommend 的完整字段及 Limit 语义。
func (r *Prepared) Items(seed, limit int) []Item {
	if len(r.items) == 0 {
		return nil
	}
	return balanceOrder(r.items, r.order(seed), seed, limit, func(index int) Item { return r.items[index] })
}

// IDs 与 Items 使用唯一排序与混合实现，仅最终装配类型不同。
func (r *Prepared) IDs(seed, limit int) []string {
	if len(r.items) == 0 {
		return nil
	}
	return balanceOrder(r.items, r.order(seed), seed, limit, func(index int) string { return r.items[index].AssetID })
}

// scoreItem 是单个条目的十维打分（公式与常量逐字遵守 DOMAIN_RULES §1.1）：
//
//	score = Σ(维度分×权重) + randomFactor − dailyPenalty
//
// 维度分与随机扰动均与库内最大值作归一化（norms），幂等且边界安全。
func scoreItem(it *Item, tags []string, w weightedScore, relevance map[string]float64,
	topTags map[string]bool, norms normDenominators, p Params) float64 {
	viewCount := it.Stats.ViewCount
	playCount := it.Stats.PlayCount

	// 每日推荐去重：惩罚随展示次数递增（旧项目 v1.12 定案——恒 -0.8 时
	// 高频刷新后近全库都罚一次、推荐趋近随机；递增让新文件稳定排前）。
	penalty := 0.0
	if it.ShownToday > 0 {
		penalty = -dailyPenaltyStep * float64(it.ShownToday)
	}

	// 维度分 × 权重（顺序与 DOMAIN_RULES §1.1 表一致）
	tagRelevance := tagRelevanceScore(tags, relevance) * w.tagRelevance
	tagCollection := tagCollectionScore(tags, topTags) * w.tagCollection
	engagement := math.Min(float64(viewCount+playCount)/norms.maxEngagement, 1) * w.engagement
	recency := recencyScore(it.Stats.LastViewedAt, p.Now) * w.recency
	likeScore := math.Min(float64(it.LikeCount)/norms.maxLikes, 1) * w.likeScore
	discovery := (1 - math.Min(float64(viewCount)/discoveryDivisor, 1)) * w.discovery
	freshness := freshnessScore(it.CreatedAt, p.Now) * w.freshness
	browseDepth := math.Min(float64(it.Stats.BrowseSeconds)/norms.maxBrowseSeconds, 1) * w.browseDepth
	randomFactor := randomFactorOf(it.AssetID, p.Seed) * w.maxRandom

	return tagRelevance + tagCollection + engagement + recency + likeScore +
		discovery + freshness + browseDepth + randomFactor + penalty
}

// randomFactorOf 确定性随机扰动：FNV-1a32(AssetID) xor seed 取低 16 位
// 归一化到 [0,1] 再乘 maxRandom。禁止真随机（同 seed 必须可复现）。
func randomFactorOf(assetID string, seed int) float64 {
	h := fnv1a32(assetID) ^ uint32(seed)
	return float64(h&randomMask) / randomDivisor
}

// fnv1a32 是 FNV-1a 32bit 哈希（32 位素数为种子，标准 FNV 参数）。
// 替代旧项目 String.hashCode（JVM 实现细节，跨平台不可复现）。
func fnv1a32(s string) uint32 {
	var h uint32 = 2166136261
	for i := 0; i < len(s); i++ {
		h ^= uint32(s[i])
		h *= 16777619
	}
	return h
}
