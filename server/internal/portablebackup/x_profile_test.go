package portablebackup

import (
	"qimeng-media/server/internal/creatorprofile"
	"testing"
)

func TestMissingXProfileRequiresExistingVerifiedAccountLink(t *testing.T) {
	current := creatorprofile.Empty("synthetic")
	current.Status = "verified"
	current.EvidenceURLs = []string{"https://twitter.com/synthetic_one"}
	incoming := creatorprofile.Empty("synthetic")
	incoming.Status = "verified"
	incoming.XProfile = &creatorprofile.XProfile{URL: "https://x.com/other_account", Biography: "unrelated"}
	if canFillXProfile(current, incoming) {
		t.Fatal("unrelated X account was accepted")
	}
	incoming.XProfile.URL = "https://x.com/synthetic_one"
	current.Status = "candidate"
	if canFillXProfile(current, incoming) {
		t.Fatal("unverified local identity was accepted")
	}
	current.Status = "verified"
	current.XProfile = &creatorprofile.XProfile{URL: "https://x.com/synthetic_one", Biography: "manual original"}
	if canFillXProfile(current, incoming) {
		t.Fatal("existing X text would be overwritten")
	}
}

func TestTranslationFillProtectsOriginalAndManualClear(t *testing.T) {
	zh := "中文"
	current := creatorprofile.Empty("synthetic")
	current.Status = "verified"
	current.XProfile = &creatorprofile.XProfile{URL: "https://twitter.com/synthetic_one", Biography: "original"}
	incoming := current
	incoming.XProfile = &creatorprofile.XProfile{URL: "https://x.com/Synthetic_One", Biography: "original", BiographyZh: &zh}
	if !canFillXTranslation(current, incoming) {
		t.Fatal("same-source missing translation not fillable")
	}
	for _, change := range []func(*creatorprofile.Profile){
		func(p *creatorprofile.Profile) { p.XProfile.URL = "https://x.com/other" },
		func(p *creatorprofile.Profile) { p.XProfile.Biography = "changed" },
		func(p *creatorprofile.Profile) { p.Status = "candidate" },
	} {
		copy := incoming
		x := *incoming.XProfile
		copy.XProfile = &x
		change(&copy)
		if canFillXTranslation(current, copy) {
			t.Fatal("mismatched source accepted")
		}
	}
	empty := ""
	current.XProfile.BiographyZh = &empty
	if canFillXTranslation(current, incoming) {
		t.Fatal("manual empty translation overwritten")
	}
}

func TestProfileConflictIgnoresRevisionAndMaintenanceFlags(t *testing.T) {
	current := creatorprofile.Empty("synthetic")
	current.Biography = "manual biography"
	incoming := current
	incoming.Revision = 17
	incoming.AutoRefresh = true
	if profilesConflict(current, incoming) {
		t.Fatal("revision is not a content conflict")
	}
	incoming.Biography = "different biography"
	if !profilesConflict(current, incoming) {
		t.Fatal("changed biography was not reported")
	}
}
