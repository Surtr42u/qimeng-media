package thumbnailwarmup

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"sync/atomic"
	"testing"
	"time"

	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/thumbnail"
)

type scopeCandidates struct {
	rows                                []db.ListThumbnailWarmupPageRow
	fullCalls, libraryCalls, assetCalls int
	onAssets                            func()
	onFull                              func()
}

func (q *scopeCandidates) ListThumbnailWarmupPage(_ context.Context, p db.ListThumbnailWarmupPageParams) ([]db.ListThumbnailWarmupPageRow, error) {
	q.fullCalls++
	if q.onFull != nil {
		q.onFull()
	}
	return q.selectRows(p.AfterID, nil), nil
}
func (q *scopeCandidates) ListThumbnailWarmupLibraryPage(_ context.Context, p db.ListThumbnailWarmupLibraryPageParams) ([]db.ListThumbnailWarmupLibraryPageRow, error) {
	q.libraryCalls++
	rows := q.selectRows(p.AfterID, func(r db.ListThumbnailWarmupPageRow) bool { return filepath.Base(r.RootPath) == p.LibraryID })
	out := make([]db.ListThumbnailWarmupLibraryPageRow, len(rows))
	for i, r := range rows {
		out[i] = db.ListThumbnailWarmupLibraryPageRow(r)
	}
	return out, nil
}
func (q *scopeCandidates) ListThumbnailWarmupAssetsPage(_ context.Context, p db.ListThumbnailWarmupAssetsPageParams) ([]db.ListThumbnailWarmupAssetsPageRow, error) {
	q.assetCalls++
	if q.onAssets != nil {
		q.onAssets()
	}
	var ids []string
	if err := json.Unmarshal([]byte(p.AssetIdsJson.(string)), &ids); err != nil {
		return nil, err
	}
	rows := q.selectRows(p.AfterID, func(r db.ListThumbnailWarmupPageRow) bool { return slices.Contains(ids, r.AssetID) })
	out := make([]db.ListThumbnailWarmupAssetsPageRow, len(rows))
	for i, r := range rows {
		out[i] = db.ListThumbnailWarmupAssetsPageRow(r)
	}
	return out, nil
}
func (q *scopeCandidates) selectRows(after string, accept func(db.ListThumbnailWarmupPageRow) bool) []db.ListThumbnailWarmupPageRow {
	var out []db.ListThumbnailWarmupPageRow
	for _, r := range q.rows {
		if r.AssetID > after && (accept == nil || accept(r)) {
			out = append(out, r)
		}
	}
	if len(out) > pageSize {
		out = out[:pageSize]
	}
	return out
}

func scopeFixture(t *testing.T) (*Service, *scopeCandidates, *[]string) {
	t.Helper()
	root := t.TempDir()
	q := &scopeCandidates{}
	for _, library := range []string{"a", "b"} {
		libraryRoot := filepath.Join(root, library)
		if err := os.Mkdir(libraryRoot, 0o755); err != nil {
			t.Fatal(err)
		}
		for _, suffix := range []string{"1", "2"} {
			id := library + suffix
			name := id + ".jpg"
			if err := os.WriteFile(filepath.Join(libraryRoot, name), []byte("synthetic"), 0o644); err != nil {
				t.Fatal(err)
			}
			q.rows = append(q.rows, db.ListThumbnailWarmupPageRow{AssetID: id, RootPath: libraryRoot, RelPath: name, MediaType: "image", SizeBytes: 9, Mtime: "stored"})
		}
	}
	submitted := []string{}
	s := New(q, fakeThumbs{has: func(string, int64, string) bool { return false }, submit: func(task thumbnail.Task) error { submitted = append(submitted, task.AssetID); return nil }}, nil, time.Now())
	return s, q, &submitted
}

func TestScopedBackfillOnlyChecksChangedAssetsAndNeverPublishesPartialCoverage(t *testing.T) {
	s, q, submitted := scopeFixture(t)
	for range 10 {
		s.NotifyAsset("a", "a1")
	}
	scope := s.takePending()
	s.reconcileScope(t.Context(), scope)
	if q.assetCalls != 1 || q.fullCalls != 0 || q.libraryCalls != 0 || !slices.Equal(*submitted, []string{"a1"}) {
		t.Fatal("single-asset change inspected unrelated candidates")
	}
	if _, ok := s.fresh(); ok {
		t.Fatal("partial coverage published as full")
	}
	s.NotifyAsset("a", "a1")
	s.NotifyLibrary("a")
	s.NotifyAsset("b", "b1")
	s.reconcileScope(t.Context(), s.takePending())
	if q.libraryCalls != 1 || q.assetCalls != 2 {
		t.Fatal("mixed library and asset scopes not retained")
	}
	s.reconcile(t.Context())
	if snap, ok := s.fresh(); !ok || snap.Total != 4 {
		t.Fatal("full reconciliation did not restore full coverage")
	}
}

func TestPendingScopePromotesWithoutDroppingWork(t *testing.T) {
	s, _, _ := scopeFixture(t)
	for i := 0; i <= maxPendingChanges; i++ {
		s.NotifyAsset("a", fmt.Sprint(i))
	}
	if len(s.pending.libraries) != 1 || len(s.pending.assets) != 0 || s.pending.all {
		t.Fatal("one-library overflow not promoted to library")
	}
	s.takePending()
	for i := 0; i <= maxPendingChanges; i++ {
		s.NotifyLibrary(fmt.Sprint(i))
	}
	if !s.pending.all || len(s.pending.libraries) != 0 || len(s.pending.assets) != 0 {
		t.Fatal("many-library overflow not promoted to full")
	}
}

func TestInFlightChangeSurvivesAndPartialWorkDoesNotPostponeFullReconcile(t *testing.T) {
	s, q, submitted := scopeFixture(t)
	s.NotifyAsset("a", "a2")
	q.onAssets = func() { q.onAssets = nil; s.NotifyAsset("a", "a1") }
	s.reconcileScope(t.Context(), s.takePending())
	s.reconcileScope(t.Context(), s.takePending())
	if !slices.Equal(*submitted, []string{"a2", "a1"}) {
		t.Fatal("notification before previous cursor was lost")
	}
	ticks := make(chan time.Time)
	full := make(chan struct{})
	var resets atomic.Int32
	q.onFull = func() { close(full) }
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	done := make(chan struct{})
	go func() { defer close(done); s.runLoop(ctx, 0, ticks, func() { resets.Add(1) }) }()
	ticks <- time.Now()
	<-full
	cancel()
	<-done
	if resets.Load() != 1 {
		t.Fatal("full reconciliation clock not independent")
	}
}
