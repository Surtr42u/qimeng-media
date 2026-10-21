package syncchannel

import (
	"sort"
	"time"
)

// Item 是值快照，不把内部 map/指针/锁暴露给状态消费者。
type Item struct {
	RelPath, Kind, LibraryName, State, Error string
	Size                                     int64
	Attempts                                 int
	LastAttemptAt                            time.Time
}

type Snapshot struct {
	Enabled, Paused                                        bool
	Root, LastError                                        string
	IntervalSeconds, SyncedTotal, Failed, Ignored, Waiting int
	LastCycleAt                                            time.Time
	Items, Recent                                          []Item
}

func itemSnapshot(it localSyncItem) Item {
	return Item{RelPath: it.relPath, Kind: it.kind, LibraryName: it.libraryName, State: it.state,
		Error: it.errText, Size: it.size, Attempts: it.attempts, LastAttemptAt: it.lastAttemptAt}
}

// Snapshot counts 始终全量；items 按路径排序截断，recent 维持最新在前。
func (s *Service) Snapshot() Snapshot {
	s.localSync.mu.Lock()
	defer s.localSync.mu.Unlock()
	st := Snapshot{Enabled: s.cfg.LocalSync.Root != "" && s.localSync.lastErr == "",
		Root: s.cfg.LocalSync.Root, LastError: s.localSync.lastErr, Paused: s.localSync.paused,
		IntervalSeconds: int(s.Interval() / time.Second), SyncedTotal: s.localSync.syncedTotal,
		LastCycleAt: s.localSync.lastCycleAt}
	paths := make([]string, 0, len(s.localSync.items))
	for p, it := range s.localSync.items {
		paths = append(paths, p)
		switch it.state {
		case StateFailed:
			st.Failed++
		case StateIgnored:
			st.Ignored++
		case StateWaitingStable:
			st.Waiting++
		}
	}
	sort.Strings(paths)
	if len(paths) > localSyncStatusItemCap {
		paths = paths[:localSyncStatusItemCap]
	}
	st.Items = make([]Item, 0, len(paths))
	for _, p := range paths {
		st.Items = append(st.Items, itemSnapshot(*s.localSync.items[p]))
	}
	st.Recent = make([]Item, 0, len(s.localSync.recent))
	for _, it := range s.localSync.recent {
		st.Recent = append(st.Recent, itemSnapshot(it))
	}
	return st
}
