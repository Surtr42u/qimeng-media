package recommend

import (
	"math"
	"math/rand"
)

// bucketWidth 是同分桶判定幅度：得分差 < 0.05 视为同分（刷新打散只
// 在桶内进行），DOMAIN_RULES §1.4「±0.05 分差内视为同桶」逐字遵守。
const bucketWidth = 0.05

// shuffleBuckets 同分桶随机打散：score < 桶内最小分 − 0.05 时开新桶
// （降序输入下"桶内最小分"即最后一项分差超出阈值），桶内用种子 RNG
// 洗牌，单元素桶不动；返回打散后的有序列表（seed>0 才调用）。
//
// 确定性保证：RNG 一定以 seed 初始化——与 balanceVideoImage 同源种子，
// 整个推荐结果（打散+混合）在同 seed 下逐位可复现。旧 App 实现即如此
// （v1.12 起 shuffleBuckets 与 balanceVideoImage 共用同一 Random 种子）。
func shuffleBuckets(scored []scoredItem, seed int) []Item {
	rng := rand.New(rand.NewSource(int64(seed)))
	buckets := make([][]Item, 0, len(scored)/2+1)
	var current []Item
	lastScore := math.MaxFloat64
	for _, si := range scored {
		if len(current) > 0 && si.score < lastScore-bucketWidth {
			buckets = append(buckets, current)
			current = nil
		}
		current = append(current, si.item)
		if len(current) == 1 {
			lastScore = si.score
		} else if si.score < lastScore {
			lastScore = si.score
		}
	}
	if len(current) > 0 {
		buckets = append(buckets, current)
	}
	out := make([]Item, 0, len(scored))
	for _, b := range buckets {
		if len(b) > 1 {
			rng.Shuffle(len(b), func(i, j int) { b[i], b[j] = b[j], b[i] })
		}
		out = append(out, b...)
	}
	return out
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
func balanceVideoImage(items []Item, seed int) []Item {
	videos := make([]Item, 0, len(items))
	images := make([]Item, 0, len(items))
	for _, it := range items {
		if it.MediaType == mediaTypeVideo {
			videos = append(videos, it)
		} else {
			images = append(images, it)
		}
	}
	rng := rand.New(rand.NewSource(int64(seed)))
	out := make([]Item, 0, len(items))
	vi, ii := 0, 0
	for vi < len(videos) || ii < len(images) {
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
			out = append(out, videos[vi])
			vi++
		} else {
			out = append(out, images[ii])
			ii++
		}
	}
	return out
}
