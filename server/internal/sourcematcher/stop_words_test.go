package sourcematcher

// stop_words_test.go：停用词层（DOMAIN_RULES §4 兜底提取层，2026-10-03）——
// 内置冻结基线（触手/白丝/婚纱/多角色/多角色酒吧）与用户追加层只在兜底提取
// 生效：命中即跳过（不终止收集）、清空追加层恢复提取；别名表层永不走停用词。

import (
	"slices"
	"testing"
)

// TestBuiltinStopWordsSkipExtraction 内置基线命中跳过：`出处  描述词 序号`
// 不产角色胶囊；对照真角色词照常提取。
func TestBuiltinStopWordsSkipExtraction(t *testing.T) {
	m := newTestMatcher()
	m.UpdateCustomSources([]string{"我的分区"})
	for _, file := range []string{
		"我的分区  触手 1.mp4",
		"我的分区  白丝 1.mp4",
		"我的分区  婚纱 1.mp4",
	} {
		if src, chars := m.MatchAll(file); src != "我的分区" || chars != nil {
			t.Errorf("MatchAll(%q) = (%q, %v), want (我的分区, nil)", file, src, chars)
		}
	}
	// 对照组：非停用词照常兜底提取（说明 nil 是跳过而非出处没命中）。
	if src, chars := m.MatchAll("我的分区  真角色 1.mp4"); src != "我的分区" || !slices.Equal(chars, []string{"真角色"}) {
		t.Errorf("对照组 MatchAll = (%q, %v), want (我的分区, [真角色])", src, chars)
	}
}

// TestCustomStopWordsAppendAndClear 追加层与清空：UpdateStopWords 追加
// 「黑丝」（不在内置基线）后同文件不再提取；UpdateStopWords(nil) 清空追加
// 层后恢复提取（内置基线恒生效——触手仍被跳过）。
func TestCustomStopWordsAppendAndClear(t *testing.T) {
	m := newTestMatcher()
	m.UpdateCustomSources([]string{"我的分区"})
	m.UpdateStopWords([]string{"黑丝"})
	if _, chars := m.MatchAll("我的分区  黑丝 1.mp4"); chars != nil {
		t.Errorf("追加层生效后角色 = %v, want nil", chars)
	}
	m.UpdateStopWords(nil)
	if _, chars := m.MatchAll("我的分区  黑丝 1.mp4"); !slices.Equal(chars, []string{"黑丝"}) {
		t.Errorf("清空追加层后角色 = %v, want [黑丝]（恢复提取）", chars)
	}
	if _, chars := m.MatchAll("我的分区  触手 1.mp4"); chars != nil {
		t.Errorf("清空追加层后内置基线角色 = %v, want nil（基线恒生效）", chars)
	}
}

// TestStopWordSkipsWithoutTerminating 跳过不终止收集：停用词夹在角色名与
// 序号之间时，角色照常提取（与序号终止语义区分——序号 break，停用词 continue）。
func TestStopWordSkipsWithoutTerminating(t *testing.T) {
	m := newTestMatcher()
	m.UpdateCustomSources([]string{"我的分区"})
	if _, chars := m.MatchAll("我的分区  真角色 触手 1.mp4"); !slices.Equal(chars, []string{"真角色"}) {
		t.Errorf("停用词应跳过不终止: %v, want [真角色]", chars)
	}
}

// TestAliasTableUnaffectedByStopWords 别名表层不受停用词影响：表命中路径
// （含词面里含停用词子串的「触手大师」）不走停用词过滤，照常返回 canonical。
func TestAliasTableUnaffectedByStopWords(t *testing.T) {
	m := newTestMatcher()
	m.UpdateCustomGroups([]SourceGroup{{Canonical: "我的分区", Characters: []CharEntry{
		{Canonical: "触手大师", Aliases: []string{"触手大师"}},
	}}})
	if src, chars := m.MatchAll("我的分区  触手大师 1.mp4"); src != "我的分区" || !slices.Equal(chars, []string{"触手大师"}) {
		t.Errorf("表命中路径 = (%q, %v), want (我的分区, [触手大师])", src, chars)
	}
}
