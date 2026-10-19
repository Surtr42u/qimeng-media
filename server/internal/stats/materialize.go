package stats

import (
	"sort"
	"time"
)

// MaterializedEvent 是事件真相的最小投影，聚合只依赖显式输入。
type MaterializedEvent struct {
	AssetID, Kind, StartedAt string
	Seconds                  int64
}

// MaterializedDay 是单资产本地日聚合值。
type MaterializedDay struct {
	AssetID, Day          string
	Views, Plays, Seconds int64
}

// MaterializeDaily 使用与实时写入相同的本地日界；忽略已删资产和坏时间。
func MaterializeDaily(events []MaterializedEvent, live map[string]bool, location *time.Location) []MaterializedDay {
	type key struct{ assetID, day string }
	totals := map[key]MaterializedDay{}
	for _, e := range events {
		if !live[e.AssetID] {
			continue
		}
		at, err := time.Parse(time.RFC3339Nano, e.StartedAt)
		if err != nil {
			continue
		}
		k := key{e.AssetID, at.In(location).Format(time.DateOnly)}
		r := totals[k]
		r.AssetID, r.Day = k.assetID, k.day
		switch e.Kind {
		case "open":
			r.Views++
		case "play":
			r.Plays++
		case "dwell":
			r.Seconds += e.Seconds
		}
		totals[k] = r
	}
	out := make([]MaterializedDay, 0, len(totals))
	for _, r := range totals {
		out = append(out, r)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].AssetID != out[j].AssetID {
			return out[i].AssetID < out[j].AssetID
		}
		return out[i].Day < out[j].Day
	})
	return out
}
