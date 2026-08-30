package recommend

import (
	"sort"
	"strings"
	"time"
	"unicode/utf8"
)

// 评分维度常量（DOMAIN_RULES §1.1，逐字遵守）。
const (
	// dayDuration 是一天的时长，所有"距今天数"折算的基准。
	dayDuration = 24 * time.Hour
	// relevanceEmptyDefault 标签相关性在"无备选标签或无已浏览标签"时的默认分。
	relevanceEmptyDefault = 0.2
	// collectionEmptyDefault 标签合集在"无标签或偏好集为空"时的默认分。
	collectionEmptyDefault = 0.15
	// recencyNeverOpened 从未浏览文件的 recency 默认分（冷启动口径 §1.5）。
	recencyNeverOpened = 0.3
	// discoveryDivisor 发现维度浏览数归一化除数（viewCount/5 封顶 1）。
	discoveryDivisor = 5
	// topTagCount 偏好标签集截取数（Top-20；Jaccard 比较用的是值降序前 20）。
	topTagCount = 20
	// inferredTagMaxLen 文件名推断标签的长度上限。按字符数计（非字节）：
	// 旧项目 Kotlin 的 String.length 按 UTF-16 码元计数（中文 1 字=1），
	// 用 UTF-8 字节数判定会把中文 6~15 字（≥18 字节）的标签误丢。
	inferredTagMaxLen = 16
)

// ageDays 把"距今"换算为浮点天数（与旧项目 (now-t)/86400000f 同口径）。
func ageDays(t, now time.Time) float64 {
	return float64(now.Sub(t)) / float64(dayDuration)
}

// tagsFor 返回一项资产的完整标签集：库内手动标签 + 文件名推断标签。
// 与旧项目 MediaBrowserLogic.tagsFor（MediaBrowserLogic.kt:368-374）语义
// 逐条对齐：
//   - manual 标签原样并入：不 trim、不限长（DB 标签名是什么用什么；
//     tags.name 唯一约束已保证非空，库内无脏数据路径）；
//   - 推断标签：去扩展名后按 '_'/'-'/' ' 切分、trim + lowercase、非空且
//     字符数 ≤16（utf8.RuneCountInString 对齐旧项目 UTF-16 码元计数）；
//   - 两集合按字符串原值去重（大小写敏感，旧项目 Set 语义），保留首次
//     出现顺序（确定性；评分对集合顺序不敏感）；
//   - 推断标签只做评分素材，不写回库（DOMAIN_RULES §7 标签不写入媒体文件）。
func tagsFor(item Item) []string {
	out := make([]string, 0, len(item.Tags)+3)
	seen := make(map[string]bool, len(item.Tags)+3)
	for _, t := range item.Tags {
		if seen[t] {
			continue
		}
		seen[t] = true
		out = append(out, t)
	}
	base := item.FileName
	if i := strings.LastIndex(base, "."); i >= 0 {
		base = base[:i]
	}
	for _, part := range strings.FieldsFunc(base, func(r rune) bool {
		return r == '_' || r == '-' || r == ' '
	}) {
		tag := strings.ToLower(strings.TrimSpace(part))
		if tag == "" || utf8.RuneCountInString(tag) > inferredTagMaxLen {
			continue
		}
		if seen[tag] {
			continue
		}
		seen[tag] = true
		out = append(out, tag)
	}
	return out
}

// tagRelevanceMap 是"标签→归一化相关度"：仅统计 viewCount>0 的文件
// （浏览行为被视作兴趣信号，从未浏览的不参与），频次 / 最大频次归一化，
// 规则照旧项目 buildTagRelevanceMap。
func buildTagRelevanceMap(items []Item) map[string]float64 {
	counts := make(map[string]int)
	for i := range items {
		it := &items[i]
		if it.Stats.ViewCount <= 0 {
			continue
		}
		for _, tag := range tagsFor(*it) {
			counts[tag]++
		}
	}
	if len(counts) == 0 {
		return nil
	}
	maxCount := 0
	for _, c := range counts {
		if c > maxCount {
			maxCount = c
		}
	}
	m := make(map[string]float64, len(counts))
	for tag, c := range counts {
		m[tag] = float64(c) / float64(maxCount)
	}
	return m
}

// topTagsOf 取相关度降序前 Top-20 的标签集合（偏好标签集，Jaccard 用）。
// 同相关度按字典序（map 迭代无序——先排序再稳定排序，保证确定性；
// 旧项目用 Kotlin sortedByDescending 的稳定排序，语义等价）。
func topTagsOf(m map[string]float64) map[string]bool {
	if len(m) == 0 {
		return nil
	}
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	sort.SliceStable(keys, func(i, j int) bool { return m[keys[i]] > m[keys[j]] })
	if len(keys) > topTagCount {
		keys = keys[:topTagCount]
	}
	top := make(map[string]bool, len(keys))
	for _, k := range keys {
		top[k] = true
	}
	return top
}

// tagRelevanceScore 标签相关性：标签在已浏览文件中出现频次的归一化
// 平均（缺省 0），封顶 1；空标签或空相关度表 → 默认 0.2。
func tagRelevanceScore(tags []string, relevance map[string]float64) float64 {
	if len(tags) == 0 || len(relevance) == 0 {
		return relevanceEmptyDefault
	}
	sum := 0.0
	for _, t := range tags {
		sum += relevance[t]
	}
	avg := sum / float64(len(tags))
	if avg > 1 {
		return 1
	}
	return avg
}

// tagCollectionScore 标签合集：与 Top-20 偏好标签集的 Jaccard 相似度；
// 空标签或偏好集为空 → 默认 0.15。
func tagCollectionScore(tags []string, topTags map[string]bool) float64 {
	if len(tags) == 0 || len(topTags) == 0 {
		return collectionEmptyDefault
	}
	intersection, union := 0, 0
	for _, t := range tags {
		if topTags[t] {
			intersection++
		}
	}
	// 并集 = 标签数 + 偏好集数 − 交集（tags 无重复，topTags 是集合）
	union = len(tags) + len(topTags) - intersection
	if union <= 0 {
		return 0
	}
	return float64(intersection) / float64(union)
}

// recencyScore 浏览时效：距上次浏览天数衰减
// （<1d=1.0 <3d=0.8 <7d=0.5 <30d=0.2 更久=0；从未浏览=0.3）。
// 分段值逐字遵守 DOMAIN_RULES §1.1。
func recencyScore(lastViewed *time.Time, now time.Time) float64 {
	if lastViewed == nil {
		return recencyNeverOpened
	}
	return decayByAge(ageDays(*lastViewed, now),
		recencyDay, recencyThreeDays, recencyWeek, recencyMonth)
}

// freshnessScore 新鲜度：入库时间衰减
// （<1d=1.0 <3d=0.7 <7d=0.4 <30d=0.2 更久=0）。分段值逐字遵守。
// 零值 CreatedAt 的 ageDays 极大 → 0 分，与旧项目 indexedAtMillis<=0→0 等价。
func freshnessScore(createdAt, now time.Time) float64 {
	return decayByAge(ageDays(createdAt, now),
		freshnessDay, freshnessThreeDays, freshnessWeek, freshnessMonth)
}

// 衰减龄期阈值（天数，DOMAIN_RULES §1.1 逐字遵守：<1d / <3d / <7d / <30d）。
const (
	decayBoundaryDay   = 1.0  // <1d 档
	decayBoundaryThree = 3.0  // <3d 档
	decayBoundaryWeek  = 7.0  // <7d 档
	decayBoundaryMonth = 30.0 // <30d 档
)

// decayByAge 是四段衰减曲线的公共形状（分数值由调用方传入——
// recency 与 freshness 的分段值不同，分别为 DOMAIN_RULES §1.1 两列）。
func decayByAge(age float64, d1, d3, d7, d30 float64) float64 {
	switch {
	case age < decayBoundaryDay:
		return d1
	case age < decayBoundaryThree:
		return d3
	case age < decayBoundaryWeek:
		return d7
	case age < decayBoundaryMonth:
		return d30
	default:
		return 0
	}
}

// 衰减分段值（DOMAIN_RULES §1.1 逐字遵守；同类分次出现故提常量）。
const (
	// recency 列：<1d / <3d / <7d / <30d
	recencyDay       = 1.0
	recencyThreeDays = 0.8
	recencyWeek      = 0.5
	recencyMonth     = 0.2
	// freshness 列：<1d / <3d / <7d / <30d
	freshnessDay       = 1.0
	freshnessThreeDays = 0.7
	freshnessWeek      = 0.4
	freshnessMonth     = 0.2
)
