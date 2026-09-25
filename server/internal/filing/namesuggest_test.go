package filing

// namesuggest_test.go：作品名序号联想纯函数的表驱动回归（铁律 3：纯函数
// 行为由单元测试锁定）。覆盖端点描述的口径：规范化匹配（去扩展名/空白折叠/
// ASCII 大小写不敏感/少空格吸附）、两种序号风格、混合风格同族、序号跨扩展
// 名共用、多族排序与 cap 3、无序号族跳过、空 q、无命中。

import (
	"reflect"
	"testing"
)

func TestSuggestSeriesNames(t *testing.T) {
	// openapi.yaml 端点描述的逐字示例：裸序号跨扩展名共用 + 全角括号族跳过。
	exampleNames := []string{"守望先锋 DVA 11.png", "守望先锋 DVA 12.mp4", "守望先锋 DVA（特写）.png"}

	cases := []struct {
		name  string
		names []string
		q     string
		want  []string
	}{
		{"协议示例：裸序号推进+跨扩展名+全角括号族跳过", exampleNames, "守望先锋dva", []string{"守望先锋 DVA 13"}},
		{"括号序号推进", []string{"旅行 (1).jpg", "旅行 (2).jpg"}, "旅行", []string{"旅行 (3)"}},
		{"混合风格同族：最大序号成员的风格胜出", []string{"名 12.png", "名 (2).jpg"}, "名", []string{"名 13"}},
		{"序号平局取裸风格", []string{"x (5).png", "x 5.jpg"}, "x", []string{"x 6"}},
		{"大写输入吸附库内小写写法", []string{"守望先锋 dva 11.png"}, "守望先锋DVA", []string{"守望先锋 dva 12"}},
		{"小写输入吸附库内大写写法", exampleNames, "守望先锋DVA", []string{"守望先锋 DVA 13"}},
		{"输入带尾随空格折叠命中", exampleNames, "  守望先锋 dva  ", []string{"守望先锋 DVA 13"}},
		{"输入多空格折叠命中", []string{"守望先锋 DVA 11.png"}, "守望先锋   DVA", []string{"守望先锋 DVA 12"}},
		{"建议保留库内多空格原样", []string{"组图  (2).jpg"}, "组图", []string{"组图  (3)"}},
		{"扩展名去重前缀命中", []string{"海景 1.png", "海景.backup.jpg"}, "海景", []string{"海景 2"}},
		{"q 带扩展名同命中", exampleNames, "守望先锋dva.png", []string{"守望先锋 DVA 13"}},
		{"多族排序：命中数降序、族键字典序、cap 3", []string{
			"aa 1.png", "aa 2.png", "aa 3.png",
			"ab 1.png", "ab 2.png", "ab 3.png",
			"ac 1.png",
			"ad 1.png", "ad 2.png",
		}, "a", []string{"aa 4", "ab 4", "ad 3"}},
		{"无序号族不产生建议", []string{"夕阳（特写）.png", "夕阳风景.jpg"}, "夕阳", []string{}},
		{"序号族与无序号命中并存：只出序号族", []string{"名 3.png", "名单.jpg"}, "名", []string{"名 4"}},
		{"整名纯数字无名字部分不构成系列", []string{"12.png"}, "1", []string{}},
		{"无空白紧贴数字不算序号", []string{"v2.png"}, "v", []string{}},
		{"全角括号紧贴不算序号风格", []string{"名（2）.png"}, "名", []string{}},
		{"括号内容非数字不算序号", []string{"名 (特写).png"}, "名", []string{}},
		{"空 q → 空列表", exampleNames, "", []string{}},
		{"纯空白 q → 空列表", exampleNames, "   ", []string{}},
		{"无命中 → 空列表", exampleNames, "不存在", []string{}},
		{"空库 → 空列表", nil, "守望先锋", []string{}},
		{"点文件按无扩展名处理", []string{".gitignore"}, ".gitigno", []string{}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := SuggestSeriesNames(c.names, c.q)
			if !reflect.DeepEqual(got, c.want) {
				t.Fatalf("SuggestSeriesNames(%q)=%#v, want %#v", c.q, got, c.want)
			}
		})
	}
}

// TestSuggestSeriesNamesDeterministic：相同输入不同顺序 → 相同建议
// （族排序与成员平局判定都不得依赖输入顺序）。
func TestSuggestSeriesNamesDeterministic(t *testing.T) {
	a := []string{"名 (2).jpg", "名 12.png", "名（特写）.png"}
	b := []string{"名（特写）.png", "名 12.png", "名 (2).jpg"}
	want := []string{"名 13"}
	if got := SuggestSeriesNames(a, "名"); !reflect.DeepEqual(got, want) {
		t.Fatalf("顺序一=%#v, want %#v", got, want)
	}
	if got := SuggestSeriesNames(b, "名"); !reflect.DeepEqual(got, want) {
		t.Fatalf("顺序二=%#v, want %#v", got, want)
	}
}
