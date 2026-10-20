package sourcematcher

import (
	"fmt"
	"reflect"
	"sort"
	"sync"
	"testing"
)

func TestRelatedKeysMatchOriginalFieldsAndOrder(t *testing.T) {
	groups := MergeGroups(builtinGroups, []string{"自定义来源"}, []SourceGroup{{Canonical: "自定义Ω", Variants: []string{"A.B", "ＡＢ", "共享别名"}, Characters: []CharEntry{{Canonical: "角色甲", Aliases: []string{"D.Va", "A B", "共享别名"}}, {Canonical: "角色乙", Aliases: []string{"共享别名"}}}}})
	keys := buildRelatedKeys(groups)
	queries := map[string]bool{}
	for _, q := range []string{"", " -._ ", "A.B", "ＡＢ", "共享别名", "不存在的词条"} {
		queries[q] = true
	}
	for _, g := range groups {
		queries[g.Canonical] = true
		for _, v := range g.Variants {
			queries[v] = true
		}
		for _, c := range g.Characters {
			queries[c.Canonical] = true
			for _, a := range c.Aliases {
				queries[a] = true
			}
		}
	}
	for q := range queries {
		want := findInGroupsBaseline(groups, q)
		if got := findInGroups(groups, q, keys); !reflect.DeepEqual(got, want) {
			t.Fatalf("q=%q 反查结果或先见顺序改变", q)
		}
	}
}

func sortedRelated(r RelatedTerms) RelatedTerms {
	for _, values := range [][]string{r.CanonicalSources, r.SourceVariants, r.CanonicalCharacters, r.CharacterAliases, r.AllTerms} {
		sort.Strings(values)
	}
	return r
}

func TestRelatedKeysFollowCustomSnapshotAndReturnFreshResults(t *testing.T) {
	const query = "shared-unique-Ω"
	makeGroups := func(i int) []SourceGroup {
		return []SourceGroup{{Canonical: fmt.Sprintf("来源%d", i), Characters: []CharEntry{{Canonical: fmt.Sprintf("角色%d", i), Aliases: []string{query}}}}}
	}
	m := New(1)
	wants := make([]RelatedTerms, 2)
	for i := range wants {
		m.UpdateCustomGroups(makeGroups(i))
		wants[i] = sortedRelated(m.FindRelatedTerms(query))
	}
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		for i := 0; i < 30; i++ {
			m.UpdateCustomGroups(makeGroups(i % 2))
		}
	}()
	for i := 0; i < 100; i++ {
		got := sortedRelated(m.FindRelatedTerms(query))
		if !reflect.DeepEqual(got, wants[0]) && !reflect.DeepEqual(got, wants[1]) {
			t.Errorf("反查混用了不同词表快照: %+v", got)
			break
		}
	}
	wg.Wait()
	m.UpdateCustomGroups(nil)
	if got := m.FindRelatedTerms(query); len(got.AllTerms) != 0 {
		t.Fatal("清空词层后不能残留预计算键命中")
	}
	want := FindBuiltinRelatedTerms("dva")
	got := FindBuiltinRelatedTerms("dva")
	got.CharacterAliases[0] = "modified"
	if !reflect.DeepEqual(FindBuiltinRelatedTerms("dva"), want) {
		t.Fatal("修改结果不得污染后续反查")
	}
}

func BenchmarkRelatedTerms(b *testing.B) {
	for _, q := range []string{"dva", "女武神", "a", "no-such-word-Ω"} {
		for _, baseline := range []bool{true, false} {
			b.Run(fmt.Sprintf("%s/baseline=%t", q, baseline), func(b *testing.B) {
				b.ReportAllocs()
				for i := 0; i < b.N; i++ {
					if baseline {
						findInGroupsBaseline(builtinGroups, q)
					} else {
						FindBuiltinRelatedTerms(q)
					}
				}
			})
		}
	}
}
