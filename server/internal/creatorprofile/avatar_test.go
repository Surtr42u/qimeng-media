package creatorprofile

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"
)

const avatarPNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="

func TestOfflineAvatarPersistsWithoutExposingImageInProfile(t *testing.T) {
	s, q := testService(t)
	p := Empty("synthetic")
	p.AvatarURL = "https://example.org/avatar.png"
	p.Status = "candidate"
	var err error
	p.AvatarData, err = DecodeAvatar(avatarPNG)
	if err != nil {
		t.Fatal(err)
	}
	if err = Persist(t.Context(), q, p, s.now()); err != nil {
		t.Fatal(err)
	}
	data, kind, err := s.Avatar(t.Context(), p.AuthorID)
	if err != nil || kind != "image/png" || !bytes.Equal(data, p.AvatarData) {
		t.Fatalf("offline avatar failed: %s %v", kind, err)
	}
	loaded, err := s.Get(t.Context(), p.AuthorID)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := json.Marshal(loaded)
	if err != nil || strings.Contains(string(raw), "avatarData") {
		t.Fatal("profile response exposed image", err)
	}
	w := loaded.Write
	w.Biography = "edit preserves offline image"
	saved, err := s.Save(t.Context(), p.AuthorID, w)
	if err != nil || !bytes.Equal(saved.AvatarData, data) {
		t.Fatal("editing biography lost image", err)
	}
	w = saved.Write
	w.AvatarURL = "https://example.org/new.png"
	saved, err = s.Save(t.Context(), p.AuthorID, w)
	if err != nil || len(saved.AvatarData) != 0 {
		t.Fatal("changed source retained stale image", err)
	}
}

func TestDecodeAvatarRejectsInvalidInput(t *testing.T) {
	for _, encoded := range []string{"", "%%%", "PGh0bWw+", strings.Repeat("A", 2796205)} {
		if _, err := DecodeAvatar(encoded); err == nil {
			t.Fatal("accepted invalid avatar")
		}
	}
}
