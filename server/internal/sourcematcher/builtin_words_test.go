package sourcematcher

// builtin_words_test.go：内置基线固化词条（ADR-0033 补记三，2026-10-04）的
// 纯内置匹配锁定——不设任何自定义层（newTestMatcher 即纯内置），用户首批审定
// 词条必须在所有部署形态下得出一致结果；另锁内置表 canonical 唯一性。

import (
	"slices"
	"testing"
)

// TestBuiltinBaselineWords 固化词条的纯内置匹配（无自定义层）：引擎版本自愈
// 重算（scanner.EnrichmentEngineVersion=2）后各部署应得出与本表一致的结果。
func TestBuiltinBaselineWords(t *testing.T) {
	m := newTestMatcher()
	tests := []struct {
		file       string
		wantSource string
		wantChars  []string
	}{
		// 守望先锋·D.Mon：小写别名 Dmon 改名归一
		{"守望先锋  Dmon 1.mp4", "守望先锋", []string{"D.Mon"}},
		// 铁拳·风间飞鸟：新增别名「风间明日香」（同一角色另一中文译名）
		{"铁拳8  风间明日香 1.mp4", "铁拳", []string{"风间飞鸟"}},
		// 铁拳·风间准：新角色，与风间飞鸟同文件时按 canonical 字典序
		{"铁拳8  风间飞鸟+风间准.mp4", "铁拳", []string{"风间准", "风间飞鸟"}},
		// 最终幻想·阿拉尼雅：新角色
		{"最终幻想  阿拉尼雅 1.mp4", "最终幻想", []string{"阿拉尼雅"}},
		// 怪物猎人（新组）：中文名与英文变体都命中杰玛
		{"怪物猎人  杰玛.mp4", "怪物猎人", []string{"杰玛"}},
		{"Monster Hunter  Gemma.mp4", "怪物猎人", []string{"杰玛"}},
		// 战锤40k（新组）：英文变体大小写混合 + 序号括号终止
		{"战锤40k  战斗修女 1 (1).mp4", "战锤40k", []string{"战斗修女"}},
		// 初音未来（新组）：别名折叠域小写命中，归一为组名同名 canonical
		{"初音未来  miku 1.mp4", "初音未来", []string{"初音未来"}},
	}
	for _, tt := range tests {
		src, chars := m.MatchAll(tt.file)
		if src != tt.wantSource || !slices.Equal(chars, tt.wantChars) {
			t.Errorf("MatchAll(%q) = (%q, %v), want (%q, %v)", tt.file, src, chars, tt.wantSource, tt.wantChars)
		}
	}
}

// TestBuiltinGroupsCanonicalUnique 内置表完整性：builtinGroups 的 canonical
// 全表唯一（重复组会在索引构建时静默相互覆盖）。
func TestBuiltinGroupsCanonicalUnique(t *testing.T) {
	seen := make(map[string]int, len(builtinGroups))
	for i, g := range builtinGroups {
		if prev, dup := seen[g.Canonical]; dup {
			t.Errorf("builtinGroups[%d] canonical %q 与 [%d] 重复", i, g.Canonical, prev)
			continue
		}
		seen[g.Canonical] = i
	}
}
