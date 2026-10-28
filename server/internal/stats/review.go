package stats

// ReviewCounts is the bounded aggregate input; only open events contribute.
type ReviewCounts struct {
	Views, Files, FirstViews, ActiveDays, PreviousViews int64
}

type InterestCounts struct {
	Kind, ID, Name    string
	Current, Previous int64
}

type InterestChange struct {
	InterestCounts
	CurrentShare, PreviousShare, ChangePoints float64
}

type HourCount struct {
	Hour  int
	Views int64
}

type BrowsingReview struct {
	ReviewCounts
	RepeatViews         int64
	ComparisonAvailable bool
	Interests           []InterestChange
	Hours               []HourCount
}

// BuildReview keeps first/repeat accounting and share math on the server.
// Interest inputs are already bounded and ordered by the aggregate query.
func BuildReview(counts ReviewCounts, interests []InterestCounts, hours []HourCount, hasPreviousPeriod bool) BrowsingReview {
	out := BrowsingReview{ReviewCounts: counts, RepeatViews: counts.Views - counts.FirstViews,
		ComparisonAvailable: hasPreviousPeriod && counts.Views > 0 && counts.PreviousViews > 0,
		Interests:           []InterestChange{}, Hours: make([]HourCount, hoursPerDay)}
	for hour := range out.Hours {
		out.Hours[hour].Hour = hour
	}
	for _, hour := range hours {
		out.Hours[hour.Hour].Views = hour.Views
	}
	if out.ComparisonAvailable {
		for _, item := range interests {
			current := float64(item.Current) / float64(counts.Views) * 100
			previous := float64(item.Previous) / float64(counts.PreviousViews) * 100
			out.Interests = append(out.Interests, InterestChange{InterestCounts: item,
				CurrentShare: current, PreviousShare: previous, ChangePoints: current - previous})
		}
	}
	return out
}
