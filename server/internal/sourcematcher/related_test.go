package sourcematcher

import (
	"testing"
)

func TestFindBuiltinRelatedTerms(t *testing.T) {
	// 1. 测试 dva 能否命中 DVA 与守望先锋
	t.Run("dva matches DVA and Overwatch", func(t *testing.T) {
		res := FindBuiltinRelatedTerms("dva")
		foundChar := false
		for _, c := range res.CanonicalCharacters {
			if c == "DVA" {
				foundChar = true
				break
			}
		}
		if !foundChar {
			t.Errorf("期望 CanonicalCharacters 包含 DVA，实际: %v", res.CanonicalCharacters)
		}
		foundAlias := false
		for _, a := range res.CharacterAliases {
			if a == "D.Va" {
				foundAlias = true
				break
			}
		}
		if !foundAlias {
			t.Errorf("期望 CharacterAliases 包含 D.Va，实际: %v", res.CharacterAliases)
		}
		foundSrc := false
		for _, s := range res.CanonicalSources {
			if s == "守望先锋" {
				foundSrc = true
				break
			}
		}
		if !foundSrc {
			t.Errorf("期望 CanonicalSources 包含 守望先锋，实际: %v", res.CanonicalSources)
		}
	})

	// 2. 测试女武神（别名）能否命中玛莲妮亚（真名）与艾尔登法环
	t.Run("女武神 matches 玛莲妮亚 and 艾尔登法环", func(t *testing.T) {
		res := FindBuiltinRelatedTerms("女武神")
		foundChar := false
		for _, c := range res.CanonicalCharacters {
			if c == "玛莲妮亚" {
				foundChar = true
				break
			}
		}
		if !foundChar {
			t.Errorf("期望 CanonicalCharacters 包含 玛莲妮亚，实际: %v", res.CanonicalCharacters)
		}
		foundSrc := false
		for _, s := range res.CanonicalSources {
			if s == "艾尔登法环" {
				foundSrc = true
				break
			}
		}
		if !foundSrc {
			t.Errorf("期望 CanonicalSources 包含 艾尔登法环，实际: %v", res.CanonicalSources)
		}
	})

	// 3. 测试玛莲妮亚（真名）能否命中艾尔登法环及女武神别名
	t.Run("玛莲妮亚 matches 艾尔登法环 and 女武神", func(t *testing.T) {
		res := FindBuiltinRelatedTerms("玛莲妮亚")
		foundChar := false
		for _, c := range res.CanonicalCharacters {
			if c == "玛莲妮亚" {
				foundChar = true
				break
			}
		}
		if !foundChar {
			t.Errorf("期望 CanonicalCharacters 包含 玛莲妮亚，实际: %v", res.CanonicalCharacters)
		}
		foundAlias := false
		for _, a := range res.CharacterAliases {
			if a == "女武神" {
				foundAlias = true
				break
			}
		}
		if !foundAlias {
			t.Errorf("期望 CharacterAliases 包含 女武神，实际: %v", res.CharacterAliases)
		}
	})

	// 4. 测试老头环能否命中艾尔登法环
	t.Run("老头环 matches 艾尔登法环", func(t *testing.T) {
		res := FindBuiltinRelatedTerms("老头环")
		foundSrc := false
		for _, s := range res.CanonicalSources {
			if s == "艾尔登法环" {
				foundSrc = true
				break
			}
		}
		if !foundSrc {
			t.Errorf("期望 CanonicalSources 包含 艾尔登法环，实际: %v", res.CanonicalSources)
		}
	})
}
