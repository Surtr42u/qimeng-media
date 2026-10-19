package httpapi

import (
	"bytes"
	"database/sql"
	"errors"
	"io"
	"io/fs"
	"log/slog"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/scanner"
	"runtime"
	"testing"
)

func TestUploadRejectsDirectoryLink(t *testing.T) {
	e := newTestEnv(t)
	outside := t.TempDir()
	link := filepath.Join(e.media, "linked")
	if runtime.GOOS == "windows" {
		c := exec.Command("powershell", "-NoProfile", "-NonInteractive", "-Command", `New-Item -ItemType Junction -Path $env:QM_AUDIT_LINK -Target $env:QM_AUDIT_TARGET | Out-Null`)
		c.Env = append(os.Environ(), "QM_AUDIT_LINK="+link, "QM_AUDIT_TARGET="+outside)
		if out, err := c.CombinedOutput(); err != nil {
			t.Fatalf("junction: %v %s", err, out)
		}
	} else if err := os.Symlink(outside, link); err != nil {
		t.Fatal(err)
	}
	// Remove the junction itself before TempDir cleanup; never traverse it.
	t.Cleanup(func() {
		if err := os.Remove(link); err != nil {
			t.Error(err)
		}
	})
	jpg := makeJPG(t, t.TempDir(), 32, 32)
	resp := e.uploadBytes(t, e.libID, "linked", "audit.jpg", jpg)
	_ = resp.Body.Close()
	_, outsideErr := os.Stat(filepath.Join(outside, "audit.jpg"))
	t.Logf("upload_status=%d outside_file_created=%v", resp.StatusCode, outsideErr == nil)
	if outsideErr == nil || resp.StatusCode != http.StatusBadRequest {
		t.Error("upload wrote outside the registered library through a directory link")
	}
	id, ok := e.assetIDByName(t, "a.jpg")
	if !ok {
		t.Fatal("missing fixture")
	}
	resp = e.do(t, http.MethodPost, "/api/v1/assets/"+id+"/move", `{"targetDir":"linked"}`)
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("linked move target status=%d", resp.StatusCode)
	}
	if _, err := os.Stat(filepath.Join(outside, "a.jpg")); !errors.Is(err, fs.ErrNotExist) {
		t.Fatal("move escaped library", err)
	}

}

func TestCompleteRetriesDatabaseFailureWithoutDuplicate(t *testing.T) {
	e := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 32, 32)
	sess := e.createUploadSession(t, e.libID, "audit-resume.jpg", int64(len(jpg)))
	resp := e.patchUpload(t, sess.Id.String(), 0, jpg)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatal(resp.StatusCode)
	}
	if _, err := e.conn.Exec(`CREATE TRIGGER audit_insert_fail BEFORE INSERT ON assets WHEN NEW.file_name='audit-resume.jpg' BEGIN SELECT RAISE(ABORT,'synthetic database failure'); END`); err != nil {
		t.Fatal(err)
	}
	resp = e.completeUpload(t, sess.Id.String())
	_ = resp.Body.Close()
	firstStatus := resp.StatusCode
	before, err := os.ReadFile(filepath.Join(e.media, "audit-resume.jpg"))
	if err != nil {
		t.Fatal(err)
	}
	resp = e.patchUpload(t, sess.Id.String(), int64(len(jpg)), []byte("unexpected bytes"))
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("sealed PATCH status=%d", resp.StatusCode)
	}
	after, err := os.ReadFile(filepath.Join(e.media, "audit-resume.jpg"))
	if err != nil || !bytes.Equal(before, after) {
		t.Fatal("PATCH changed the published file")
	}
	if _, err := e.conn.Exec(`DROP TRIGGER audit_insert_fail`); err != nil {
		t.Fatal(err)
	}
	resp = e.completeUpload(t, sess.Id.String())
	body, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	_, fileErr := os.Stat(filepath.Join(e.media, "audit-resume.jpg"))
	snap, snapErr := e.s.uploads.Snapshot(sess.Id.String())
	t.Logf("first_status=%d retry_status=%d file_exists=%v session_error=%v offset=%d size=%d retry_body=%s", firstStatus, resp.StatusCode, fileErr == nil, snapErr, snap.Offset, snap.Size, body)
	if resp.StatusCode != http.StatusCreated || firstStatus != http.StatusInternalServerError {
		t.Error("complete cannot be retried after a transient DB failure")
	}
	entries, err := os.ReadDir(e.media)
	if err != nil {
		t.Fatal(err)
	}
	for _, item := range entries {
		if item.Name() == "audit-resume (2).jpg" {
			t.Fatal("retry created duplicate")
		}
	}
}

func TestRestoreRetriesDatabaseFailureWithOriginalIdentity(t *testing.T) {
	e := newTestEnv(t)
	id, ok := e.assetIDByName(t, "a.jpg")
	if !ok {
		t.Fatal("missing asset")
	}
	resp := e.do(t, http.MethodDelete, "/api/v1/assets/"+id, "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatal(resp.StatusCode)
	}
	items := e.trashList(t)
	if len(items) != 1 {
		t.Fatal(len(items))
	}
	if _, err := e.conn.Exec(`CREATE TRIGGER audit_restore_fail BEFORE INSERT ON assets WHEN NEW.file_name='a.jpg' BEGIN SELECT RAISE(ABORT,'synthetic database failure'); END`); err != nil {
		t.Fatal(err)
	}
	resp = e.do(t, http.MethodPost, "/api/v1/trash/"+*items[0].Id+"/restore", "")
	_ = resp.Body.Close()
	firstStatus := resp.StatusCode
	if _, err := e.conn.Exec(`DROP TRIGGER audit_restore_fail`); err != nil {
		t.Fatal(err)
	}
	// 模拟重启后的扫描器：只能从磁盘意图得知此路径保留原身份，不能抢先注册新ID。
	filing.InvalidateAllRestoreIntents(e.dataDir)
	scan := scanner.New(e.q, e.s.bus, slog.New(slog.NewTextHandler(io.Discard, nil)), e.dataDir, nil)
	scan.SetConn(e.conn)
	lib, err := e.q.GetLibrary(t.Context(), e.libID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := scan.Scan(t.Context(), lib); err == nil {
		t.Fatal("pending recovery should make scan incomplete")
	}
	if _, err := e.q.GetAsset(t.Context(), id); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("scan stole pending identity: %v", err)
	}
	resp = e.do(t, http.MethodPost, "/api/v1/trash/"+*items[0].Id+"/restore", "")
	_ = resp.Body.Close()
	_, fileErr := os.Stat(filepath.Join(e.media, "a.jpg"))
	_, oldIDErr := e.q.GetAsset(t.Context(), id)
	t.Logf("restore_status=%d retry_status=%d trash_count=%d file_exists=%v old_identity_error=%v", firstStatus, resp.StatusCode, len(e.trashList(t)), fileErr == nil, oldIDErr)
	if oldIDErr != nil || resp.StatusCode != http.StatusOK || firstStatus != http.StatusInternalServerError || len(e.trashList(t)) != 0 {
		t.Error("restore removed recovery metadata before persisting asset identity")
	}
}
