package recommend

import (
	"fmt"
	"math/rand"
	"reflect"
	"sort"
	"testing"
)

// 保留旧的两次全量排序作为输出基线，约束Top-20、同分字典序及输入不变。
func legacyTopTags(m map[string]float64) map[string]bool {
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

func TestTopTagsBoundedSelectionMatchesLegacy(t *testing.T) {
	for _, size := range []int{0, 1, 19, 20, 21, 100, 10000} {
		for seed := int64(0); seed < 8; seed++ {
			rng := rand.New(rand.NewSource(seed))
			m := make(map[string]float64, size)
			for _, i := range rng.Perm(size) {
				m[fmt.Sprintf("tag-%05d", i)] = float64(rng.Intn(7)) / 7
			}
			before := make(map[string]float64, len(m))
			for k, v := range m {
				before[k] = v
			}
			if got, want := topTagsOf(m), legacyTopTags(m); !reflect.DeepEqual(got, want) {
				t.Fatalf("size=%d seed=%d set changed", size, seed)
			}
			if !reflect.DeepEqual(m, before) {
				t.Fatal("input mutated")
			}
		}
	}
}

var topTagsBenchmarkResult map[string]bool

func BenchmarkTopTagsSelection(b *testing.B) {
	for _, size := range []int{50, 20000} {
		m := make(map[string]float64, size)
		for i := 0; i < size; i++ {
			m[fmt.Sprintf("tag-%05d", i)] = float64(i%71) / 71
		}
		for name, fn := range map[string]func(map[string]float64) map[string]bool{"legacy": legacyTopTags, "bounded": topTagsOf} {
			b.Run(fmt.Sprintf("%d/%s", size, name), func(b *testing.B) {
				b.ReportAllocs()
				for b.Loop() {
					topTagsBenchmarkResult = fn(m)
				}
			})
		}
	}
}
