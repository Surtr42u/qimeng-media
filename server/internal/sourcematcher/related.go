package sourcematcher

import (
	"strings"
	"unicode"
)

// RelatedTerms 包含了根据查询词关联出的出处与角色别名/变体信息。
type RelatedTerms struct {
	CanonicalSources    []string // 命中的出处规范名（如 "守望先锋", "艾尔登法环"）
	SourceVariants      []string // 命中的出处全部变体（如 "法环", "老头环", "ow"）
	CanonicalCharacters []string // 命中的角色规范名（如 "D.Va", "玛莲妮亚"）
	CharacterAliases    []string // 命中的角色全部别名（如 "dva", "女武神", "Malenia"）
	AllTerms            []string // 所有关联关键词汇总（去重，供联想匹配使用）
}

// CleanFuzzyKey 去除标点、分隔符与空白并转为小写，用于消除符号差异（如 "D.Va" 与 "dva"、"艾尔登法环" 与 "法环"）。
func CleanFuzzyKey(s string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(s) {
		if unicode.IsLetter(r) || unicode.IsDigit(r) || unicode.Is(unicode.Han, r) {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// FindBuiltinRelatedTerms 纯函数：在内置 133 组出处与角色表中检索与 q 关联的出处与角色别名。
func FindBuiltinRelatedTerms(q string) RelatedTerms {
	return findInGroups(builtinGroups, q)
}

// FindRelatedTerms 在当前 Matcher（含自定义词表层）中检索与 q 关联的出处与角色别名。
func (m *Matcher) FindRelatedTerms(q string) RelatedTerms {
	if m == nil {
		return FindBuiltinRelatedTerms(q)
	}
	ix := m.snap.Load()
	if ix == nil || len(ix.groups) == 0 {
		return FindBuiltinRelatedTerms(q)
	}
	groups := make([]SourceGroup, 0, len(ix.groups))
	for _, g := range ix.groups {
		if g != nil {
			groups = append(groups, *g)
		}
	}
	return findInGroups(groups, q)
}

func findInGroups(groups []SourceGroup, q string) RelatedTerms {
	qFuzzy := CleanFuzzyKey(q)
	if qFuzzy == "" {
		return RelatedTerms{}
	}

	seenSources := make(map[string]bool)
	seenSourceVars := make(map[string]bool)
	seenChars := make(map[string]bool)
	seenCharAliases := make(map[string]bool)
	seenAll := make(map[string]bool)

	var res RelatedTerms

	addTerm := func(term string) {
		if term == "" {
			return
		}
		clean := strings.TrimSpace(term)
		if clean == "" || seenAll[clean] {
			return
		}
		seenAll[clean] = true
		res.AllTerms = append(res.AllTerms, clean)
	}

	for _, g := range groups {
		if g.Canonical == "" {
			continue
		}

		sourceMatched := false
		if CleanFuzzyKey(g.Canonical) == qFuzzy || strings.Contains(CleanFuzzyKey(g.Canonical), qFuzzy) {
			sourceMatched = true
		} else {
			for _, v := range g.Variants {
				if CleanFuzzyKey(v) == qFuzzy || strings.Contains(CleanFuzzyKey(v), qFuzzy) {
					sourceMatched = true
					break
				}
			}
		}

		if sourceMatched {
			if !seenSources[g.Canonical] {
				seenSources[g.Canonical] = true
				res.CanonicalSources = append(res.CanonicalSources, g.Canonical)
				addTerm(g.Canonical)
			}
			for _, v := range g.Variants {
				if !seenSourceVars[v] {
					seenSourceVars[v] = true
					res.SourceVariants = append(res.SourceVariants, v)
					addTerm(v)
				}
			}
		}

		for _, ce := range g.Characters {
			if ce.Canonical == "" {
				continue
			}

			charMatched := false
			if CleanFuzzyKey(ce.Canonical) == qFuzzy || strings.Contains(CleanFuzzyKey(ce.Canonical), qFuzzy) {
				charMatched = true
			} else {
				for _, a := range ce.Aliases {
					if CleanFuzzyKey(a) == qFuzzy || strings.Contains(CleanFuzzyKey(a), qFuzzy) {
						charMatched = true
						break
					}
				}
			}

			if charMatched {
				if !seenChars[ce.Canonical] {
					seenChars[ce.Canonical] = true
					res.CanonicalCharacters = append(res.CanonicalCharacters, ce.Canonical)
					addTerm(ce.Canonical)
				}
				if !seenSources[g.Canonical] {
					seenSources[g.Canonical] = true
					res.CanonicalSources = append(res.CanonicalSources, g.Canonical)
					addTerm(g.Canonical)
				}
				for _, a := range ce.Aliases {
					if !seenCharAliases[a] {
						seenCharAliases[a] = true
						res.CharacterAliases = append(res.CharacterAliases, a)
						addTerm(a)
					}
				}
				for _, v := range g.Variants {
					if !seenSourceVars[v] {
						seenSourceVars[v] = true
						res.SourceVariants = append(res.SourceVariants, v)
						addTerm(v)
					}
				}
			}
		}
	}

	return res
}
