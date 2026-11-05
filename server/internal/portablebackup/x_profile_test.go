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
