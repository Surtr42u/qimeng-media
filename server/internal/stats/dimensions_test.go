package stats

import (
	"testing"
	"time"
)

func TestDimensionBucketsAlignSparseAndDifferentSpans(t *testing.T) {
	for _, scope := range []Range{Range7d, RangeDay, RangeAll} {
		start := "2026-10-16"
		if scope == RangeAll {
			start = "2021-01-01"
		}
		rows := []DimensionRow{
			{DailyRow: DailyRow{Day: start, ViewCount: 3, Seconds: 9}, MediaType: MediaImage, Source: SourceNormal},
			{DailyRow: DailyRow{Day: "2026-10-22", ViewCount: 7, PlayCount: 2}, MediaType: MediaVideo, Source: SourceCos},
			{DailyRow: DailyRow{Day: "2026-10-22", ViewCount: 5}, MediaType: MediaAnimated, Source: SourceNormal},
		}
		buckets := BuildDimensionBuckets(rows, scope, localDay(2026, time.October, 22))
		var total int64
		for _, bucket := range buckets {
			if len(bucket.MediaTypes) != 3 || len(bucket.Sources) != 2 {
				t.Fatal("missing zero-valued dimensions")
			}
			var byType, bySource Totals
			for _, value := range bucket.MediaTypes {
				byType = addTotal(byType, value)
			}
			for _, value := range bucket.Sources {
				bySource = addTotal(bySource, value)
			}
			expected := Totals{bucket.ViewCount, bucket.PlayCount, bucket.Seconds}
			if byType != expected || bySource != expected {
				t.Fatalf("dimension totals do not conserve: %+v", bucket)
			}
			if bucket.MediaTypes[MediaVideo].ViewCount > 0 && !(bucket.Start <= "2026-10-22" && bucket.End >= "2026-10-22") {
				t.Fatal("late video was assigned an early image date")
			}
			total += bucket.ViewCount
		}
		if total != 15 {
			t.Fatalf("scope=%s total=%d", scope, total)
		}
		sum, types, sources := SumDimensions(rows)
		if sum.ViewCount != total || types[MediaAnimated].ViewCount != 5 || sources[SourceCos].ViewCount != 7 {
			t.Fatal("overview breakdown mismatch")
		}
	}
}

func TestDimensionEmptyInvalidAndLeadingZero(t *testing.T) {
	now := localDay(2026, time.October, 22)
	if len(BuildDimensionBuckets(nil, RangeAll, now)) != 0 {
		t.Fatal("empty must remain empty")
	}
	rows := []DimensionRow{
		{DailyRow: DailyRow{Day: "bad", ViewCount: 999}, MediaType: MediaImage, Source: SourceNormal},
		{DailyRow: DailyRow{Day: "2026-10-22", ViewCount: 4}, MediaType: MediaVideo, Source: SourceNormal},
	}
	got := BuildDimensionBuckets(rows, Range7d, now)
	if len(got) != 2 || got[0].ViewCount != 0 || got[1].MediaTypes[MediaVideo].ViewCount != 4 {
		t.Fatalf("leading zero/invalid input: %+v", got)
	}
	total, _, _ := SumDimensions(rows)
	if total.ViewCount != 4 || rows[0].ViewCount != 999 {
		t.Fatal("invalid rows counted or input mutated")
	}
}
