package sourcematcher

import (
	"slices"
	"testing"
)

// newTestMatcher 构造独立测试实例（每测试自带缓存与索引，互不污染）。
func newTestMatcher() *Matcher {
	return New(0) // 0 = 用 DefaultCacheSize
}

// TestMatchLegacySemantics 照译旧项目 SourceMatcherTest.kt 的 21 个 match()
// 断言（子测试名 = 旧测试方法名，覆盖：精确匹配/变体/最长优先/未命中/
// 版本合并/大小写与空格容差）。
func TestMatchLegacySemantics(t *testing.T) {
	m := newTestMatcher()
	tests := []struct {
		file string
		want string
	}{
		// 精确匹配已知出处
		{"守望先锋", "守望先锋"},
		{"守望先锋.jpg", "守望先锋"},    // 带扩展名
		{"守望先锋_天使.jpg", "守望先锋"}, // 出处名开头 + 附加内容
		// 变体匹配
		{"LOL", "英雄联盟"},           // "LOL" → 英雄联盟
		{"OW_天使.jpg", "守望先锋"},     // "OW" → 守望先锋
		{"Overwatch", "守望先锋"},     // 英文名变体 → 中文 canonical
		{"Overwatch.jpg", "守望先锋"}, // 带扩展名的变体
		{"2077", "赛博朋克2077"},      // "2077" 是赛博朋克的变体
		// 最长优先匹配
		{"尼尔机械纪元", "尼尔 机械纪元"},        // 长变体优先于短变体（尼尔 人工生命 不应命中）
		{"NieR Automata", "尼尔 机械纪元"}, // 英文变体
		{"生化危机4", "生化危机"},            // 版本号合并
		// 未命中返回空
		{"随便一个名字.jpg", ""},
		{"", ""},
		{"RandomTitle.jpg", ""},
		// 版本号合并
		{"铁拳8", "铁拳"},
		{"铁拳7", "铁拳"},
		{"Tekken 8", "铁拳"},
		{"最终幻想7", "最终幻想"},
		{"FF7", "最终幻想"},
		// 大小写与空格容差
		{"overwatch", "守望先锋"},
		{"守望先锋 归来", "守望先锋"},
	}
	for _, tt := range tests {
		t.Run("legacy/"+tt.file, func(t *testing.T) {
			if got := m.Match(tt.file); got != tt.want {
				t.Errorf("Match(%q) = %q, want %q", tt.file, got, tt.want)
			}
		})
	}
}

// TestMatchCustomSourceMatchesAfterUpdate 照译旧测试：更新自定义出处后应能匹配
// （自定义名 canonical = 自定义名本身，DOMAIN_RULES §4）。
func TestMatchCustomSourceMatchesAfterUpdate(t *testing.T) {
	m := newTestMatcher()
	m.UpdateCustomSources([]string{"我的自定义游戏"})
	if got := m.Match("我的自定义游戏"); got != "我的自定义游戏" {
		t.Errorf("Match(自定义) = %q, want %q", got, "我的自定义游戏")
	}
	m.UpdateCustomSources(nil) // 清理
}

// TestMatchCustomSourceClearedNoLongerMatches 照译旧测试：清除自定义出处后不再匹配。
func TestMatchCustomSourceClearedNoLongerMatches(t *testing.T) {
	m := newTestMatcher()
	m.UpdateCustomSources([]string{"临时游戏"})
	m.UpdateCustomSources(nil)
	if got := m.Match("临时游戏"); got != "" {
		t.Errorf("清除后 Match(临时游戏) = %q, want 空", got)
	}
}

// TestStripSourceDigitProtection 数字保护（DOMAIN_RULES §4 / 旧 GUIDE_ALGORITHM
// L244：^\d+(?![a-zA-Z]) 不剥 "2B"/"9S"）。若保护失效（"2" 被剥成 "b_主角"），
// 别名 "2b" 无法命中，角色断言即锁定保护语义。
func TestStripSourceDigitProtection(t *testing.T) {
	m := newTestMatcher()
	source, chars := m.MatchAll("尼尔机械纪元 2B_主角.jpg")
	if source != "尼尔 机械纪元" {
		t.Errorf("出处 = %q, want %q", source, "尼尔 机械纪元")
	}
	if !slices.Equal(chars, []string{"2B"}) {
		t.Errorf("角色 = %v, want [2B]（数字保护失效时 2b 别名将无法命中）", chars)
	}
	// "9S" 无检索表条目（数据表未收录），直接验证 stripSource 的保护语义：
	// 剥离开头出处后 "9s" 前导数字后跟字母，必须整段保留。
	rest := m.snap.Load().stripSource("尼尔机械纪元 9S", "尼尔 机械纪元")
	if rest != "9s" {
		t.Errorf("stripSource 后剩余 = %q, want %q（9S 数字保护失效）", rest, "9s")
	}
}

// TestStripSourceLeadingPunct 开头标点清理（旧 GUIDE_ALGORITHM L244 第一步
// ^[\s+_\-()（）]+）：剥离开头出处后残留的连接符（-/_/空格）必须清理，
// 不干扰角色子串匹配。
func TestStripSourceLeadingPunct(t *testing.T) {
	m := newTestMatcher()
	for _, file := range []string{
		"守望先锋-天使.jpg",  // 剥后剩 "-天使"
		"守望先锋_天使.jpg",  // 剥后剩 "_天使"
		"守望先锋 _天使.jpg", // 剥后剩 "_天使"（空格已折叠，下划线保留）
		"守望先锋()天使.jpg", // 剥后剩 "()天使"
	} {
		if _, chars := m.MatchAll(file); !slices.Equal(chars, []string{"天使"}) {
			t.Errorf("MatchAll(%q) 角色 = %v, want [天使]", file, chars)
		}
	}
}

// TestMatchAllMultiSource 多出处 "+" 分段（DOMAIN_RULES §4 / 旧 GUIDE_ALGORITHM
// L259 原例）：按 "+" 分段分别匹配出处并合并（按段顺序去重拼接）；角色 = 各命中
// 出处用整名匹配后合并、canonical 字典序排序。
func TestMatchAllMultiSource(t *testing.T) {
	m := newTestMatcher()
	source, chars := m.MatchAll("恶魔战士+铁拳8 莫妮卡+莉莉.jpg")
	if source != "恶魔战士+铁拳" {
		t.Errorf("出处 = %q, want %q（两出处都命中、按段顺序拼接）", source, "恶魔战士+铁拳")
	}
	// 字典序：莉(U+8389) < 莫(U+83AB)；与输入段顺序无关
	if !slices.Equal(chars, []string{"莉莉", "莫妮卡"}) {
		t.Errorf("角色 = %v, want [莉莉 莫妮卡]", chars)
	}
}

// TestMultiCharacterOrderInsensitive 多角色字典序拼接与输入顺序无关：
// "天使+dva" 与 "dva 天使" 必须归入同一药丸（canonical 字典序）。
func TestMultiCharacterOrderInsensitive(t *testing.T) {
	m := newTestMatcher()
	s1, c1 := m.MatchAll("守望先锋 天使+dva.jpg")
	s2, c2 := m.MatchAll("守望先锋 dva 天使.jpg")
	wantChars := []string{"DVA", "天使"} // canonical 原样（DVA 大写），'D'(0x44) < 天(U+5929)
	if s1 != "守望先锋" || s2 != "守望先锋" {
		t.Errorf("出处 = %q / %q, want 均为 守望先锋", s1, s2)
	}
	if !slices.Equal(c1, wantChars) || !slices.Equal(c2, wantChars) {
		t.Errorf("角色 = %v / %v, want 均为 %v（字典序与输入顺序无关）", c1, c2, wantChars)
	}
}

// TestAliasLengthDescending 别名长度降序（旧 GUIDE_ALGORITHM L244：确保
// "Miss Fortune" 优先于 "MF" 匹配）——长短别名命中同一角色；同一位置长别名
// 先占用（区域重叠检测）时不得因短别名重复计角色。
func TestAliasLengthDescending(t *testing.T) {
	m := newTestMatcher()
	for _, file := range []string{
		"英雄联盟 Miss Fortune.jpg",    // 长别名命中
		"英雄联盟 MF.jpg",              // 短别名命中（同角色）
		"英雄联盟 MF Miss Fortune.jpg", // 长别名先占用区域，短别名同 canonical 去重
	} {
		if _, chars := m.MatchAll(file); !slices.Equal(chars, []string{"好运姐"}) {
			t.Errorf("MatchAll(%q) 角色 = %v, want [好运姐]", file, chars)
		}
	}
}

// TestCacheSemantics 缓存语义（旧 matchAllCache 语义）：同文件名二次匹配结果
// 一致；UpdateCustomSources 清空缓存，自定义出处增删后重新匹配（miss→hit→miss）。
func TestCacheSemantics(t *testing.T) {
	m := newTestMatcher()
	s1, c1 := m.MatchAll("守望先锋 天使+dva.jpg")
	s2, c2 := m.MatchAll("守望先锋 天使+dva.jpg") // 二次调用走缓存
	if s1 != s2 || !slices.Equal(c1, c2) {
		t.Errorf("二次匹配不一致：(%q,%v) vs (%q,%v)", s1, c1, s2, c2)
	}
	// 更新自定义出处（清缓存）后：既有文件重新计算结果不变，自定义名命中
	m.UpdateCustomSources([]string{"我的自定义游戏"})
	if s3, c3 := m.MatchAll("守望先锋 天使+dva.jpg"); s3 != s1 || !slices.Equal(c3, c1) {
		t.Errorf("更新自定义出处后既有文件匹配漂移：(%q,%v) vs (%q,%v)", s3, c3, s1, c1)
	}
	if got := m.Match("我的自定义游戏"); got != "我的自定义游戏" {
		t.Errorf("自定义出处未命中: %q", got)
	}
	// 清除自定义出处（再次清缓存）后重新匹配为未命中
	m.UpdateCustomSources(nil)
	if got := m.Match("我的自定义游戏"); got != "" {
		t.Errorf("清除自定义出处后仍命中: %q", got)
	}
}

// TestBuiltinGroupsDataIntegrity 数据表保真：组数断言 + 抽查 3 组关键字段
// （翻译保真：字段与旧 Kotlin 数据一一对应）。
func TestBuiltinGroupsDataIntegrity(t *testing.T) {
	// DOMAIN_RULES §4 记"131 个"，实测旧源文件数据组为 130（131 次出现含
	// 1 行 data class 定义），以源数据保真为准（见 source_groups_data.go 头注释勘误）。
	if len(builtinGroups) != 130 {
		t.Fatalf("len(builtinGroups) = %d, want 130", len(builtinGroups))
	}
	// 抽查 1：尼尔 机械纪元（长变体 + 数字开头角色）
	g := findGroup(t, "尼尔 机械纪元")
	for _, v := range []string{"尼尔 机械纪元", "尼尔机械纪元", "NieR Automata", "NieR"} {
		if !slices.Contains(g.Variants, v) {
			t.Errorf("尼尔组变体缺 %q: %v", v, g.Variants)
		}
	}
	if c := findChar(t, g, "2B"); !slices.Contains(c.Aliases, "2b") || !slices.Contains(c.Aliases, "二号机") {
		t.Errorf("2B 别名异常: %v", c.Aliases)
	}
	// 抽查 2：守望先锋（缩写变体 + 带空格变体 + 英文角色别名）
	g = findGroup(t, "守望先锋")
	for _, v := range []string{"守望先锋 归来", "守望先锋2", "Overwatch", "OW"} {
		if !slices.Contains(g.Variants, v) {
			t.Errorf("守望先锋组变体缺 %q: %v", v, g.Variants)
		}
	}
	if c := findChar(t, g, "DVA"); !slices.Contains(c.Aliases, "D.Va") || !slices.Contains(c.Aliases, "宋哈娜") {
		t.Errorf("DVA 别名异常: %v", c.Aliases)
	}
	// 抽查 3：英雄联盟（缩写变体 + 长短英文别名并存的好运姐）
	g = findGroup(t, "英雄联盟")
	for _, v := range []string{"League of Legends", "LOL", "撸啊撸"} {
		if !slices.Contains(g.Variants, v) {
			t.Errorf("英雄联盟组变体缺 %q: %v", v, g.Variants)
		}
	}
	if c := findChar(t, g, "好运姐"); !slices.Contains(c.Aliases, "Miss Fortune") ||
		!slices.Contains(c.Aliases, "MF") || !slices.Contains(c.Aliases, "女枪") {
		t.Errorf("好运姐别名异常: %v", c.Aliases)
	}
}

func findGroup(t *testing.T, canonical string) *SourceGroup {
	t.Helper()
	for i := range builtinGroups {
		if builtinGroups[i].Canonical == canonical {
			return &builtinGroups[i]
		}
	}
	t.Fatalf("内置组缺 %q", canonical)
	return nil
}

func findChar(t *testing.T, g *SourceGroup, canonical string) *CharEntry {
	t.Helper()
	for i := range g.Characters {
		if g.Characters[i].Canonical == canonical {
			return &g.Characters[i]
		}
	}
	t.Fatalf("组 %q 缺角色 %q", g.Canonical, canonical)
	return nil
}
