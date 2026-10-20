package recommend

import (
	"crypto/sha256"
	"fmt"
	"reflect"
	"testing"
	"time"
)

// 摘要来自优化前（ADR-0045）的完整输出，锁定稳定排序、桶打散和混合的
// 随机数消费顺序。限量输出必须逐项等于全量前缀，不能先截候选再打散/混合。
func TestRecommendOutputCompatibility(t *testing.T) {
	for _, tc := range []struct {
		media string
		seed  int
		ties  bool
		want  string
	}{
		{"image", 0, false, "031f722d3246e2c14959f5aacf9d51c288c8d872f528aa4c2447f839f4dcb35e"},
		{"image", 1, false, "bc54437545ff909037c0042c729487d21fbc191e6d0e87f90fc938489f1b36d4"},
		{"image", 42, false, "f1f8371e49e6aa99d5481b8b98ddc9a2c4572ec6dbc91febe7d5f4c4a199c6f0"},
		{"video", 0, false, "031f722d3246e2c14959f5aacf9d51c288c8d872f528aa4c2447f839f4dcb35e"},
		{"video", 1, false, "bc54437545ff909037c0042c729487d21fbc191e6d0e87f90fc938489f1b36d4"},
		{"video", 42, false, "f1f8371e49e6aa99d5481b8b98ddc9a2c4572ec6dbc91febe7d5f4c4a199c6f0"},
		{"mixed", -1, false, "fa085895dfe29b8ad513432be0fac5a16691d4b32d4aca298fa30f23260ec441"},
		{"mixed", 0, false, "1dbea64472ee87b1aed1e2a68de36e88da0e5d784a02206f6cf7a269b2936d46"},
		{"mixed", 1, false, "2140a17bfddcb5ad5d12809f5899f8ce4346ef969a0c15edc084eb5628082856"},
		{"mixed", 42, false, "3536403e225ec543bb482708aec82b5e0feca10df200c88c82a6e056de0b6404"},
		{"mixed", 0, true, "cfe6c905d9484f6959352aafca4393abc13a4cfbc35b643c76c0d32f45f8f279"},
		{"mixed", 1, true, "5ac6fb6838ab6d2b7c4143cd23ce80a0c623299dd649153fe9c77d6e6e35049a"},
		{"mixed", 42, true, "9e2f74ff34adbda2c9a55d4432dc5fca3941da5759befa47612faedbbf9a1149"},
	} {
		name := fmt.Sprintf("%s/seed=%d/ties=%t", tc.media, tc.seed, tc.ties)
		t.Run(name, func(t *testing.T) {
			items := compatibilityItems(tc.media)
			p := Params{Now: fixedNow, Seed: tc.seed}
			if tc.ties {
				p.Prefs = &Weights{}
			}
			full := Recommend(items, p)
			hash := sha256.New()
			for _, it := range full {
				if _, err := fmt.Fprintln(hash, it.AssetID); err != nil {
					t.Fatal(err)
				}
			}
			got := fmt.Sprintf("%x", hash.Sum(nil))
			if got != tc.want {
				t.Fatalf("推荐全量顺序改变: got %s want %s", got, tc.want)
			}
			for _, limit := range []int{-1, 1, 2, 200, len(items), len(items) + 1} {
				p.Limit = limit
				wantLen := len(full)
				if limit > 0 && limit < wantLen {
					wantLen = limit
				}
				if got := Recommend(items, p); !reflect.DeepEqual(got, full[:wantLen]) {
					t.Fatalf("limit=%d 未保留全量结果前缀及完整字段", limit)
				}
			}
		})
	}
}

func compatibilityItems(media string) []Item {
	items := make([]Item, 257)
	for i := range items {
		kind := media
		if media == "mixed" {
			kind = []string{"image", "video", "animated_image"}[i%3]
		}
		it := makeItem(fmt.Sprintf("compat-%03d", i), kind, fixedNow)
		it.FileName = fmt.Sprintf("系列_%d-Alpha alpha_中文.jpg", i%31)
		it.CreatedAt = fixedNow.Add(-time.Duration(i) * 6 * time.Hour)
		it.Tags = []string{"手动", fmt.Sprintf("tag-%d", i%29), "手动", "alpha"}
		it.Stats = Stats{ViewCount: i % 7, PlayCount: i % 5, BrowseSeconds: int64(i * 13)}
		if i%3 != 0 {
			last := fixedNow.Add(-time.Duration(i) * 3 * time.Hour)
			it.Stats.LastViewedAt = &last
		}
		it.LikeCount, it.ShownToday = i%4, i%3
		if i%7 == 0 {
			it.Tags = nil
		}
		items[i] = it
	}
	return items
}
