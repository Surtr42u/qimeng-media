package httpapi

import (
	"encoding/json"
	"net/http"
	"reflect"
	"testing"

	"qimeng-media/server/internal/creatorprofile"
	"qimeng-media/server/internal/portablebackup"
)

func TestXProfilePreservesRawTextAcrossLegacySaves(t *testing.T) {
	env := newTestEnv(t)
	code, author := env.createAuthor(t, "x_profile_source")
	if code != http.StatusOK || author.Id == nil {
		t.Fatal("create author failed")
	}
	id := *author.Id
	p := creatorprofile.Empty(id)
	p.Status = "verified"
	p.HomepageURL = "https://example.org/creator"
	p.Biography = "curated summary"
	p.EvidenceURLs = []string{"https://x.com/synthetic_one"}
	p.XProfile = &creatorprofile.XProfile{URL: "https://x.com/synthetic_one", Biography: "Original bio 🌸\nSecond line"}
	saved, err := env.s.creatorProfiles.Save(t.Context(), id, p.Write)
	if err != nil {
		t.Fatal(err)
	}
	legacy := saved.Write
	legacy.XProfile = nil
	legacy.Biography = "edited curated summary"
	raw, err := json.Marshal(legacy)
	if err != nil {
		t.Fatal(err)
	}
	path := "/api/v1/authors/" + id + "/profile"
	response := env.do(t, http.MethodPut, path, string(raw))
	closeBody(response)
	if response.StatusCode != http.StatusOK {
		t.Fatalf("legacy save: %d", response.StatusCode)
	}
	current := readCreatorProfile(t, env, path)
	if !reflect.DeepEqual(current.XProfile, p.XProfile) || current.Biography != legacy.Biography {
		t.Fatal("legacy save lost raw X text")
	}
	for _, invalid := range []any{
		nil,
		map[string]any{"url": "https://example.org/user", "biography": "x"},
		map[string]any{"url": "https://x.com/synthetic_one/status/1", "biography": "x"},
		map[string]any{"url": "https://x.com/search", "biography": "x"},
		map[string]any{"url": "https://x.com/synthetic_one"},
		map[string]any{"url": "", "biography": "orphaned source"},
	} {
		raw, err = json.Marshal(current.Write)
		if err != nil {
			t.Fatal(err)
		}
		var body map[string]any
		if err = json.Unmarshal(raw, &body); err != nil {
			t.Fatal(err)
		}
		body["xProfile"] = invalid
		raw, err = json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
		response = env.do(t, http.MethodPut, path, string(raw))
		closeBody(response)
		if response.StatusCode != http.StatusBadRequest {
			t.Fatalf("invalid X source accepted: %d", response.StatusCode)
		}
		if got := readCreatorProfile(t, env, path); !reflect.DeepEqual(got, current) {
			t.Fatal("invalid request changed stored data")
		}
	}
}

func TestBackupFillsMissingXProfileWithoutOverwritingManualFields(t *testing.T) {
	env := newTestEnv(t)
	code, author := env.createAuthor(t, "x_backup")
	if code != http.StatusOK || author.Id == nil {
		t.Fatal("create author failed")
	}
	id := *author.Id
	p := creatorprofile.Empty(id)
	p.Status = "verified"
	p.HomepageURL = "https://example.org/creator"
	p.Biography = "keep local summary"
	p.EvidenceURLs = []string{"https://twitter.com/synthetic_one"}
	saved, err := env.s.creatorProfiles.Save(t.Context(), id, p.Write)
	if err != nil {
		t.Fatal(err)
	}
	incoming := saved
	incoming.Biography = "incoming summary"
	incoming.XProfile = &creatorprofile.XProfile{URL: "https://x.com/synthetic_one", Biography: "X source text"}
	profileBytes, err := json.Marshal(incoming)
	if err != nil {
		t.Fatal(err)
	}
	profileJSON := string(profileBytes)
	request := portablebackup.BackupImport{Format: "qimeng_backup", SchemaVersion: 2, AppIdentifier: "synthetic", Data: portablebackup.BackupData{
		Authors: &[]portablebackup.Author{{AuthorId: id, DisplayName: "x_backup", ProfileJson: &profileJSON}}, ViewEvents: &[]portablebackup.ViewEvent{},
	}}
	raw, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	for range 2 {
		response := env.do(t, http.MethodPost, "/api/v1/import/qimeng-backup", string(raw))
		closeBody(response)
		if response.StatusCode != http.StatusOK {
			t.Fatalf("import: %d", response.StatusCode)
		}
	}
	got := readCreatorProfile(t, env, "/api/v1/authors/"+id+"/profile")
	if got.Revision != saved.Revision+1 || got.Biography != saved.Biography || !reflect.DeepEqual(got.XProfile, incoming.XProfile) {
		t.Fatal("X fill lost manual data or is not idempotent")
	}
}
