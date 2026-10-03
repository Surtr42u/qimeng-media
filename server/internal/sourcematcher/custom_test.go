package sourcematcher

// custom_test.go：自定义出处组合并层（ADR-0033）——MergeGroups 纯函数语义、
// UpdateCustomGroups 运行期行为（并入内置组/新组/清空/与裸名层共存），以及
// 「第一后裔 邦尼」复合变体移除的回归锁定：移除前最长变体命中即停，
// "第一后裔 邦尼 1" 被整个剥掉导致剩余串只剩 "1"、角色匹配落空。

import (
	"slices"
	"testing"
)

// TestMergeGroupsNewAndMergeIntoBuiltin 新组追加 + 同名并入既有组扩角色 +
// 裸名层退化输入 + canonical 自并入兜底。
func TestMergeGroupsNewAndMergeIntoBuiltin(t *testing.T) {
	base := []SourceGroup{
		{Canonical: "组A", Variants: []string{"组A", "A2"}, Characters: []CharEntry{
			{Canonical: "X", Aliases: []string{"X", "x2"}},
		}},
	}
	merged := MergeGroups(base,
		[]string{"裸名组"},
		[]SourceGroup{
			{Canonical: "组A", Characters: []CharEntry{{Canonical: "Y", Aliases: []string{"Y"}}}},
			{Canonical: "新组", Variants: []string{"NG"}, Characters: []CharEntry{{Canonical: "Z"}}},
		},
	)
	if len(merged) != 3 {
		t.Fatalf("合并后应 3 组（组A/裸名组/新组），得到 %d: %+v", len(merged), merged)
	}
	find := func(canonical string) SourceGroup {
		t.Helper()
		for _, g := range merged {
			if g.Canonical == canonical {
				return g
			}
		}
		t.Fatalf("合并结果缺组 %q: %+v", canonical, merged)
		return SourceGroup{}
	}
	// 并入：组A 角色表 = X ∪ Y，变体不变；Z 这种省略自身别名的角色被兜底补齐。
	a := find("组A")
	if !slices.Equal(a.Variants, []string{"组A", "A2"}) {
		t.Errorf("组A 变体 = %v, want [组A A2]", a.Variants)
	}
	if len(a.Characters) != 2 || a.Characters[0].Canonical != "X" ||
		!slices.Equal(a.Characters[0].Aliases, []string{"X", "x2"}) {
		t.Errorf("组A 既有角色失配: %+v", a.Characters)
	}
	if a.Characters[1].Canonical != "Y" || !slices.Equal(a.Characters[1].Aliases, []string{"Y"}) {
		t.Errorf("组A 并入角色应为 Y（canonical 自并入别名后去重）: %+v", a.Characters[1])
	}
	// 新组：canonical 未在提交变体中也被兜底并入。
	ng := find("新组")
	if !slices.Equal(ng.Variants, []string{"NG", "新组"}) {
		t.Errorf("新组变体 = %v, want [NG 新组]", ng.Variants)
	}
	// 裸名层 = 无角色表裸组。
	if b := find("裸名组"); len(b.Characters) != 0 || !slices.Equal(b.Variants, []string{"裸名组"}) {
		t.Errorf("裸名组应为无角色表裸组: %+v", b)
	}
}

// TestMergeGroupsDoesNotMutateInputs 合并不得写穿入参——base 是包级内置表，
// 原地改写会让每次重建的"基线"被自定义层污染（别名/变体越并越多）。
func TestMergeGroupsDoesNotMutateInputs(t *testing.T) {
	base := []SourceGroup{{Canonical: "组A", Variants: []string{"组A"}, Characters: []CharEntry{
		{Canonical: "X", Aliases: []string{"X"}},
	}}}
	_ = MergeGroups(base, []string{"组A"}, []SourceGroup{{Canonical: "组A", Characters: []CharEntry{{Canonical: "Y"}}}})
	if len(base[0].Characters) != 1 || base[0].Characters[0].Canonical != "X" ||
		!slices.Equal(base[0].Characters[0].Aliases, []string{"X"}) {
		t.Fatalf("合并写穿了基线角色表: %+v", base[0].Characters)
	}
	if !slices.Equal(base[0].Variants, []string{"组A"}) {
		t.Fatalf("合并写穿了基线变体: %v", base[0].Variants)
	}
}

// TestMatcherUpdateCustomGroups 运行期行为四段：并入内置组 → 新组 → 清空 →
// 内置表始终未被污染。
func TestMatcherUpdateCustomGroups(t *testing.T) {
	m := newTestMatcher()
	// ① 并入内置组：守望先锋 + 索杰恩（自定义层词条）→ 表层命中即改名归一，
	// 既有角色不受影响。（2026-10-04 起改用内置表未收录的索杰恩：D.Mon 已固化
	// 进内置基线（ADR-0033 补记三），再用它断言合并将失去判别力——不并也命中。）
	m.UpdateCustomGroups([]SourceGroup{{Canonical: "守望先锋", Characters: []CharEntry{
		{Canonical: "索杰恩", Aliases: []string{"Sojourn", "索杰恩"}},
	}}})
	if src, chars := m.MatchAll("守望先锋  Sojourn 1.mp4"); src != "守望先锋" || !slices.Equal(chars, []string{"索杰恩"}) {
		t.Errorf("并入后 MatchAll = (%q, %v), want (守望先锋, [索杰恩])", src, chars)
	}
	if src, chars := m.MatchAll("守望先锋  DVA.mp4"); src != "守望先锋" || !slices.Equal(chars, []string{"DVA"}) {
		t.Errorf("并入后既有角色失配: (%q, %v), want (守望先锋, [DVA])", src, chars)
	}
	// ② 新组：canonical + 英文变体 + 角色别名。
	m.UpdateCustomGroups([]SourceGroup{{Canonical: "怪物猎人", Variants: []string{"Monster Hunter"}, Characters: []CharEntry{
		{Canonical: "杰玛", Aliases: []string{"杰玛", "Gemma"}},
	}}})
	if src, chars := m.MatchAll("怪物猎人  杰玛.mp4"); src != "怪物猎人" || !slices.Equal(chars, []string{"杰玛"}) {
		t.Errorf("新组 MatchAll = (%q, %v), want (怪物猎人, [杰玛])", src, chars)
	}
	if src, chars := m.MatchAll("Monster Hunter  Gemma.mp4"); src != "怪物猎人" || !slices.Equal(chars, []string{"杰玛"}) {
		t.Errorf("英文变体 MatchAll = (%q, %v), want (怪物猎人, [杰玛])", src, chars)
	}
	// ③ 清空出处组层：词条的改名归一消失，但命名规约兜底层仍按原名提取
	// （DOMAIN_RULES §4 兜底层：表零命中 → `出处  角色名 序号` 位置提取）。
	m.UpdateCustomGroups(nil)
	if src, chars := m.MatchAll("守望先锋  Sojourn 1.mp4"); src != "守望先锋" || !slices.Equal(chars, []string{"Sojourn"}) {
		t.Errorf("清空后 MatchAll = (%q, %v), want (守望先锋, [Sojourn] 兜底原名)", src, chars)
	}
	// ④ 内置表从未被污染：内置角色照常命中；D.Mon/Dmon 已固化内置基线
	// （ADR-0033 补记三），清空自定义层后仍由内置表改名归一。
	if src, chars := m.MatchAll("守望先锋  DVA.mp4"); src != "守望先锋" || !slices.Equal(chars, []string{"DVA"}) {
		t.Errorf("清空后内置角色失配: (%q, %v), want (守望先锋, [DVA])", src, chars)
	}
	if src, chars := m.MatchAll("守望先锋  Dmon 1.mp4"); src != "守望先锋" || !slices.Equal(chars, []string{"D.Mon"}) {
		t.Errorf("清空后内置固化词条失配: (%q, %v), want (守望先锋, [D.Mon])", src, chars)
	}
}

// TestMatcherCustomNameKeepsBuiltinCharacters 裸名层与内置组同 canonical 时
// 角色表不得因裸组覆盖而丢失（合并语义替代旧 rebuild 的 map 覆盖行为）。
func TestMatcherCustomNameKeepsBuiltinCharacters(t *testing.T) {
	m := newTestMatcher()
	m.UpdateCustomSources([]string{"守望先锋"})
	if _, chars := m.MatchAll("守望先锋  DVA.mp4"); !slices.Equal(chars, []string{"DVA"}) {
		t.Errorf("裸名同名并入后内置角色丢失: %v, want [DVA]", chars)
	}
	m.UpdateCustomSources(nil)
}

// TestBunnyVariantSwallowRegression 「第一后裔 邦尼」复合变体移除的回归锁定
// （ADR-0033）：变体表曾含 "第一后裔 邦尼"，长度降序前缀命中即停使
// "第一后裔 邦尼 1.mp4" 的角色名被变体整体吃掉、剩余串只剩 "1"，角色匹配
// 落空；移除后剥出处剩 "邦尼 1"，别名 邦尼 命中 canonical 邦妮。
func TestBunnyVariantSwallowRegression(t *testing.T) {
	m := newTestMatcher()
	src, chars := m.MatchAll("第一后裔  邦尼 1.mp4")
	if src != "第一后裔" || !slices.Equal(chars, []string{"邦妮"}) {
		t.Errorf("MatchAll = (%q, %v), want (第一后裔, [邦妮])", src, chars)
	}
}
