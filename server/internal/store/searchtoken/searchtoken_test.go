package searchtoken

import "testing"

// TestMatchNumberToken 锁定数字 token 的独立数字段语义（2026-11-07 修复点）。
func TestMatchNumberToken(t *testing.T) {
	cases := []struct {
		name  string
		text  string
		token string
		want  bool
	}{
		{"独立数字命中", "朱诺 11.png", "11", true},
		{"前接数字不命中", "朱诺 110.png", "11", false},
		{"后接数字不命中", "朱诺 110.png", "110", true},
		{"年份不命中短数字", "朱诺 2011.png", "11", false},
		{"年份完整命中", "朱诺 2011.png", "2011", true},
		{"分辨率命中数字段", "朱诺 1080p.png", "1080", true},
		{"短数字不命中分辨率", "朱诺 1080p.png", "10", false},
		{"文件开头数字段", "11.png", "11", true},
		{"文件末尾数字段", "朱诺11", "11", true},
		{"中文紧邻数字段", "朱诺11号.png", "11", true},
		{"小数整体不拆", "1.5倍速.mp4", "1", true}, // 数字段定义只看前后 ASCII 数字
		{"小数内部不命中", "11.5.mp4", "1", false},
		{"空 token 不命中", "朱诺 11.png", "", false},
		{"无数字不命中", "朱诺.png", "11", false},
	}
	for _, c := range cases {
		if got := Match(c.text, c.token); got != c.want {
			t.Errorf("%s: Match(%q, %q) = %v，期望 %v", c.name, c.text, c.token, got, c.want)
		}
	}
}

// TestMatchPageToken 锁定括号页码 token 与裸数字的互斥关系。
func TestMatchPageToken(t *testing.T) {
	cases := []struct {
		name  string
		text  string
		token string
		want  bool
	}{
		{"半角页命中", "朱诺 11 (2).png", "2", false}, // 裸数字不命中页码
		{"半角页 token", "朱诺 11 (2).png", "(2)", true},
		{"全角页 token", "朱诺 11（2）.png", "(2)", true}, // 全半角互认
		{"全角 token 半角正文", "朱诺 11 (2).png", "（2）", true},
		{"方括号页 token", "套图 [3].jpg", "[3]", true},
		{"直角引号页 token", "套图【4】.jpg", "【4】", true},
		{"页号被更长数字包裹不命中", "朱诺 2011.png", "(2)", false},
		{"括号内非纯数字不按页匹配", "朱诺 (a2).png", "(2)", false},
		{"空括号回退子串", "字面(2)示例.png", "(2)", true}, // 括号页 token 命中；正文无独立数字也无妨
		{"页码相邻数字分隔", "朱诺 112(2).png", "(2)", true},
	}
	for _, c := range cases {
		if got := Match(c.text, c.token); got != c.want {
			t.Errorf("%s: Match(%q, %q) = %v，期望 %v", c.name, c.text, c.token, got, c.want)
		}
	}
}

// TestMatchNonDigitToken 锁定旧子串语义未回归。
func TestMatchNonDigitToken(t *testing.T) {
	cases := []struct {
		text  string
		token string
		want  bool
	}{
		{"赛博朋克 2077", "赛博朋克", true},
		{"赛博朋克 2077", "2077", true}, // 独立数字段命中（混合语料回归）
		{"赛博朋克 2077", "赛博", true},
		{"DVA", "dva", true}, // ASCII 大小写折叠（两侧都折叠）
		{"dva", "DVA", true},
		{"100%完结", "100%", true}, // % 字面匹配，无 LIKE 魔法
		{"abc_def", "c_d", true}, // _ 字面匹配
		{"1080p", "1080P", true}, // 混合 token 仍走子串 + 折叠
		{"1080p", "1080p", true},
		{"1080p", "1080", true}, // 数字 token 命中分辨率数字段
		{"朱诺", "露西", false},
	}
	for _, c := range cases {
		if got := Match(c.text, c.token); got != c.want {
			t.Errorf("Match(%q, %q) = %v，期望 %v", c.text, c.token, got, c.want)
		}
	}
}

// TestASCIILowerKeepsNonASCII 保证折叠不改变非 ASCII 内容与字节长度。
func TestASCIILowerKeepsNonASCII(t *testing.T) {
	input := "Fate/Grand Order 命运-冠位指定"
	want := "fate/grand order 命运-冠位指定"
	if got := asciiLower(input); got != want {
		t.Fatalf("asciiLower(%q) = %q，期望 %q", input, got, want)
	}
	if len(asciiLower(input)) != len(input) {
		t.Fatalf("asciiLower 改变了字节长度")
	}
}
