package discovery

import (
	"fmt"
	"reflect"
	"testing"
	"time"
)

func TestMixedSelection(t *testing.T) {
	now := time.Date(2026, 10, 28, 12, 0, 0, 0, time.UTC)
	candidates := []Candidate{}
	for i := 0; i < 30; i++ {
		last := time.Time{}
		if i%3 == 1 {
			last = now.Add(-recentWindow - time.Second)
		}
		if i%3 == 2 {
			last = now.Add(-recentWindow)
		}
		candidates = append(candidates, Candidate{ID: fmt.Sprint(i), LastActivity: last})
	}
	original := append([]Candidate(nil), candidates...)
	a := Select(candidates, 42, 6, now)
	if len(a) != 6 {
		t.Fatal(a)
	}
	counts := map[string]int{}
	seen := map[string]bool{}
	for _, p := range a {
		counts[p.Reason]++
		if seen[p.ID] {
			t.Fatal("duplicate")
		}
		seen[p.ID] = true
	}
	for _, reason := range []string{Unseen, Rediscover, Interest} {
		if counts[reason] != 2 {
			t.Fatal(counts)
		}
	}
	if !reflect.DeepEqual(a, Select(candidates, 42, 6, now)) {
		t.Fatal("unstable seed")
	}
	if reflect.DeepEqual(a, Select(candidates, 43, 6, now)) {
		t.Fatal("seed did not change selection")
	}
	if !reflect.DeepEqual(original, candidates) {
		t.Fatal("mutated inputs")
	}
	if got := Select(candidates[:2], 42, 6, now); len(got) != 2 {
		t.Fatal(got)
	}
	if got := Select(candidates[:1], 42, 0, now); len(got) != 0 {
		t.Fatal(got)
	}
	if got := Select(nil, 42, 6, now); len(got) != 0 {
		t.Fatal(got)
	}
}
