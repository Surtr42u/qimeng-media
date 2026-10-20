package recommendfeed

import (
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestSnapshotLifetimeAndKeyIsolation(t *testing.T) {
	c := NewCache()
	key := Key{Day: "2026-10-20", Seed: 1}
	calls := 0
	compute := func() ([]string, error) { calls++; return []string{fmt.Sprint(calls)}, nil }
	first, err := c.order(key, compute)
	if err != nil {
		t.Fatal(err)
	}
	second, err := c.order(key, compute)
	if err != nil || first[0] != second[0] || calls != 1 {
		t.Fatalf("snapshot not reused: %v %d", err, calls)
	}
	variants := []Key{key, key, key, key, key, key}
	variants[0].Revision++
	variants[1].Day = "2026-10-21"
	variants[2].Seed++
	variants[3].MediaType = "video"
	variants[4].CosOnly = true
	variants[5].Prefs = "different"
	for _, k := range variants {
		if _, err := c.order(k, compute); err != nil {
			t.Fatal(err)
		}
	}
	if calls != 7 {
		t.Fatal("environment keys must be isolated")
	}
	e := c.entries[key]
	e.at = time.Now().Add(-TTL)
	c.entries[key] = e
	if _, err := c.order(key, compute); err != nil || calls != 8 {
		t.Fatalf("TTL failed: %v %d", err, calls)
	}
	c.Clear()
	if _, err := c.order(key, compute); err != nil || calls != 9 {
		t.Fatalf("clear failed: %v %d", err, calls)
	}
}

func TestSnapshotSingleflightAndLateInvalidation(t *testing.T) {
	c := NewCache()
	key := Key{Seed: 1}
	started, release := make(chan struct{}), make(chan struct{})
	var calls atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 12; i++ {
		wg.Go(func() {
			_, err := c.order(key, func() ([]string, error) {
				if calls.Add(1) == 1 {
					close(started)
				}
				<-release
				return []string{"a"}, nil
			})
			if err != nil {
				t.Error(err)
			}
		})
	}
	<-started
	close(release)
	wg.Wait()
	if calls.Load() != 1 {
		t.Fatalf("computed %d times", calls.Load())
	}
	c.Clear()
	_, err := c.order(key, func() ([]string, error) { c.Clear(); return []string{"old"}, nil })
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := c.get(key); ok {
		t.Fatal("late computation repopulated invalidated cache")
	}
}

func TestSnapshotErrorPanicAndBudget(t *testing.T) {
	c := NewCache()
	key := Key{}
	for _, compute := range []func() ([]string, error){
		func() ([]string, error) { return nil, errors.New("failed") },
		func() ([]string, error) { panic("failed") },
	} {
		if _, err := c.order(key, compute); err == nil {
			t.Fatal("failure swallowed")
		}
		if _, ok := c.get(key); ok {
			t.Fatal("failure cached")
		}
	}
	ids := make([]string, maxCachedIDs+1)
	got, err := c.order(key, func() ([]string, error) { return ids, nil })
	if err != nil || len(got) != len(ids) {
		t.Fatal("budget truncated candidates")
	}
	if _, ok := c.get(key); ok {
		t.Fatal("oversized stream cached")
	}
	for i := 0; i < maxEntries+2; i++ {
		if _, err := c.order(Key{Seed: i}, func() ([]string, error) { return []string{"a"}, nil }); err != nil {
			t.Fatal(err)
		}
	}
	if len(c.entries) > maxEntries || c.count > maxCachedIDs {
		t.Fatal("budget exceeded")
	}
}
