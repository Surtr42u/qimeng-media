package recommend

import (
	"fmt"
	"math"
	"reflect"
	"testing"
	"time"
)

// 1.00、0.96、0.92……每对相邻分差都小于 0.05，但整条链不是同分桶。
// 使用单一媒体，隔离视频/图片混合对分数顺序的有意影响。
func TestRecommendBucketCannotChainAcrossScoreRange(t *testing.T) {
	items := make([]Item, 21)
	groups := make(map[string]int, len(items))
	for i := range items {
		items[i] = makeItem(fmt.Sprintf("chain-%02d", i), "image", fixedNow)
		items[i].Stats.BrowseSeconds = int64(1000 - i*40)
		groups[items[i].AssetID] = i / 2
	}
	for seed := 1; seed <= 32; seed++ {
		got := Recommend(items, Params{Now: fixedNow, Seed: seed, Prefs: &Weights{BrowseDepth: 1}})
		if len(got) != len(items) || !containsAll(ids(got), ids(items)) {
			t.Fatal("打散必须保留完整候选")
		}
		for i, it := range got {
			if groups[it.AssetID] != i/2 {
				t.Fatalf("seed=%d 发生跨桶打散: %v", seed, ids(got))
			}
		}
		again := Recommend(items, Params{Now: fixedNow, Seed: seed, Prefs: &Weights{BrowseDepth: 1}})
		if !reflect.DeepEqual(got, again) {
			t.Fatal("同 seed 必须可复现")
		}
	}
}

func TestRecommendScoreKeepsFixedDimensions(t *testing.T) {
	lastViewed := fixedNow.Add(-2 * 24 * time.Hour)
	it := makeItem("score", "image", fixedNow)
	it.Tags = []string{"alpha", "beta"}
	it.FileName = "alpha-gamma.jpg"
	it.Stats = Stats{ViewCount: 2, PlayCount: 3, BrowseSeconds: 10, LastViewedAt: &lastViewed}
	it.LikeCount, it.ShownToday = 1, 2
	prefs := DefaultWeights()
	prefs.MaxRandom = 0 // 随机项另外由同 seed 可复现测试约束。
	got := scoreItem(&it, tagsFor(it), resolveWeights(&prefs, true, true, true),
		map[string]float64{"alpha": 1, "beta": 0.5, "gamma": 0.25},
		map[string]bool{"alpha": true, "beta": true, "delta": true},
		normDenominators{maxEngagement: 10, maxBrowseSeconds: 100, maxLikes: 2}, Params{Now: fixedNow})
	// 三标签平均相关度 7/12、Jaccard 1/2；固定维度共 0.571333…，展示两次扣 1.6。
	const want = -1.0286666666666666
	if math.Abs(got-want) > 1e-12 {
		t.Fatalf("固定评分维度改变: got %.15f want %.15f", got, want)
	}
}

func TestRecommendDoesNotRewriteManualTagsOrInputOrder(t *testing.T) {
	items := []Item{makeItem("z", "image", fixedNow), makeItem("a", "video", fixedNow)}
	items[0].FileName = "Alpha-系列.jpg"
	items[0].Tags = []string{"Manual", "Manual", "alpha"}
	items[1].Tags = []string{"video"}
	before := make([]Item, len(items))
	copy(before, items)
	for i := range before {
		before[i].Tags = append([]string(nil), items[i].Tags...)
	}
	got := Recommend(items, Params{Now: fixedNow, Seed: 42})
	if !reflect.DeepEqual(items, before) {
		t.Fatal("推荐不得改写输入标签或顺序")
	}
	for _, result := range got {
		for _, input := range before {
			if result.AssetID == input.AssetID && !reflect.DeepEqual(result.Tags, input.Tags) {
				t.Fatal("推断标签不得冒充手动标签写入输出")
			}
		}
	}
}

func TestShuffleBucketInclusiveBoundary(t *testing.T) {
	const high = 1.0
	boundary := high - bucketWidth
	rows := []scoredItem{
		{index: 0, score: high},
		{index: 1, score: boundary},
		{index: 2, score: math.Nextafter(boundary, math.Inf(-1))},
	}
	for seed := 1; seed <= 32; seed++ {
		got := append([]scoredItem(nil), rows...)
		shuffleBuckets(got, seed)
		if got[2].index != 2 || got[0].index+got[1].index != 1 {
			t.Fatalf("边界内外分桶错误: %v", got)
		}
	}
}
