package recommend

import (
	"math"
	"sort"
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

// scoredItem 是评分后的中间行：item + 总分。排序只搬评分的绑定，
// item 字段（值拷贝）保证排序稳定后仍能取回原始条目，不依赖输入 slice。
type scoredItem struct {
	item  Item
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
	if len(items) == 0 {
		return nil
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
	relevance := buildTagRelevanceMap(items)
	topTags := topTagsOf(relevance)
	norms := computeNormDenominators(items)

	scored := make([]scoredItem, len(items))
	for i := range items {
		scored[i] = scoredItem{item: items[i], score: scoreItem(&items[i], weights, relevance, topTags, norms, p)}
	}
	sort.SliceStable(scored, func(i, j int) bool { return scored[i].score > scored[j].score })

	var ordered []Item
	if p.Seed > 0 {
		ordered = shuffleBuckets(scored, p.Seed)
	} else {
		ordered = make([]Item, len(scored))
		for i := range scored {
			ordered[i] = scored[i].item
		}
	}
	mixed := balanceVideoImage(ordered, p.Seed)
	if p.Limit > 0 && len(mixed) > p.Limit {
		mixed = mixed[:p.Limit]
	}
	return mixed
}

// scoreItem 是单个条目的十维打分（公式与常量逐字遵守 DOMAIN_RULES §1.1）：
//
//	score = Σ(维度分×权重) + randomFactor − dailyPenalty
//
// 维度分与随机扰动均与库内最大值作归一化（norms），幂等且边界安全。
func scoreItem(it *Item, w weightedScore, relevance map[string]float64,
	topTags map[string]bool, norms normDenominators, p Params) float64 {
	tags := tagsFor(*it)
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
// 归一化到 [0,1) 再乘 maxRandom。禁止真随机（同 seed 必须可复现）。
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
