package httpapi

import (
	"context"
	"testing"

	"github.com/google/uuid"

	"qimeng-media/server/internal/store/db"
)

func TestDateCountsTotalStillIncludesUnknownDates(t *testing.T) {
	env := newTestEnv(t)
	for i, mtime := range []string{"", "not-a-timestamp", "2026-10-19T00:00:00.000Z"} {
		name := []string{"unknown.jpg", "invalid.jpg", "known.jpg"}[i]
		if _, err := env.q.UpsertAsset(context.Background(), db.UpsertAssetParams{
			AssetID: uuid.NewString(), LibraryID: env.libID, RelPath: name, FileName: name,
			MediaType: "image", SizeBytes: 1024, Mtime: mtime, CreatedAt: "2026-10-19", UpdatedAt: "2026-10-19",
		}); err != nil {
			t.Fatal(err)
		}
	}
	for _, filters := range []string{"", "&mediaType=image", "&favorite=true", "&directory=", "&dateFrom=2026-10-19", "&q=unknown"} {
		for _, offset := range []string{"0", "480", "-300"} {
			query := "?includeCos=true&tzOffsetMinutes=" + offset + filters
			separate := fetchAssetPage(t, env, query)
			combined := fetchAssetPage(t, env, query+"&dateCounts=true")
			if separate.TotalMatched == nil || combined.TotalMatched == nil || *separate.TotalMatched != *combined.TotalMatched {
				t.Fatalf("总数不一致 %s: separate=%v combined=%v", query, separate.TotalMatched, combined.TotalMatched)
			}
			if combined.DateCounts == nil {
				t.Fatal("日期桶不能省略")
			}
			sum := 0
			for _, bucket := range *combined.DateCounts {
				if bucket.Date == "" {
					t.Fatal("未知日期桶不能进入协议")
				}
				sum += bucket.FileCount
			}
			if filters == "" && sum != *combined.TotalMatched-2 {
				t.Fatalf("未知日期应计入总数、排除日期桶: sum=%d total=%d", sum, *combined.TotalMatched)
			}
		}
	}
}
