package scanner

import (
	"context"
	"os"
	"testing"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/thumbnail"
)

// 隔离数据库与合成文件的异常恢复/操作交错回归。
func TestScanPreservesIdentityOnIngestFailure(t *testing.T) {
	ctx := context.Background()
	e := newTestEnv(t, nil)
	e.writeFile(t, "kept.jpg", 10)
	if _, err := e.s.Scan(ctx, e.lib); err != nil {
		t.Fatal(err)
	}
	a := e.assetByPath(t, "kept.jpg")
	if _, err := e.conn.Exec(`INSERT INTO favorites(asset_id,created_at) VALUES (?, '2026-10-19T00:00:00.000Z')`, a.AssetID); err != nil {
		t.Fatal(err)
	}
	if _, err := e.conn.Exec(`CREATE TRIGGER audit_fail_update BEFORE UPDATE ON assets BEGIN SELECT RAISE(ABORT, 'synthetic write failure'); END`); err != nil {
		t.Fatal(err)
	}
	e.writeFile(t, "kept.jpg", 20)
	res, err := e.s.Scan(ctx, e.lib)
	var favorites int
	if qerr := e.conn.QueryRow(`SELECT count(*) FROM favorites`).Scan(&favorites); qerr != nil {
		t.Fatal(qerr)
	}
	_, fileErr := os.Stat(e.abs("kept.jpg"))
	t.Logf("scan_error=%v removed=%d assets=%d favorites=%d file_exists=%v", err, res.Removed, e.assetCount(t), favorites, fileErr == nil)
	if err == nil || e.assetCount(t) != 1 || favorites != 1 {
		t.Error("an ingestion failure deleted an existing asset and its favorite")
	}
}

func TestScanConcurrentMovePreservesIdentity(t *testing.T) {
	ctx := context.Background()
	e := newTestEnv(t, nil)
	e.writeFile(t, "z/old.jpg", 10)
	if _, err := e.s.Scan(ctx, e.lib); err != nil {
		t.Fatal(err)
	}
	a := e.assetByPath(t, "z/old.jpg")
	if _, err := e.conn.Exec(`INSERT INTO favorites(asset_id,created_at) VALUES (?, '2026-10-19T00:00:00.000Z')`, a.AssetID); err != nil {
		t.Fatal(err)
	}
	e.writeFile(t, "a.mp4", 123)
	e.s.probe = func(context.Context, string) (*thumbnail.ProbeResult, error) {
		// Same filesystem+database steps as the move API, after the scan snapshot
		// but before WalkDir reads the later z/ directory.
		unlock := filing.LockLibrary(e.lib.ID)
		defer unlock()
		if err := os.Rename(e.abs("z/old.jpg"), e.abs("z/new.jpg")); err != nil {
			t.Fatal(err)
		}
		if _, err := e.q.MoveAssetPath(ctx, db.MoveAssetPathParams{AssetID: a.AssetID, RelPath: "z/new.jpg", FileName: "new.jpg", UpdatedAt: a.UpdatedAt}); err != nil {
			t.Fatal(err)
		}
		return nil, nil
	}
	res, err := e.s.Scan(ctx, e.lib)
	_, idErr := e.q.GetAsset(ctx, a.AssetID)
	var favorites int
	if qerr := e.conn.QueryRow(`SELECT count(*) FROM favorites`).Scan(&favorites); qerr != nil {
		t.Fatal(qerr)
	}
	_, fileErr := os.Stat(e.abs("z/new.jpg"))
	t.Logf("scan_error=%v moved=%d old_identity_error=%v favorites=%d moved_file_exists=%v", err, res.Moved, idErr, favorites, fileErr == nil)
	if err != nil || idErr != nil || favorites != 1 {
		t.Error("scanning concurrently with an API move destroyed the retained identity")
	}
}

// 合并的后半步失败时，候选删除及其级联关联必须一起回滚。
func TestMoveMergeRollsBackCandidateDeletion(t *testing.T) {
	e := newTestEnv(t, nil)
	e.writeFile(t, "old.jpg", 10)
	e.writeFile(t, "new.jpg", 10)
	if _, err := e.s.Scan(t.Context(), e.lib); err != nil {
		t.Fatal(err)
	}
	old := e.assetByPath(t, "old.jpg")
	cand := e.assetByPath(t, "new.jpg")
	if _, err := e.conn.Exec(`INSERT INTO favorites(asset_id,created_at) VALUES (?, '2026-10-19T00:00:00Z')`, cand.AssetID); err != nil {
		t.Fatal(err)
	}
	if _, err := e.conn.Exec(`CREATE TRIGGER reject_merge BEFORE UPDATE OF rel_path ON assets BEGIN SELECT RAISE(ABORT,'synthetic merge failure'); END`); err != nil {
		t.Fatal(err)
	}
	if err := e.s.applyMoveMerge(t.Context(), e.lib, old, cand); err == nil {
		t.Fatal("expected merge failure")
	}
	if _, err := e.q.GetAsset(t.Context(), cand.AssetID); err != nil {
		t.Fatal("candidate identity lost", err)
	}
	var favorites int
	if err := e.conn.QueryRow(`SELECT count(*) FROM favorites`).Scan(&favorites); err != nil || favorites != 1 {
		t.Fatalf("candidate favorite lost: %d %v", favorites, err)
	}
}

// 上传/恢复通道先注册的身份不能被扫描器误当作本轮新建的移动合并候选。
func TestScanDoesNotConsumeConcurrentUploadIdentity(t *testing.T) {
	e := newTestEnv(t, nil)
	e.writeFile(t, "z/old.jpg", 10)
	if _, err := e.s.Scan(t.Context(), e.lib); err != nil {
		t.Fatal(err)
	}
	old := e.assetByPath(t, "z/old.jpg")
	info, err := os.Stat(e.abs("z/old.jpg"))
	if err != nil {
		t.Fatal(err)
	}
	e.writeFile(t, "a.mp4", 20)
	const uploadID = "synthetic-upload-identity"
	e.s.probe = func(context.Context, string) (*thumbnail.ProbeResult, error) {
		unlock := filing.LockLibrary(e.lib.ID)
		defer unlock()
		if err := os.Remove(e.abs("z/old.jpg")); err != nil {
			t.Fatal(err)
		}
		e.writeFile(t, "z/new.jpg", 10)
		if err := os.Chtimes(e.abs("z/new.jpg"), info.ModTime(), info.ModTime()); err != nil {
			t.Fatal(err)
		}
		_, err := e.q.UpsertAsset(t.Context(), db.UpsertAssetParams{AssetID: uploadID, LibraryID: e.lib.ID, RelPath: "z/new.jpg", FileName: "new.jpg", MediaType: old.MediaType, SizeBytes: old.SizeBytes, Mtime: old.Mtime, CreatedAt: old.CreatedAt, UpdatedAt: old.UpdatedAt})
		return nil, err
	}
	if _, err := e.s.Scan(t.Context(), e.lib); err != nil {
		t.Fatal(err)
	}
	asset := e.assetByPath(t, "z/new.jpg")
	if asset.AssetID != uploadID {
		t.Fatalf("API-created identity consumed: %s", asset.AssetID)
	}
}
