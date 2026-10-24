package recommendfeed

import (
	"errors"
	"fmt"
	"reflect"
	"testing"
	"time"

	"qimeng-media/server/internal/recommend"
)

func warmItems() []recommend.Item {
	items := make([]recommend.Item, 32)
	for i := range items {
		items[i] = recommend.Item{AssetID: fmt.Sprintf("synthetic-%d", i), MediaType: "image", FileName: "sample", Tags: []string{"tag"}}
	}
	return items
}

func TestWarmSeedsLoadsOnceAndKeepsIndependentOrders(t *testing.T) {
	c := NewCache()
	base := Key{Day: "2026-10-25", Revision: 4, Prefs: "same"}
	now := time.Date(2026, 10, 25, 12, 0, 0, 0, time.UTC)
	items := warmItems()
	before := warmItems()
	loads := 0
	load := func() ([]recommend.Item, error) { loads++; return items, nil }
	if err := c.warmSeeds(base, []int{1, 0}, nil, now, load); err != nil {
		t.Fatal(err)
	}
	if loads != 1 {
		t.Fatalf("装载次数: %d", loads)
	}
	var orders [][]string
	for _, seed := range []int{1, 0} {
		key := base
		key.Seed = seed
		ids, ok := c.get(key)
		if !ok || !reflect.DeepEqual(ids, orderItems(items, seed, nil, now)) {
			t.Fatalf("seed %d 顺序不一致", seed)
		}
		orders = append(orders, ids)
	}
	if reflect.DeepEqual(orders[0], orders[1]) {
		t.Fatal("两个 seed 被错误合并")
	}
	if !reflect.DeepEqual(items, before) {
		t.Fatal("共享输入被排序改写")
	}
	if err := c.warmSeeds(base, []int{1, 0}, nil, now, load); err != nil {
		t.Fatal(err)
	}
	if loads != 1 {
		t.Fatal("全命中仍装载数据")
	}
	base.Day = "2026-10-26"
	if err := c.warmSeeds(base, []int{1, 0}, nil, now, load); err != nil {
		t.Fatal(err)
	}
	if loads != 2 {
		t.Fatal("跨日输入被复用")
	}
}

func TestWarmSeedsEmptyErrorAndInvalidation(t *testing.T) {
	for _, mode := range []string{"empty", "error", "invalidated"} {
		t.Run(mode, func(t *testing.T) {
			c := NewCache()
			loads := 0
			expected := errors.New("read failure")
			err := c.warmSeeds(Key{}, []int{1, 0}, nil, time.Now(), func() ([]recommend.Item, error) {
				loads++
				if mode == "error" {
					return nil, expected
				}
				if mode == "invalidated" {
					c.Clear()
					return warmItems(), nil
				}
				return nil, nil
			})
			if loads != 1 {
				t.Fatalf("装载次数: %d", loads)
			}
			switch mode {
			case "empty":
				if err != nil || len(c.entries) != 2 {
					t.Fatal("空候选未正确复用", err)
				}
			case "error":
				if !errors.Is(err, expected) || len(c.entries) != 0 {
					t.Fatal("错误被缓存或吞掉", err)
				}
			case "invalidated":
				if !errors.Is(err, ErrWarmupInvalidated) || len(c.entries) != 0 {
					t.Fatal("旧输入被回填到新一代", err)
				}
			}
		})
	}
}

func TestWarmSeedsSkipsAlreadyCachedSeed(t *testing.T) {
	c := NewCache()
	key := Key{Seed: 1}
	if _, err := c.order(key, func() ([]string, error) { return []string{"cached"}, nil }); err != nil {
		t.Fatal(err)
	}
	loads := 0
	if err := c.warmSeeds(Key{}, []int{1, 0}, nil, time.Now(), func() ([]recommend.Item, error) { loads++; return warmItems(), nil }); err != nil {
		t.Fatal(err)
	}
	ids, ok := c.get(key)
	if loads != 1 || !ok || !reflect.DeepEqual(ids, []string{"cached"}) {
		t.Fatal("预热覆盖了已缓存 seed")
	}
}
