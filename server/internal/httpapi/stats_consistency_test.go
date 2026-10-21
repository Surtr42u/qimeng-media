package httpapi

import (
	"context"
	"fmt"
	"testing"

	"github.com/google/uuid"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

func TestStatsExactCountsAreIndependentOfTopLimit(t *testing.T) {
	env := newTestEnv(t)
	stamp := store.FormatTimestamp(env.clock.Now())
	for i := range 21 {
		name := fmt.Sprintf("统计作者%02d", i)
		assetID := seedAuthorFixture(t, env, name, fmt.Sprintf("统计%02d.jpg", i), false)
		postViewEvent(t, env, assetID, gen.Open, fmt.Sprintf("count-%d", i), env.clock.Now())
		tag, err := env.q.CreateTag(context.Background(), db.CreateTagParams{ID: uuid.NewString(), Name: name, CreatedAt: stamp})
		if err != nil {
			t.Fatal(err)
		}
		if err := env.q.AddAssetTag(context.Background(), db.AddAssetTagParams{AssetID: assetID, TagID: tag.ID, CreatedAt: stamp, Origin: store.OriginClient}); err != nil {
			t.Fatal(err)
		}
	}
	overview := getOverview(t, env)
	if overview.ViewedFilesCount == nil || *overview.ViewedFilesCount != 21 || overview.ViewedAuthorsCount == nil || *overview.ViewedAuthorsCount != 21 || overview.ViewedTagsCount == nil || *overview.ViewedTagsCount != 21 {
		t.Fatalf("exact counts truncated: %+v", overview)
	}
	if overview.WindowTotals == nil || overview.WindowTotals.ViewCount != 21 || overview.AvgViewsPerFile == nil || *overview.AvgViewsPerFile != 1 {
		t.Fatalf("window summary mismatch: %+v", overview)
	}
	// Every bucket uses the exact same date axis and conserves every dimension.
	buckets := getTrends(t, env, "?range=all")
	var sum int
	for _, bucket := range buckets {
		if bucket.MediaTypes == nil || bucket.Sources == nil {
			t.Fatal("dimension maps missing")
		}
		types := *bucket.MediaTypes
		sources := *bucket.Sources
		if len(types) != 3 || len(sources) != 2 {
			t.Fatal("zero-valued dimensions omitted")
		}
		typeSum := types["image"].ViewCount + types["video"].ViewCount + types["animated_image"].ViewCount
		sourceSum := sources["normal"].ViewCount + sources["cos"].ViewCount
		if bucket.ViewCount == nil || typeSum != *bucket.ViewCount || sourceSum != *bucket.ViewCount {
			t.Fatal("bucket totals mismatch")
		}
		sum += *bucket.ViewCount
	}
	if sum != overview.WindowTotals.ViewCount {
		t.Fatal("overview and trend totals differ")
	}
}
