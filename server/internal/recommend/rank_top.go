package recommend

import (
	"container/heap"
	"slices"
	"time"
)

// RankTop 返回完整排行的前 limit 项；limit<=0 或覆盖输入时沿用 Rank。
// 准入/热度/mtime 与 Rank 共用口径，输入下标作为最终平局键，保持原稳定序。
// 小页只保留 limit 个下标和预计算热度，不复制/排序全库 Item；HTTP 深分页
// 需请求 offset+limit 项再切片，不能先截候选再排名。
func RankTop(items []Item, period string, now time.Time, limit int) []Item {
	if limit <= 0 || limit >= len(items) {
		return Rank(items, period, now)
	}
	_, windowed := rankPeriodWindows[period]
	selection := &rankingHeap{items: items, rows: make([]rankEntry, 0, limit)}
	for i := range items {
		heat := heatOf(&items[i])
		if windowed {
			heat = items[i].Window.Heat()
			if heat <= 0 {
				continue
			}
		}
		candidate := rankEntry{index: i, heat: heat}
		if len(selection.rows) < limit {
			selection.rows = append(selection.rows, candidate)
			if len(selection.rows) == limit {
				heap.Init(selection)
			}
		} else if selection.before(candidate, selection.rows[0]) {
			selection.rows[0] = candidate
			heap.Fix(selection, 0)
		}
	}
	slices.SortFunc(selection.rows, func(a, b rankEntry) int {
		if selection.before(a, b) {
			return -1
		}
		if selection.before(b, a) {
			return 1
		}
		return 0
	})
	out := make([]Item, len(selection.rows))
	for i, row := range selection.rows {
		out[i] = items[row.index]
	}
	return out
}

type rankEntry struct{ index, heat int }

// 堆顶为已保留项中最差的一项，新候选更优时替换堆顶。堆操作复用标准库。
type rankingHeap struct {
	items []Item
	rows  []rankEntry
}

func (h *rankingHeap) before(a, b rankEntry) bool {
	order := compareRankItems(&h.items[a.index], &h.items[b.index], a.heat, b.heat)
	return order < 0 || (order == 0 && a.index < b.index)
}

func (h *rankingHeap) Len() int           { return len(h.rows) }
func (h *rankingHeap) Less(i, j int) bool { return h.before(h.rows[j], h.rows[i]) }
func (h *rankingHeap) Swap(i, j int)      { h.rows[i], h.rows[j] = h.rows[j], h.rows[i] }
func (h *rankingHeap) Push(v any)         { h.rows = append(h.rows, v.(rankEntry)) }
func (h *rankingHeap) Pop() any {
	n := len(h.rows) - 1
	row := h.rows[n]
	h.rows = h.rows[:n]
	return row
}
