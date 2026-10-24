package httpapi

import (
	"encoding/json"
	"net/http"
	"strconv"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
)

func TestRecommendationEmptyMiddlePageKeepsNextOffset(t *testing.T) {
	env := newTestEnv(t)
	all := recList(t, env, "?seed=42&limit=3")
	if len(all) != 3 {
		t.Fatalf("fixture has %d assets", len(all))
	}
	// 与快照页资格复核用例同样模拟：排序已缓存，变更通知尚未到达。
	if _, err := env.conn.Exec("DELETE FROM assets WHERE asset_id=?", *all[1].Id); err != nil {
		t.Fatal(err)
	}
	var seen []string
	var lengths []int
	for offset, requests := 0, 0; offset >= 0; requests++ {
		if requests >= 4 {
			t.Fatal("offset did not terminate")
		}
		resp := env.do(t, http.MethodGet, "/api/v1/recommendations?seed=42&limit=1&offset="+strconv.Itoa(offset), "")
		var page []gen.AssetSummary
		err := json.NewDecoder(resp.Body).Decode(&page)
		closeErr := resp.Body.Close()
		if err != nil || closeErr != nil || resp.StatusCode != http.StatusOK {
			t.Fatalf("response: status=%d decode=%v close=%v", resp.StatusCode, err, closeErr)
		}
		lengths = append(lengths, len(page))
		for _, asset := range page {
			seen = append(seen, asset.Id.String())
		}
		next, err := strconv.Atoi(resp.Header.Get(nextOffsetHeader))
		if err != nil || (next != -1 && next <= offset) {
			t.Fatalf("invalid next offset: %q", resp.Header.Get(nextOffsetHeader))
		}
		offset = next
	}
	if len(lengths) != 3 || lengths[0] != 1 || lengths[1] != 0 || lengths[2] != 1 {
		t.Fatalf("expected first/empty/tail pages, got %v", lengths)
	}
	if len(seen) != 2 || seen[0] != all[0].Id.String() || seen[1] != all[2].Id.String() {
		t.Fatalf("remaining assets skipped/repeated: %v", seen)
	}
}

func TestOffsetHeadersOnFinalAndOutOfRangePages(t *testing.T) {
	for _, endpoint := range []string{"recommendations?seed=1", "rankings?period=all"} {
		for _, offset := range []string{"0", "99"} {
			env := newTestEnv(t)
			resp := env.do(t, http.MethodGet, "/api/v1/"+endpoint+"&limit=3&offset="+offset, "")
			if err := resp.Body.Close(); err != nil {
				t.Fatal(err)
			}
			if resp.StatusCode != http.StatusOK || resp.Header.Get(nextOffsetHeader) != "-1" {
				t.Fatalf("%s offset=%s: status=%d next=%q", endpoint, offset, resp.StatusCode, resp.Header.Get(nextOffsetHeader))
			}
		}
	}
}
