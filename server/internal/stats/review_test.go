package stats

import "testing"

func TestReviewFirstAndRepeatConserveOpens(t *testing.T) {
	result := BuildReview(ReviewCounts{Views: 8, Files: 3, FirstViews: 2, PreviousViews: 4},
		[]InterestCounts{{Kind: "tag", ID: "a", Current: 2, Previous: 2}}, []HourCount{{Hour: 23, Views: 8}}, true)
	if result.RepeatViews != 6 || result.FirstViews+result.RepeatViews != result.Views || !result.ComparisonAvailable {
		t.Fatal(result)
	}
	if result.Interests[0].CurrentShare != 25 || result.Interests[0].PreviousShare != 50 || result.Interests[0].ChangePoints != -25 {
		t.Fatal(result.Interests)
	}
	if len(result.Hours) != 24 || result.Hours[0].Hour != 0 || result.Hours[23].Views != 8 {
		t.Fatal(result.Hours)
	}
}

func TestReviewDoesNotInventComparison(t *testing.T) {
	for _, counts := range []ReviewCounts{{}, {Views: 3, FirstViews: 1}, {PreviousViews: 2}} {
		result := BuildReview(counts, nil, nil, true)
		if result.ComparisonAvailable || len(result.Interests) != 0 || len(result.Hours) != 24 {
			t.Fatal(result)
		}
	}
	result := BuildReview(ReviewCounts{Views: 5, PreviousViews: 5}, nil, nil, false)
	if result.ComparisonAvailable {
		t.Fatal("all-time must not compare a fabricated previous period")
	}
}
