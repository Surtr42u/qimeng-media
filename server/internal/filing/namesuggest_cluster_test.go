package filing

import (
	"fmt"
	"math/rand"
	"strings"
	"testing"
)

func TestShortestFamilyPrefixMatchesOriginalScan(t *testing.T) {
	keys := []string{"根", "根 角色", "根 角色 特写", "根 角色 特写 礼服", "根 角色甲", "根 角色甲乙", "根 角", "根角 子", "other", "other root child", "other root", "独立", "🌟 子"}
	for i := 0; i < 1000; i++ {
		keys = append(keys, fmt.Sprintf("系列%d 角色%d%s", i%13, i%29, []string{"", " 特写", " 特写 礼服", " 舞台"}[i%4]))
	}
	for seed := int64(0); seed < 8; seed++ {
		rng := rand.New(rand.NewSource(seed))
		fams := make(map[string]*nameFamily)
		for _, i := range rng.Perm(len(keys)) {
			key := keys[i]
			fams[key] = &nameFamily{famKey: key}
		}
		for key := range fams {
			// 独立保留优化前的全族扫描，验证多层前缀与插入/遍历次序均不影响结果。
			want := key
			for other := range fams {
				if len(other) < len(want) && strings.HasPrefix(want, other+" ") {
					want = other
				}
			}
			if got := shortestFamilyPrefix(key, fams); got != want {
				t.Fatalf("key=%q got=%q want=%q", key, got, want)
			}
		}
	}
}
