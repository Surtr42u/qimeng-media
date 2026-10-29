package httpapi

import (
	"encoding/json"
	"net/http"
	"qimeng-media/server/internal/httpapi/gen"
	"testing"
)

func TestDiscoveriesContract(t *testing.T) {
	env := newTestEnv(t)
	for _, query := range []string{"?limit=0", "?limit=13", "?seed=-1"} {
		response := env.do(t, http.MethodGet, "/api/v1/discoveries"+query, "")
		if response.StatusCode != http.StatusBadRequest {
			t.Errorf("%s status=%d", query, response.StatusCode)
		}
		_ = response.Body.Close()
	}
	response := env.do(t, http.MethodGet, "/api/v1/discoveries?seed=42", "")
	defer func() { _ = response.Body.Close() }()
	if response.StatusCode != http.StatusOK {
		t.Fatal(response.StatusCode)
	}
	var items []gen.DiscoveryItem
	if err := json.NewDecoder(response.Body).Decode(&items); err != nil {
		t.Fatal(err)
	}
	if len(items) == 0 || len(items) > defaultDiscoveryLimit {
		t.Fatal(len(items))
	}
	for _, item := range items {
		if item.Asset.Id == nil || item.Asset.ThumbUrl == nil || item.Reason != "unseen" {
			t.Fatal("invalid discovery response")
		}
	}
}
