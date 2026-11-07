package portablebackup

import (
	"encoding/json"
	"io"
	"log/slog"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"qimeng-media/server/internal/creatorprofile"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// 1x1 PNG：与 httpapi 离线头像 fixture 同源，用于验证 replace 携带离线图片。
const backupProfileAvatarBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII="

const (
	backupProfileAvatarURL = "https://example.org/backup-avatar.png"
	localProfileAvatarURL  = "https://example.org/local-avatar.png"
)

func newPortableImportService(t *testing.T) (*Service, *db.Queries) {
	t.Helper()
	conn, err := store.Open(filepath.Join(t.TempDir(), "portable-import.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		if err := conn.Close(); err != nil {
			t.Error(err)
		}
	})
	if err := store.Migrate(conn); err != nil {
		t.Fatal(err)
	}
	q := db.New(conn)
	now := time.Date(2026, 11, 8, 10, 0, 0, 0, time.UTC)
	return New(Options{Conn: conn, Queries: q, Now: func() time.Time { return now },
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil))}), q
}

// seedExistingAuthorProfile 造一位本机已有档案的作者（候选状态，避免触发
// X/译文的补充分支，锁定 keep 的「仅保留提示」行为）。
func seedExistingAuthorProfile(t *testing.T, q *db.Queries, id string) creatorprofile.Profile {
	t.Helper()
	if err := q.UpsertAuthor(t.Context(), db.UpsertAuthorParams{
		ID: id, DisplayName: "本机作者", Type: "regular",
		CreatedAt: store.FormatTimestamp(time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)),
		Origin:    "client",
	}); err != nil {
		t.Fatal(err)
	}
	local := creatorprofile.Empty(id)
	local.Revision = 3
	local.Status = "candidate"
	local.Nickname = "本机昵称"
	local.Biography = "本机简介"
	local.AvatarURL = localProfileAvatarURL
	local.AvatarData = []byte("local-avatar-bytes")
	local.XProfile = &creatorprofile.XProfile{URL: "https://x.com/local_author", Biography: "本机X原文"}
	if err := creatorprofile.Persist(t.Context(), q, local, time.Date(2026, 2, 1, 0, 0, 0, 0, time.UTC)); err != nil {
		t.Fatal(err)
	}
	return local
}

// backupAuthorProfile 构造与导出同形的备份档案：全部可替换字段互不相同，
// 并开启自动维护以验证 replace 强制关闭。
func backupAuthorProfile(t *testing.T, id string) (string, creatorprofile.Profile) {
	t.Helper()
	backup := creatorprofile.Empty(id)
	backup.Revision = 99
	backup.Status = "verified"
	backup.Nickname = "备份昵称"
	backup.Biography = "备份简介"
	backup.HomepageURL = "https://example.org/creator"
	backup.EvidenceURLs = []string{"https://example.org/evidence"}
	backup.AvatarURL = backupProfileAvatarURL
	backup.AutoRefresh = true
	backup.FeedURL = "https://example.org/feed.xml"
	backup.TrackUpdates = true
	backup.XProfile = &creatorprofile.XProfile{URL: "https://x.com/backup_author", Biography: "备份X原文"}
	raw, err := json.Marshal(backup)
	if err != nil {
		t.Fatal(err)
	}
	return string(raw), backup
}

func authorProfileBackup(id, profileJSON, avatarBase64 string) *BackupImport {
	return &BackupImport{
		Format: Format, SchemaVersion: SchemaVersion, AppIdentifier: "synthetic",
		Data: BackupData{
			Authors: &[]Author{{
				AuthorId: id, DisplayName: "备份作者",
				ProfileJson: &profileJSON, AvatarData: &avatarBase64,
			}},
			ViewEvents: &[]ViewEvent{},
		},
	}
}

func hasImportWarning(warnings *[]string, fragment string) bool {
	if warnings == nil {
		return false
	}
	for _, warning := range *warnings {
		if strings.Contains(warning, fragment) {
			return true
		}
	}
	return false
}

func TestImportKeepsExistingAuthorProfileByDefault(t *testing.T) {
	service, q := newPortableImportService(t)
	local := seedExistingAuthorProfile(t, q, "author_profile_keep")
	profileJSON, _ := backupAuthorProfile(t, local.AuthorID)

	result, err := service.Import(t.Context(), authorProfileBackup(local.AuthorID, profileJSON, backupProfileAvatarBase64))
	if err != nil {
		t.Fatal(err)
	}
	current, err := creatorprofile.Load(t.Context(), q, local.AuthorID)
	if err != nil {
		t.Fatal(err)
	}
	if current.Revision != local.Revision || current.Nickname != local.Nickname || current.Biography != local.Biography ||
		current.AvatarURL != local.AvatarURL || !reflect.DeepEqual(current.AvatarData, local.AvatarData) ||
		!reflect.DeepEqual(current.XProfile, local.XProfile) {
		t.Fatalf("默认 keep 改写了本机档案: %+v", current)
	}
	if result.AuthorProfilesReplaced != nil {
		t.Fatalf("默认 keep 不应报告替换数: %d", *result.AuthorProfilesReplaced)
	}
	if !hasImportWarning(result.Warnings, "已保留本机已有资料") {
		t.Fatalf("默认 keep 应保留冲突提示: %v", result.Warnings)
	}
}

func TestImportReplacesExistingAuthorProfileOnRequest(t *testing.T) {
	service, q := newPortableImportService(t)
	local := seedExistingAuthorProfile(t, q, "author_profile_replace")
	profileJSON, incoming := backupAuthorProfile(t, local.AuthorID)
	wantAvatar, err := creatorprofile.DecodeAvatar(backupProfileAvatarBase64)
	if err != nil {
		t.Fatal(err)
	}

	result, err := service.Import(t.Context(), authorProfileBackup(local.AuthorID, profileJSON, backupProfileAvatarBase64),
		ImportOptions{AuthorProfileConflict: AuthorProfileConflictReplace})
	if err != nil {
		t.Fatal(err)
	}
	current, err := creatorprofile.Load(t.Context(), q, local.AuthorID)
	if err != nil {
		t.Fatal(err)
	}
	if current.Revision != local.Revision+1 {
		t.Fatalf("replace 后 Revision = %d，want %d", current.Revision, local.Revision+1)
	}
	if current.Status != incoming.Status || current.Nickname != incoming.Nickname || current.Biography != incoming.Biography ||
		current.HomepageURL != incoming.HomepageURL || current.AvatarURL != incoming.AvatarURL ||
		current.FeedURL != incoming.FeedURL || !reflect.DeepEqual(current.EvidenceURLs, incoming.EvidenceURLs) ||
		!reflect.DeepEqual(current.XProfile, incoming.XProfile) {
		t.Fatalf("replace 未以备份整份覆盖: %+v", current)
	}
	if current.AutoRefresh || current.TrackUpdates {
		t.Fatalf("replace 应关闭自动维护: autoRefresh=%v trackUpdates=%v", current.AutoRefresh, current.TrackUpdates)
	}
	if !reflect.DeepEqual(current.AvatarData, wantAvatar) {
		t.Fatalf("replace 未采用备份离线头像: %x", current.AvatarData)
	}
	if result.AuthorProfilesReplaced == nil || *result.AuthorProfilesReplaced != 1 {
		t.Fatalf("AuthorProfilesReplaced = %v，want 1", result.AuthorProfilesReplaced)
	}
	if result.AuthorsImported == nil || *result.AuthorsImported != 1 {
		t.Fatalf("AuthorsImported = %v，want 1", result.AuthorsImported)
	}
	if hasImportWarning(result.Warnings, "已保留本机已有资料") {
		t.Fatalf("replace 不应再提示保留本机: %v", result.Warnings)
	}
}
