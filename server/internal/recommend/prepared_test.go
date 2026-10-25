package recommend

import (
	"reflect"
	"testing"
)

func TestPreparedSeedsReadOnlyAndEmpty(t *testing.T) {
	for _, items := range [][]Item{nil, {}, compatibilityItems("mixed")[:1], compatibilityItems("mixed")} {
		before := append([]Item(nil), items...)
		for _, prefs := range []*Weights{nil, {}, {TagRelevance: 0.3, Discovery: 0.7, MaxRandom: 0.4}} {
			p := Params{Now: fixedNow, Prefs: prefs}
			prepared := Prepare(items, p)
			for _, seed := range []int{1, 0, 42, -1, 1} {
				p.Seed = seed
				want := Recommend(items, p)
				if got := prepared.Items(seed, 0); !reflect.DeepEqual(got, want) {
					t.Fatal("shared preparation changed fields/order")
				}
				ids := prepared.IDs(seed, 0)
				for i, it := range want {
					if ids[i] != it.AssetID {
						t.Fatal("ID mismatch")
					}
				}
			}
		}
		if len(items) > 0 && !reflect.DeepEqual(items, before) {
			t.Fatal("input mutated")
		}
		if len(items) > 1 && !reflect.DeepEqual(items, compatibilityItems("mixed")) {
			t.Fatal("tags mutated")
		}
	}
}
