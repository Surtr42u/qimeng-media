package stats

import (
	"testing"
	"time"
)

func TestMaterializeDailyConservesCounts(t *testing.T) {
	at := time.Date(2026, 10, 19, 0, 30, 0, 0, time.Local)
	events := []MaterializedEvent{
		{AssetID: "a", Kind: "open", StartedAt: at.Format(time.RFC3339Nano)},
		{AssetID: "a", Kind: "play", StartedAt: at.UTC().Format(time.RFC3339Nano)},
		{AssetID: "a", Kind: "dwell", StartedAt: at.Format(time.RFC3339Nano), Seconds: 17},
		{AssetID: "deleted", Kind: "open", StartedAt: at.Format(time.RFC3339Nano)},
		{AssetID: "a", Kind: "open", StartedAt: "invalid"},
	}
	got := MaterializeDaily(events, map[string]bool{"a": true}, time.Local)
	if len(got) != 1 || got[0].Day != at.Format(time.DateOnly) || got[0].Views != 1 || got[0].Plays != 1 || got[0].Seconds != 17 {
		t.Fatalf("unexpected totals: %+v", got)
	}
}
