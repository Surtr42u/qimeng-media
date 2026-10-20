package sourcematcher

import "strings"

// 优化前的独立行为参照，仅用于字段/顺序对照与基准。
func findInGroupsBaseline(groups []SourceGroup, q string) RelatedTerms {
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
