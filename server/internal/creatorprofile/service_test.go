package creatorprofile

import (
	"context"
	"errors"
	"log/slog"
	"net/netip"
	"path/filepath"
	"testing"
	"time"

	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

func testService(t *testing.T) (*Service, *db.Queries) {
	t.Helper()
	conn, err := store.Open(filepath.Join(t.TempDir(), "profiles.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := conn.Close(); err != nil {
			t.Error(err)
		}
	})
	if err = store.Migrate(conn); err != nil {
		t.Fatal(err)
	}
	q := db.New(conn)
	for _, id := range []string{"synthetic", "cos_synthetic"} {
		kind := "regular"
		if id == "cos_synthetic" {
			kind = "cos"
		}
		if err = q.UpsertAuthor(t.Context(), db.UpsertAuthorParams{ID: id, DisplayName: id, Type: kind, CreatedAt: store.FormatTimestamp(time.Now()), Origin: "client"}); err != nil {
			t.Fatal(err)
		}
	}
	return New(conn, q, time.Now, slog.Default()), q
}
func TestSaveCASAndCosIsolation(t *testing.T) {
	s, _ := testService(t)
	p, err := s.Get(t.Context(), "synthetic")
	if err != nil {
		t.Fatal(err)
	}
	w := p.Write
	w.Nickname = "人工名称"
	w.Status = "candidate"
	saved, err := s.Save(t.Context(), "synthetic", w)
	if err != nil {
		t.Fatal(err)
	}
	if saved.Revision != 1 || saved.Nickname != "人工名称" {
		t.Fatal(saved)
	}
	if _, err = s.Save(t.Context(), "synthetic", w); !errors.Is(err, ErrConflict) {
		t.Fatalf("stale save: %v", err)
	}
	cos, err := s.Get(t.Context(), "cos_synthetic")
	if err != nil || cos.Nickname != "" || cos.Revision != 0 {
		t.Fatal("crossed COS identity", cos, err)
	}
	if _, err = s.Save(t.Context(), "cos_synthetic", w); err != nil {
		t.Fatal("COS ancillary editing", err)
	}
}
func TestFailedRefreshPreservesManualData(t *testing.T) {
	s, _ := testService(t)
	p := Empty("synthetic")
	p.Nickname = "手工名称"
	p.Biography = "手工简介"
	p.Status = "candidate"
	saved, err := s.Save(t.Context(), p.AuthorID, p.Write)
	if err != nil {
		t.Fatal(err)
	}
	refreshed, err := s.Refresh(t.Context(), p.AuthorID)
	if err != nil {
		t.Fatal(err)
	}
	if refreshed.Nickname != saved.Nickname || refreshed.Biography != saved.Biography || refreshed.LastError == "" || refreshed.LastSuccessAt != "" {
		t.Fatal(refreshed)
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if _, err = s.Refresh(ctx, p.AuthorID); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancel not propagated: %v", err)
	}
}
func TestMoveAndDeletePreventProfileResurrection(t *testing.T) {
	s, q := testService(t)
	p := Empty("synthetic")
	p.Nickname = "保留资料"
	p.Status = "candidate"
	if _, err := s.Save(t.Context(), p.AuthorID, p.Write); err != nil {
		t.Fatal(err)
	}
	if err := Move(t.Context(), q, "synthetic", "cos_synthetic", time.Now()); err != nil {
		t.Fatal(err)
	}
	moved, err := s.Get(t.Context(), "cos_synthetic")
	if err != nil || moved.Nickname != "保留资料" {
		t.Fatal(moved, err)
	}
	if _, err = q.DeleteAuthor(t.Context(), "cos_synthetic"); err != nil {
		t.Fatal(err)
	}
	deleted, err := Load(t.Context(), q, "cos_synthetic")
	if err != nil || deleted.Revision != 0 {
		t.Fatal("deleted profile survived", deleted, err)
	}
}
func TestPublicURLAndAddressBoundaries(t *testing.T) {
	for _, raw := range []string{"http://example.com", "https://localhost", "https://127.0.0.1", "https://192.168.1.1", "https://example.com:444/", "https://user:pass@example.com/", "https://example.com/#fragment", "https://example.local/"} {
		if _, err := publicURL(raw); err == nil {
			t.Errorf("allowed unsafe URL %s", raw)
		}
	}
	for _, raw := range []string{"0.1.2.3", "10.0.0.1", "127.0.0.1", "169.254.169.254", "100.64.0.1", "198.18.0.1", "240.0.0.1", "::1", "::ffff:127.0.0.1", "fc00::1", "2001:db8::1", "64:ff9b::a00:1"} {
		if publicIP(netip.MustParseAddr(raw)) {
			t.Errorf("allowed unsafe IP %s", raw)
		}
	}
	if _, err := publicURL("https://example.com/profile"); err != nil {
		t.Fatal(err)
	}
	if !publicIP(netip.MustParseAddr("8.8.8.8")) {
		t.Fatal("public IP rejected")
	}
}
func TestMaintenanceRequiresExplicitVerifiedSources(t *testing.T) {
	for _, w := range []Write{{Status: "candidate", AutoRefresh: true, HomepageURL: "https://example.com"}, {Status: "verified", TrackUpdates: true, HomepageURL: "https://example.com", EvidenceURLs: []string{"https://example.com"}}, {Status: "verified", HomepageURL: "https://example.com"}} {
		if Validate(w) == nil {
			t.Fatal("unverified/unsupported maintenance enabled", w)
		}
	}
	s, _ := testService(t)
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	done := make(chan struct{})
	go func() { s.Run(ctx); close(done) }()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("maintenance did not stop")
	}
}
