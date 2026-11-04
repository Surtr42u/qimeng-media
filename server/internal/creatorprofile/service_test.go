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
	if err != nil || deleted.Revision != moved.Revision+1 || deleted.Nickname != "" {
		t.Fatal("deleted profile survived", deleted, err)
	}
}
func recreateAuthor(t *testing.T, q *db.Queries, id string) {
	t.Helper()
	if _, err := q.DeleteAuthor(t.Context(), id); err != nil {
		t.Fatal(err)
	}
	if err := q.UpsertAuthor(t.Context(), db.UpsertAuthorParams{ID: id, DisplayName: id, Type: "regular", CreatedAt: store.FormatTimestamp(time.Now()), Origin: "client"}); err != nil {
		t.Fatal(err)
	}
}
func TestDeletedAuthorGenerationRejectsStaleInitialAndSavedPUT(t *testing.T) {
	for _, previouslySaved := range []bool{false, true} {
		s, q := testService(t)
		old, err := s.Get(t.Context(), "synthetic")
		if err != nil {
			t.Fatal(err)
		}
		if previouslySaved {
			old.Nickname = "old"
			old.Status = "candidate"
			old, err = s.Save(t.Context(), old.AuthorID, old.Write)
			if err != nil {
				t.Fatal(err)
			}
		}
		recreateAuthor(t, q, old.AuthorID)
		current, err := s.Get(t.Context(), old.AuthorID)
		if err != nil {
			t.Fatal(err)
		}
		if current.Revision <= old.Revision || !IsEmpty(current) {
			t.Fatal("generation reused or old data resurrected", current)
		}
		if previouslySaved {
			current.Nickname = "new"
			current.Status = "candidate"
			if _, err = s.Save(t.Context(), current.AuthorID, current.Write); err != nil {
				t.Fatal(err)
			}
		}
		if _, err = s.Save(t.Context(), old.AuthorID, old.Write); !errors.Is(err, ErrConflict) {
			t.Fatal("late PUT accepted", err)
		}
	}
}
func TestInflightRefreshCannotCrossDeletedAuthorGeneration(t *testing.T) {
	s, q := testService(t)
	p := Empty("synthetic")
	p.HomepageURL = "https://example.com"
	p.Status = "candidate"
	if _, err := s.Save(t.Context(), p.AuthorID, p.Write); err != nil {
		t.Fatal(err)
	}
	entered, release := make(chan struct{}), make(chan struct{})
	s.readMetadata = func(ctx context.Context, _ string) (Candidate, error) {
		close(entered)
		select {
		case <-release:
			return Candidate{Nickname: "stale", EvidenceURLs: []string{}}, nil
		case <-ctx.Done():
			return Candidate{}, ctx.Err()
		}
	}
	done := make(chan error, 1)
	go func() { _, err := s.Refresh(t.Context(), p.AuthorID); done <- err }()
	select {
	case <-entered:
	case <-time.After(time.Second):
		t.Fatal("refresh did not enter fetch")
	}
	recreateAuthor(t, q, p.AuthorID)
	current, err := s.Get(t.Context(), p.AuthorID)
	if err != nil {
		t.Fatal(err)
	}
	current.Nickname = "new generation"
	current.Status = "candidate"
	if _, err = s.Save(t.Context(), p.AuthorID, current.Write); err != nil {
		t.Fatal(err)
	}
	close(release)
	if err = <-done; !errors.Is(err, ErrConflict) {
		t.Fatal("late refresh accepted", err)
	}
	current, err = s.Get(t.Context(), p.AuthorID)
	if err != nil || current.Nickname != "new generation" || current.Candidate.Nickname != "" {
		t.Fatal(current, err)
	}
}
func TestMoveUsesTargetHistoricRevisionFloor(t *testing.T) {
	s, q := testService(t)
	old := Empty("cos_synthetic")
	old.Nickname = "previous target"
	old.Status = "candidate"
	for range 3 {
		var err error
		old, err = s.Save(t.Context(), old.AuthorID, old.Write)
		if err != nil {
			t.Fatal(err)
		}
	}
	if _, err := q.DeleteAuthor(t.Context(), old.AuthorID); err != nil {
		t.Fatal(err)
	}
	floor, err := Load(t.Context(), q, old.AuthorID)
	if err != nil {
		t.Fatal(err)
	}
	if err = Move(t.Context(), q, "synthetic", old.AuthorID, time.Now()); err != nil {
		t.Fatal(err)
	}
	moved, err := Load(t.Context(), q, old.AuthorID)
	if err != nil || moved.Revision <= floor.Revision {
		t.Fatal(moved, err)
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
func TestHTMLMetadataHandlesBrowserSyntaxWithoutInventingAvatar(t *testing.T) {
	data := []byte(`<html><head><script>if (a < b) { document.write("<meta name=description content=fake>"); }</script><meta property=og:title content="Creator &amp; Studio"><meta name=description content="Public biography"><meta property=og:image content="https://example.com/artwork.jpg"></head><body>Content</body></html>`)
	p, err := parseMetadata(data, "https://example.com/profile")
	if err != nil || p.Nickname != "Creator & Studio" || p.Biography != "Public biography" || p.AvatarURL != "" {
		t.Fatal(p, err)
	}
}
func TestRSSAtomLinksAreBoundedAndSafe(t *testing.T) {
	for _, raw := range []string{`<rss><channel><item><guid>public-1</guid><title>Work</title><link>https://example.com/1</link></item><item><guid>bad</guid><link>javascript:alert(1)</link></item><item><guid>public-1</guid><link>https://example.com/duplicate</link></item></channel></rss>`, `<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>public-1</id><title>Work</title><link href="https://example.com/1" rel="alternate"/></entry></feed>`} {
		updates, err := parseFeed([]byte(raw))
		if err != nil || len(updates) != 1 || updates[0].URL != "https://example.com/1" {
			t.Fatal(updates, err)
		}
	}
}
