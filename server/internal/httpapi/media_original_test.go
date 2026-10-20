package httpapi

import (
	"bytes"
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

func TestVideoOriginalBytesAndRange(t *testing.T) {
	env := newTestEnv(t)
	id, ok := env.assetIDByName(t, "c.mp4")
	if !ok {
		t.Fatal("synthetic video missing")
	}
	want, err := os.ReadFile(filepath.Join(env.media, "c.mp4"))
	if err != nil {
		t.Fatal(err)
	}
	detail := env.detail(t, id)
	for _, r := range []struct {
		header             string
		status, start, end int
	}{{"", http.StatusOK, 0, len(want)}, {"bytes=256-1023", http.StatusPartialContent, 256, 1024}} {
		req, err := http.NewRequest(http.MethodGet, env.ts.URL+*detail.OrigUrl, nil)
		if err != nil {
			t.Fatal(err)
		}
		if r.header != "" {
			req.Header.Set("Range", r.header)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		got := readAll(t, resp)
		if resp.StatusCode != r.status || !bytes.Equal(got, want[r.start:r.end]) {
			t.Fatalf("source bytes changed: range=%s status=%d", r.header, resp.StatusCode)
		}
	}
}
