package sourcematcher

// fallback_test.go：命名规约兜底提取层（DOMAIN_RULES §4「命名规约兜底提取」，
// 2026-10-03）——表零命中时按 `出处  角色名 序号` 位置提取；表命中非空时
// 本层零参与（含子串命中，锁定既有结果零回归）。

import (
	"slices"
	"testing"
)

// TestExtractFallbackSingleToken 单角色名提取：序号前的词即角色，大小写保留。
func TestExtractFallbackSingleToken(t *testing.T) {
	m := newTestMatcher()
	tests := []struct {
		file string
		want []string
	}{
		{"守望先锋  新角色 1.mp4", []string{"新角色"}},
		{"守望先锋  Dmon 1.mp4", []string{"Dmon"}}, // 大小写保留（无词条改名时按原名）
		{"守望先锋  Melody.mp4", []string{"Melody"}},
	}
	for _, tt := range tests {
		if src, chars := m.MatchAll(tt.file); src != "守望先锋" || !slices.Equal(chars, tt.want) {
			t.Errorf("MatchAll(%q) = (%q, %v), want (守望先锋, %v)", tt.file, src, chars, tt.want)
		}
	}
}

// TestExtractFallbackNumberTermination 纯数字/括号序号终止收集：序号之后的
// 描述词（小长篇/婚纱类）不得混入角色桶。
func TestExtractFallbackNumberTermination(t *testing.T) {
	m := newTestMatcher()
	tests := []struct {
		file string
		want []string
	}{
		{"守望先锋  新角色 2 小长篇 1.mp4", []string{"新角色"}},
		{"守望先锋  新角色 5 (1).jpg", []string{"新角色"}},
		{"守望先锋  1.jpg", nil},  // 纯序号无角色
		{"守望先锋.jpg", nil},     // 剥离出处后无剩余
	}
	for _, tt := range tests {
		if _, chars := m.MatchAll(tt.file); !slices.Equal(chars, tt.want) {
			t.Errorf("MatchAll(%q) 角色 = %v, want %v", tt.file, chars, tt.want)
		}
	}
}

// TestExtractFallbackQualityWordGuard "8K"/"1080p" 形（数字开头后跟 ASCII
// 字母）表不认识即丢弃且不终止——防画质词混入；后续真角色词仍可提取。
func TestExtractFallbackQualityWordGuard(t *testing.T) {
	m := newTestMatcher()
	if _, chars := m.MatchAll("守望先锋  新角色 8K 1.mp4"); !slices.Equal(chars, []string{"新角色"}) {
		t.Errorf("画质词防护: %v, want [新角色]", chars)
	}
	if _, chars := m.MatchAll("守望先锋  1080p 1.mp4"); len(chars) != 0 {
		t.Errorf("纯画质词应无角色: %v", chars)
	}
}

// TestExtractFallbackSeparators 分隔符：词内 +/& 拆分、裸 x 作分隔词丢弃；
// "+" 链上的无主段（无出处前缀的角色名）归入本出处提取。
func TestExtractFallbackSeparators(t *testing.T) {
	m := newTestMatcher()
	tests := []struct {
		file string
		want []string
	}{
		{"守望先锋  AA+BB 1.mp4", []string{"AA", "BB"}},
		{"守望先锋  AA&BB.mp4", []string{"AA", "BB"}},
		{"守望先锋  Melody x Lawa 1.mp4", []string{"Lawa", "Melody"}},
		{"守望先锋  CC DD 1.png", []string{"CC", "DD"}},
	}
	for _, tt := range tests {
		if _, chars := m.MatchAll(tt.file); !slices.Equal(chars, tt.want) {
			t.Errorf("MatchAll(%q) 角色 = %v, want %v", tt.file, chars, tt.want)
		}
	}
}

// TestExtractFallbackPartialTableHitBlocks 表层部分命中即止：已认识的角色照
// 常命中，同文件里未认识的名字不再兜底提取（零回归取舍，见 DOMAIN_RULES §4
// 兜底层——想让它进桶，给它加词条或改文件名）。
func TestExtractFallbackPartialTableHitBlocks(t *testing.T) {
	m := newTestMatcher()
	if _, chars := m.MatchAll("守望先锋  雾子 新角色 1.png"); !slices.Equal(chars, []string{"雾子"}) {
		t.Errorf("部分命中即止: %v, want [雾子]", chars)
	}
}

// TestExtractFallbackTableWinsZeroRegression 表命中（含子串命中）时兜底层
// 零参与：既有资产的角色结果一字不变。
func TestExtractFallbackTableWinsZeroRegression(t *testing.T) {
	m := newTestMatcher()
	tests := []struct {
		file string
		want []string
	}{
		{"守望先锋  DVA 1.mp4", []string{"DVA"}},           // 表精确命中
		{"守望先锋  天使黑猫 1.png", []string{"天使"}},       // 子串命中：不得再产「天使黑猫」
		{"守望先锋  天使 9 小恶魔天使.mp4", []string{"天使"}}, // 序号后描述词同表同果
	}
	for _, tt := range tests {
		if _, chars := m.MatchAll(tt.file); !slices.Equal(chars, tt.want) {
			t.Errorf("MatchAll(%q) 角色 = %v, want %v", tt.file, chars, tt.want)
		}
	}
}

// TestExtractFallbackUnknownSource 未命中出处（归"其他"）不提取角色：
// 不知道出处边界就不知道角色名从哪开始。
func TestExtractFallbackUnknownSource(t *testing.T) {
	m := newTestMatcher()
	if src, chars := m.MatchAll("完全未知出处  新角色 1.mp4"); src != "" || chars != nil {
		t.Errorf("未知出处 MatchAll = (%q, %v), want (\"\", nil)", src, chars)
	}
}
