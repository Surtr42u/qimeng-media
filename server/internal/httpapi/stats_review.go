package httpapi

import (
	"net/http"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/stats"
	"qimeng-media/server/internal/statsread"
)

func (s *Server) GetApiV1StatsReview(w http.ResponseWriter, r *http.Request, params gen.GetApiV1StatsReviewParams) {
	rng := stats.Range7d
	if params.Range != nil {
		rng = stats.Range(*params.Range)
	}
	switch rng {
	case stats.Range7d, stats.RangeDay, stats.Range90d, stats.RangeAll:
	default:
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "range须为7d/day/90d/all")
		return
	}
	result, err := statsread.LoadReview(r.Context(), s.q, rng, s.now())
	if err != nil {
		s.internalErr(w, "查询浏览回顾", err)
		return
	}
	data := result.Data
	interests := make([]gen.ReviewInterest, 0, len(data.Interests))
	for _, item := range data.Interests {
		interests = append(interests, gen.ReviewInterest{Kind: gen.ReviewInterestKind(item.Kind), Id: item.ID, Name: item.Name,
			CurrentViews: item.Current, PreviousViews: item.Previous, CurrentShare: item.CurrentShare,
			PreviousShare: item.PreviousShare, ChangePoints: item.ChangePoints})
	}
	hours := make([]gen.ReviewHour, len(data.Hours))
	for i, hour := range data.Hours {
		hours[i] = gen.ReviewHour{Hour: hour.Hour, Views: hour.Views}
	}
	writeJSON(w, http.StatusOK, gen.BrowsingReview{Range: gen.BrowsingReviewRange(rng), FromDay: result.FromDay, ToDay: result.ToDay,
		PreviousFromDay: result.PreviousFromDay, PreviousToDay: result.PreviousToDay, ViewCount: data.Views,
		UniqueFiles: data.Files, FirstViews: data.FirstViews, RepeatViews: data.RepeatViews, ActiveDays: data.ActiveDays,
		PreviousViewCount: data.PreviousViews, ComparisonAvailable: data.ComparisonAvailable, Interests: interests, Hours: hours})
}
