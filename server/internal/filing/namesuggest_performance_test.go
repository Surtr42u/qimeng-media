package filing

import (
	"fmt"
	"testing"
)

// 相同文件规模、不同命名族数量；只测纯函数，不读取真实媒体库。
func BenchmarkSuggestSeriesNamesFamilies(b *testing.B) {
	for _, tc := range []struct {
		name   string
		n      int
		nested bool
	}{{"flat_500", 500, false}, {"flat_2000", 2000, false}, {"flat_8000", 8000, false}, {"nested_2000", 2000, true}} {
		names := make([]string, tc.n)
		for i := range names {
			if tc.nested {
				names[i] = fmt.Sprintf("作品 角色%05d%s 01.jpg", i/4, []string{"", " 特写", " 特写 礼服", " 舞台"}[i%4])
			} else {
				names[i] = fmt.Sprintf("作品 角色%05d 01.jpg", i)
			}
		}
		b.Run(tc.name, func(b *testing.B) {
			b.ReportAllocs()
			for i := 0; i < b.N; i++ {
				if got := SuggestSeriesNames(names, "作品"); len(got) != maxNameSuggestions {
					b.Fatal("联想结果缺项")
				}
			}
		})
	}
}
