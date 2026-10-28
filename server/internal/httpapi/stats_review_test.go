package httpapi

import (
	"encoding/json"
	"net/http"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
)

func TestBrowsingReviewContract(t *testing.T) {
	env := newTestEnv(t)
	for _, query := range []string{"?range=7d", "?range=day", "?range=90d", "?range=all"} {
		response := env.do(t, http.MethodGet, "/api/v1/stats/review"+query, "")
		var result gen.BrowsingReview
		if response.StatusCode != http.StatusOK {
			t.Fatal(response.StatusCode)
		}
		if err := json.NewDecoder(response.Body).Decode(&result); err != nil {
			t.Fatal(err)
		}
		if err := response.Body.Close(); err != nil {
			t.Fatal(err)
		}
		if len(result.Hours) != 24 || result.FirstViews+result.RepeatViews != result.ViewCount || result.Interests == nil {
			t.Fatal(result)
		}
	}
	response := env.do(t, http.MethodGet, "/api/v1/stats/review?range=month", "")
	defer func() { _ = response.Body.Close() }()
	if response.StatusCode != http.StatusBadRequest {
		t.Fatal(response.StatusCode)
	}
}
