package statsread

import (
	"context"
	"time"

	"qimeng-media/server/internal/stats"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// Matches BrowsingReview.interests maxItems in api/openapi.yaml.
const reviewInterestLimit = 6

type ReviewResult struct {
	FromDay, ToDay, PreviousFromDay, PreviousToDay string
	Data                                           stats.BrowsingReview
}

// LoadReview reuses the existing local-day windows. Adjacent comparison uses
// calendar days, so daylight-saving boundaries do not shift the day count.
func LoadReview(ctx context.Context, q *db.Queries, rng stats.Range, now time.Time) (ReviewResult, error) {
	var result ReviewResult
	local := now.Local()
	today := time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, local.Location())
	result.ToDay = store.FormatDay(today)
	fromStamp := ""
	if start := WindowStart(rng, today); !start.IsZero() {
		result.FromDay = store.FormatDay(start)
		fromStamp = store.FormatTimestamp(start)
		// UTC calendar projections count days without DST-duration arithmetic.
		startUTC := time.Date(start.Year(), start.Month(), start.Day(), 0, 0, 0, 0, time.UTC)
		todayUTC := time.Date(today.Year(), today.Month(), today.Day(), 0, 0, 0, 0, time.UTC)
		days := int(todayUTC.Sub(startUTC)/(hoursInDay*time.Hour)) + 1
		result.PreviousFromDay = store.FormatDay(start.AddDate(0, 0, -days))
		result.PreviousToDay = store.FormatDay(start.AddDate(0, 0, -1))
	}
	counts, err := q.SummarizeBrowsingReview(ctx, db.SummarizeBrowsingReviewParams{
		FromDay: result.FromDay, ToDay: result.ToDay, PreviousFromDay: result.PreviousFromDay})
	if err != nil {
		return result, err
	}
	changes, err := q.ListReviewInterestChanges(ctx, db.ListReviewInterestChangesParams{
		FromDay: result.FromDay, ToDay: result.ToDay, PreviousFromDay: result.PreviousFromDay, MaxItems: reviewInterestLimit})
	if err != nil {
		return result, err
	}
	hourRows, err := q.ListReviewHours(ctx, db.ListReviewHoursParams{FromStamp: fromStamp, ToStamp: store.FormatTimestamp(today.AddDate(0, 0, 1))})
	if err != nil {
		return result, err
	}
	interests := make([]stats.InterestCounts, len(changes))
	for i, row := range changes {
		interests[i] = stats.InterestCounts{Kind: row.Kind, ID: row.ID, Name: row.Name, Current: row.CurrentViews, Previous: row.PreviousViews}
	}
	hours := make([]stats.HourCount, len(hourRows))
	for i, row := range hourRows {
		hours[i] = stats.HourCount{Hour: int(row.Hour), Views: row.Views}
	}
	result.Data = stats.BuildReview(stats.ReviewCounts{Views: counts.ViewCount, Files: counts.UniqueFiles, FirstViews: counts.FirstViews,
		ActiveDays: counts.ActiveDays, PreviousViews: counts.PreviousViewCount}, interests, hours, result.FromDay != "")
	return result, nil
}

const hoursInDay = 24
