package filing

// workname_test.go：作品编号/组图页码解析与联想的表驱动回归（2026-11-07 用户
// 补充：作品编号按作品递增、组内页码不递增作品编号）。只测纯函数，合成文件名，
// 不读取真实媒体库。旧 namesuggest_test.go 的既有语义测试保持不放宽。
//
// 覆盖：规范连续名、单组/多组/组间空号、组图+单图混合、乱序、全角括号、
// 零填充、中文紧贴、标记前缀、仅括号序号旧语义回归、组内页不被误判为独立作品。

import (
	"reflect"
	"strconv"
	"testing"
)

func TestParseWorkName(t *testing.T) {
	cases := []struct {
		name   string
		base   string
		want   workName
		wantOK bool
	}{
		{
			name:   "组图页半角括号",
			base:   "守望先锋 朱诺 11 (1)",
			want:   workName{familyPrefix: "守望先锋 朱诺", separator: " ", workNumber: 11, workWidth: 2, pageNumber: 1, pageWidth: 1, hasPage: true},
			wantOK: true,
		},
		{
			name:   "组图页末页",
			base:   "守望先锋 朱诺 11 (4)",
			want:   workName{familyPrefix: "守望先锋 朱诺", separator: " ", workNumber: 11, workWidth: 2, pageNumber: 4, pageWidth: 1, hasPage: true},
			wantOK: true,
		},
		{
			name:   "组图页全角括号",
			base:   "名 11（3）",
			want:   workName{familyPrefix: "名", separator: " ", workNumber: 11, workWidth: 2, pageNumber: 3, pageWidth: 1, hasPage: true},
			wantOK: true,
		},
		{
			name:   "组图页作品编号与页码零填充",
			base:   "名 001 (02)",
			want:   workName{familyPrefix: "名", separator: " ", workNumber: 1, workWidth: 3, pageNumber: 2, pageWidth: 2, hasPage: true},
			wantOK: true,
		},
		{
			name:   "组图页中文紧贴无分隔",
			base:   "朱诺11(2)",
			want:   workName{familyPrefix: "朱诺", separator: "", workNumber: 11, workWidth: 2, pageNumber: 2, pageWidth: 1, hasPage: true},
			wantOK: true,
		},
		{
			name:   "组图页标记前缀",
			base:   "作品 No.01 (3)",
			want:   workName{familyPrefix: "作品", separator: " ", marker: "No.", workNumber: 1, workWidth: 2, pageNumber: 3, pageWidth: 1, hasPage: true},
			wantOK: true,
		},
		{
			name:   "独立作品裸序号",
			base:   "海景 12",
			want:   workName{familyPrefix: "海景", separator: " ", workNumber: 12, workWidth: 2},
			wantOK: true,
		},
		{
			name:   "独立作品零填充",
			base:   "海景 007",
			want:   workName{familyPrefix: "海景", separator: " ", workNumber: 7, workWidth: 3},
			wantOK: true,
		},
		{
			name:   "仅括号序号属于旧语义而非作品语法",
			base:   "旅行 (2)",
			wantOK: false,
		},
		{
			name:   "括号修饰非数字不是组图页",
			base:   "夕阳（特写）",
			wantOK: false,
		},
		{
			name:   "纯数字无作品基名",
			base:   "12",
			wantOK: false,
		},
		{
			name:   "组图页码为0不按组图页",
			base:   "名 11 (0)",
			wantOK: false,
		},
		{
			name:   "方括号保持旧序号语义",
			base:   "名 11 [2]",
			wantOK: false,
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, ok := parseWorkName(c.base)
			if ok != c.wantOK {
				t.Fatalf("parseWorkName(%q) ok=%v, want %v", c.base, ok, c.wantOK)
			}
			if !reflect.DeepEqual(got, c.want) {
				t.Fatalf("parseWorkName(%q)=%#v, want %#v", c.base, got, c.want)
			}
		})
	}
}

func TestSuggestSeriesNamesGroupPages(t *testing.T) {
	sequential := func(prefix string, from, to int) []string {
		out := make([]string, 0, to-from+1)
		for i := from; i <= to; i++ {
			out = append(out, prefix+strconv.Itoa(i)+".png")
		}
		return out
	}
	pages := func(prefix string, workNumber, from, to int) []string {
		out := make([]string, 0, to-from+1)
		for i := from; i <= to; i++ {
			out = append(out, prefix+strconv.Itoa(workNumber)+" ("+strconv.Itoa(i)+").png")
		}
		return out
	}

	cases := []struct {
		name  string
		names []string
		q     string
		want  []string
	}{
		{
			name:  "规范连续名+新组图：组图占用11，下一独立作品12",
			names: append(sequential("守望先锋 朱诺 ", 1, 10), pages("守望先锋 朱诺 ", 11, 1, 4)...),
			q:     "守望先锋 朱诺",
			want:  []string{"守望先锋 朱诺 12"},
		},
		{
			name:  "单组四页只占一个作品编号而非逐页递增",
			names: pages("名 ", 1, 1, 4),
			q:     "名",
			want:  []string{"名 2"},
		},
		{
			name:  "多组图+组间空号：取最大已占作品编号+1",
			names: append(pages("名 ", 1, 1, 2), pages("名 ", 3, 1, 3)...),
			q:     "名",
			want:  []string{"名 4"},
		},
		{
			name:  "组图与单图混合且乱序",
			names: []string{"名 3 (1).png", "名 11 (2).png", "名 2.png", "名 11 (1).png"},
			q:     "名",
			want:  []string{"名 12"},
		},
		{
			name:  "全角括号组图页",
			names: []string{"名 11（1）.png", "名 11（2）.png"},
			q:     "名",
			want:  []string{"名 12"},
		},
		{
			name:  "零填充作品编号位宽保留",
			names: []string{"名 007 (1).png", "名 007 (2).png"},
			q:     "名",
			want:  []string{"名 008"},
		},
		{
			name:  "中文紧贴变体",
			names: []string{"朱诺11(1).png", "朱诺11(2).png"},
			q:     "朱诺",
			want:  []string{"朱诺12"},
		},
		{
			name:  "组图页保留作品编号标记风格",
			names: []string{"作品 No.01 (1).png", "作品 No.01 (2).png"},
			q:     "作品",
			want:  []string{"作品 No.02"},
		},
		{
			name:  "仅括号序号旧语义回归：仍推进括号序号",
			names: []string{"旅行 (1).jpg", "旅行 (2).jpg"},
			q:     "旅行",
			want:  []string{"旅行 (3)"},
		},
		{
			name:  "组图页不会被当成独立作品逐张推荐",
			names: pages("名 ", 11, 1, 3),
			q:     "名",
			want:  []string{"名 12"},
		},
		{
			name:  "同号的独立作品与组图页只占用一次作品编号",
			names: []string{"名 11.png", "名 11 (1).png"},
			q:     "名",
			want:  []string{"名 12"},
		},
		{
			name:  "缺页保留现状：只按已占最大页判断，不补页也不影响作品编号",
			names: []string{"名 11 (1).png", "名 11 (2).png", "名 11 (4).png"},
			q:     "名",
			want:  []string{"名 12"},
		},
		{
			name:  "补图追加页码不改变作品编号",
			names: []string{"名 10.png", "名 11 (1).png", "名 11 (2).png", "名 11 (3).png"},
			q:     "名",
			want:  []string{"名 12"},
		},
		{
			name:  "基名与作品编号间多个空格原样保留",
			names: []string{"名  11 (1).png", "名  11 (2).png"},
			q:     "名",
			want:  []string{"名  12"},
		},
		{
			name: "组图页与无序号角色文件共存",
			names: []string{
				"法环 菈妮 01 (1).png", "法环 菈妮 01 (2).png",
				"法环 梅琳娜.png",
			},
			q:    "法环",
			want: []string{"法环 菈妮 02", "法环 梅琳娜 2"},
		},
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

func TestSuggestSeriesNamesGroupPagesDeterministic(t *testing.T) {
	a := []string{"名 11 (2).png", "名 10.png", "名 11 (1).png", "名 9.png"}
	b := []string{"名 9.png", "名 11 (1).png", "名 10.png", "名 11 (2).png"}
	want := []string{"名 12"}
	if got := SuggestSeriesNames(a, "名"); !reflect.DeepEqual(got, want) {
		t.Fatalf("顺序一=%#v, want %#v", got, want)
	}
	if got := SuggestSeriesNames(b, "名"); !reflect.DeepEqual(got, want) {
		t.Fatalf("顺序二=%#v, want %#v", got, want)
	}
}
