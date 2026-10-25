package recommendfeed

import (
	"testing"
	"time"
)

func TestBudgetEvictsColdStreamsKeepsHotAndOriginalTTL(t *testing.T) {
	c := NewCache()
	put := func(seed, count int) {
		t.Helper()
		_, err := c.order(Key{Seed: seed}, func() ([]string, error) { return make([]string, count), nil })
		if err != nil {
			t.Fatal(err)
		}
	}
	for i := 0; i < maxEntries; i++ {
		put(i, 1)
	}
	hot := Key{Seed: 0}
	created := c.entries[hot].at
	if _, ok := c.get(hot); !ok {
		t.Fatal("hot miss")
	}
	put(maxEntries, 1)
	if _, ok := c.get(hot); !ok {
		t.Fatal("hot stream evicted")
	}
	if _, ok := c.get(Key{Seed: 1}); ok {
		t.Fatal("cold stream retained")
	}
	if len(c.entries) != maxEntries || c.count != maxEntries {
		t.Fatal("bad entry budget accounting")
	}
	if !c.entries[hot].at.Equal(created) {
		t.Fatal("hit extended TTL")
	}
	expired := c.entries[hot]
	expired.at = time.Now().Add(-TTL)
	c.entries[hot] = expired
	if _, ok := c.get(hot); ok {
		t.Fatal("expired hot stream survived")
	}
	if c.count != maxEntries-1 {
		t.Fatal("expiry did not release budget")
	}
}

func TestIDBudgetEvictsOnlyEnoughAndOversizeDoesNotFlush(t *testing.T) {
	c := NewCache()
	for i := 0; i < 4; i++ {
		_, err := c.order(Key{Seed: i}, func() ([]string, error) { return make([]string, maxCachedIDs/4), nil })
		if err != nil {
			t.Fatal(err)
		}
	}
	if _, ok := c.get(Key{Seed: 0}); !ok {
		t.Fatal("missing hot")
	}
	_, err := c.order(Key{Seed: 4}, func() ([]string, error) { return []string{"new"}, nil })
	if err != nil {
		t.Fatal(err)
	}
	if len(c.entries) != 4 || c.count != 3*maxCachedIDs/4+1 {
		t.Fatal("flushed more than needed")
	}
	if _, ok := c.get(Key{Seed: 0}); !ok {
		t.Fatal("hot evicted")
	}
	if _, ok := c.get(Key{Seed: 1}); ok {
		t.Fatal("oldest cold retained")
	}
	_, err = c.order(Key{Seed: 5}, func() ([]string, error) { return make([]string, maxCachedIDs+1), nil })
	if err != nil {
		t.Fatal(err)
	}
	if len(c.entries) != 4 || c.count != 3*maxCachedIDs/4+1 {
		t.Fatal("oversize disturbed cache")
	}
	// 空顺序仍占一个流预算，不占 ID 预算。
	_, err = c.order(Key{Seed: 6}, func() ([]string, error) { return []string{}, nil })
	if err != nil {
		t.Fatal(err)
	}
	if ids, ok := c.get(Key{Seed: 6}); !ok || len(ids) != 0 {
		t.Fatal("empty snapshot not cached")
	}
}
