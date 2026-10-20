package recommend

import (
	"math/rand"
)

// bucketWidth 是同分桶判定幅度：得分差不超过 0.05 视为同分（刷新打散只
// 在桶内进行），DOMAIN_RULES §1.4「±0.05 分差内视为同桶」逐字遵守。
const bucketWidth = 0.05

// shuffleBuckets 以每桶首项最高分为锚，score < 最高分 − 0.05 时开新桶。
// 禁止按相邻分差链式延伸；洗牌仅修改评分下标，不能修改输入 Item。
// 必须先确定桶尾再洗牌，以免锚点被交换；所有桶沿用同一随机数序列。
//
// 确定性保证：RNG 一定以 seed 初始化——与 balanceVideoImage 同源种子，
// 整个推荐结果（打散+混合）在同 seed 下逐位可复现。旧 App 实现即如此
// （v1.12 起 shuffleBuckets 与 balanceVideoImage 共用同一 Random 种子）。
func shuffleBuckets(scored []scoredItem, seed int) {
	rng := rand.New(rand.NewSource(int64(seed)))
	for start := 0; start < len(scored); {
		end := start + 1
		for end < len(scored) && scored[end].score >= scored[start].score-bucketWidth {
			end++
		}
		bucket := scored[start:end]
		if len(bucket) > 1 {
			rng.Shuffle(len(bucket), func(i, j int) { bucket[i], bucket[j] = bucket[j], bucket[i] })
		}
		start = end
	}
}

// balanceVideoImage 视频/图片自然混合：每步按剩余数量比例随机决定取
// 视频还是图片，避免连续分组；其余媒体类型（animated_image）全部归
// 图片组（旧项目口径：非 VIDEO 即图片）。
//
// 取舍说明（与旧 App 不同，DST 2026-08 定案）：旧实现 seed=0 时用
// 无种子 Random（真随机），会破坏"同 seed 两次调用结果一致"的可复现性
// 承诺；服务端统一用 seed 初始化 RNG（seed=0 也是确定性序列）——种子
// 进入同一路径，测试与离线回放都可复现。代价是设计上"0=稳定排序"
// 不再严格稳定（0 也是确定性游走），与 openapi 描述的"0=稳定排序、
// >0=刷新打散"的差异仅限视频/图片交错顺序本身，排序精度不减（交错
// 本来就是牺牲精度换多样性的有意为之，DOMAIN_RULES §1.4）。
// 完整排序和桶洗牌后，按全量剩余比例生成所需前缀；不生成被 Limit 丢弃
// 的尾部。两组只保存下标，比例/RNG 调用顺序和先混合全量再截断完全一致。
func balanceVideoImage(items []Item, ordered []scoredItem, seed, limit int) []Item {
	if limit <= 0 || limit > len(ordered) {
		limit = len(ordered)
	}
	out := make([]Item, 0, limit)
	videoCount := 0
	for _, si := range ordered {
		if items[si.index].MediaType == mediaTypeVideo {
			videoCount++
		}
	}
	// 单一类型只装配所需前缀，不持有整库大小的返回缓冲。
	if videoCount == 0 || videoCount == len(ordered) {
		for _, si := range ordered[:limit] {
			out = append(out, items[si.index])
		}
		return out
	}
	indices := make([]int, len(ordered))
	videos, images := indices[:0:videoCount], indices[videoCount:videoCount]
	for _, si := range ordered {
		if items[si.index].MediaType == mediaTypeVideo {
			videos = append(videos, si.index)
		} else {
			images = append(images, si.index)
		}
	}
	rng := rand.New(rand.NewSource(int64(seed)))
	vi, ii := 0, 0
	for len(out) < limit {
		pickVideo := false
		switch {
		case vi >= len(videos):
			pickVideo = false
		case ii >= len(images):
			pickVideo = true
		default:
			pickVideo = rng.Float64() < float64(len(videos)-vi)/float64(len(videos)-vi+len(images)-ii)
		}
		if pickVideo {
			out = append(out, items[videos[vi]])
			vi++
		} else {
			out = append(out, items[images[ii]])
			ii++
		}
	}
	return out
}
