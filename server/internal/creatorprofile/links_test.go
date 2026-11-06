package creatorprofile

import (
	"context"
	"reflect"
	"testing"
	"time"
)

func TestVerifiedLinksExcludesCandidateAndKeepsSavedEvidence(t *testing.T) {
	_, q := testService(t)
	p := Empty("synthetic")
	p.HomepageURL = "https://example.org/creator"
	p.EvidenceURLs = []string{"https://example.org/evidence"}
	p.XProfile = &XProfile{URL: "https://x.com/synthetic", Biography: "bio"}
	p.Status = "candidate"
	if err := Persist(t.Context(), q, p, time.Now()); err != nil {
		t.Fatal(err)
	}
	if links, err := VerifiedLinks(t.Context(), q, p.AuthorID); err != nil || len(links) != 0 {
		t.Fatal(links, err)
	}
	p.Status = "verified"
	if err := Persist(t.Context(), q, p, time.Now()); err != nil {
		t.Fatal(err)
	}
	links, err := VerifiedLinks(t.Context(), q, p.AuthorID)
	if err != nil || !reflect.DeepEqual(links, []string{p.HomepageURL, p.XProfile.URL, p.EvidenceURLs[0]}) {
		t.Fatal(links, err)
	}
}

func TestAfterSaveOnlyRunsFollowingCommittedSave(t *testing.T) {
	s, _ := testService(t)
	calls := 0
	s.AfterSave = func(_ context.Context) { calls++ }
	p := Empty("synthetic")
	p.Status = "candidate"
	if _, err := s.Save(t.Context(), p.AuthorID, p.Write); err != nil {
		t.Fatal(err)
	}
	if calls != 1 {
		t.Fatal(calls)
	}
	if _, err := s.Save(t.Context(), p.AuthorID, p.Write); err == nil {
		t.Fatal("stale save accepted")
	}
	if calls != 1 {
		t.Fatal("callback ran after failed save")
	}
}
