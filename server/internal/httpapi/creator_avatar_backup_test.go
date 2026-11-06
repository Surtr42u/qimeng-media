package httpapi

import (
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"testing"

	"qimeng-media/server/internal/creatorprofile"
	"qimeng-media/server/internal/portablebackup"
)

const offlineAvatarPNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="

func TestBackupRestoresAvatarIntoExistingProfileAndRoundTrips(t *testing.T) {
	env := newTestEnv(t)
	code, author := env.createAuthor(t, "offline_avatar")
	if code != http.StatusOK || author.Id == nil {
		t.Fatalf("create author: %d", code)
	}
	id := *author.Id
	profile := creatorprofile.Empty(id)
	profile.Status = "candidate"
	profile.AvatarURL = "https://example.org/avatar.png"
	profile.Biography = "local manual biography"
	if _, err := env.s.creatorProfiles.Save(t.Context(), id, profile.Write); err != nil {
		t.Fatal(err)
	}
	profile.Biography = "incoming biography"
	encoded, err := json.Marshal(profile)
	if err != nil {
		t.Fatal(err)
	}
	profileJSON, image := string(encoded), offlineAvatarPNG
	request := portablebackup.BackupImport{Format: "qimeng_backup", SchemaVersion: 2, AppIdentifier: "synthetic",
		Data: portablebackup.BackupData{Authors: &[]portablebackup.Author{{AuthorId: id, DisplayName: "offline_avatar", ProfileJson: &profileJSON, AvatarData: &image}}, ViewEvents: &[]portablebackup.ViewEvent{}}}
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
	path := "/api/v1/authors/" + id + "/profile"
	saved := readCreatorProfile(t, env, path)
	if saved.Biography != "local manual biography" || saved.Revision != 2 {
		t.Fatal("import overwrote existing fields or repeated image write", saved)
	}
	response := env.do(t, http.MethodGet, path+"/avatar", "")
	data, err := io.ReadAll(response.Body)
	closeBody(response)
	if err != nil || response.StatusCode != http.StatusOK || base64.StdEncoding.EncodeToString(data) != offlineAvatarPNG {
		t.Fatal("offline image endpoint failed", err, response.StatusCode)
	}
	response = env.do(t, http.MethodGet, "/api/v1/export/qimeng-backup", "")
	defer closeBody(response)
	var exported portablebackup.BackupFile
	if err = decodeBody(response, &exported); err != nil {
		t.Fatal(err)
	}
	found := false
	for _, item := range *exported.Data.Authors {
		if item.AuthorId == id {
			found = item.AvatarData != nil && *item.AvatarData == offlineAvatarPNG
		}
	}
	if !found {
		t.Fatal("export lost offline image")
	}
}

func TestInvalidBackupAvatarRejectsBeforeAuthorWrite(t *testing.T) {
	env := newTestEnv(t)
	p := creatorprofile.Empty("offline_rejected")
	p.Status = "candidate"
	p.AvatarURL = "https://example.org/avatar.png"
	rawProfile, err := json.Marshal(p)
	if err != nil {
		t.Fatal(err)
	}
	profileJSON, image := string(rawProfile), "invalid-base64"
	request := portablebackup.BackupImport{Format: "qimeng_backup", SchemaVersion: 2, AppIdentifier: "synthetic",
		Data: portablebackup.BackupData{Authors: &[]portablebackup.Author{{AuthorId: p.AuthorID, DisplayName: p.AuthorID, ProfileJson: &profileJSON, AvatarData: &image}}, ViewEvents: &[]portablebackup.ViewEvent{}}}
	raw, err := json.Marshal(request)
	if err != nil {
		t.Fatal(err)
	}
	response := env.do(t, http.MethodPost, "/api/v1/import/qimeng-backup", string(raw))
	defer closeBody(response)
	if response.StatusCode != http.StatusBadRequest {
		t.Fatalf("bad image accepted: %d", response.StatusCode)
	}
	if _, err = env.s.creatorProfiles.Get(t.Context(), p.AuthorID); err != creatorprofile.ErrNotFound {
		t.Fatal("invalid input wrote author", err)
	}
}
