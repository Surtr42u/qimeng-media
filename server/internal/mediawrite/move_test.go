package mediawrite

import (
	"bytes"
	"context"
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/google/uuid"
	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/scanner"
	"qimeng-media/server/internal/store/db"
)

func moveFixture(t *testing.T) (*Service, db.Asset, string, []byte, *events.Subscription) {
	t.Helper()
	s, lib, media, body := uploadFixture(t)
	asset, err := s.Direct(context.Background(), DirectUpload{LibraryID: lib, Filename: "original.png", Body: bytes.NewReader(body), ContentLength: int64(len(body))})
	if err != nil {
		t.Fatal(err)
	}
	sub, err := s.bus.Subscribe(events.TopicLibraryChanged)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(sub.Close)
	return s, asset, media, body, sub
}

func assertMoveFailure(t *testing.T, err error, kind FailureKind) {
	t.Helper()
	var failure *Failure
	if !errors.As(err, &failure) || failure.Kind != kind {
		t.Fatalf("失败分类: %v want=%v", err, kind)
	}
}

func assertNoMoveEvent(t *testing.T, sub *events.Subscription) {
	t.Helper()
	select {
	case e := <-sub.C:
		t.Fatalf("无成功提交不应广播: %+v", e)
	default:
	}
}

func requireMoveBytes(t *testing.T, name string, want []byte) {
	t.Helper()
	if actual, err := os.ReadFile(name); err != nil || !bytes.Equal(actual, want) {
		t.Fatalf("原件不符: %s %v", filepath.Base(name), err)
	}
}

func TestMoveBusinessPreservesIdentityAssociationsAndNoop(t *testing.T) {
	s, asset, media, body, sub := moveFixture(t)
	ctx := context.Background()
	for _, statement := range []string{
		`INSERT INTO favorites(asset_id,created_at) VALUES(?,'2026-10-21T00:00:00.000Z')`,
		`INSERT INTO likes(asset_id,day,created_at) VALUES(?,'2026-10-21','2026-10-21T00:00:00.000Z')`,
		`INSERT INTO view_events(asset_id,kind,session_id,started_at,client_event_id) VALUES(?,'open','synthetic','2026-10-21T00:00:00.000Z','synthetic-move-event')`,
	} {
		if _, err := s.conn.Exec(statement, asset.AssetID); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := s.conn.Exec(`INSERT INTO tags(id,name,created_at) VALUES('synthetic-tag','synthetic','2026-10-21T00:00:00.000Z')`); err != nil {
		t.Fatal(err)
	}
	if _, err := s.conn.Exec(`INSERT INTO asset_tags(asset_id,tag_id) VALUES(?,'synthetic-tag')`, asset.AssetID); err != nil {
		t.Fatal(err)
	}
	if _, err := s.conn.Exec(`UPDATE assets SET last_position_seconds=12 WHERE asset_id=?`, asset.AssetID); err != nil {
		t.Fatal(err)
	}
	var enrichCount int
	s.enrich = func(ctx context.Context, lib, id string) error {
		enrichCount++
		row, err := s.q.GetAsset(ctx, id)
		if err != nil || row.RelPath != "收藏/新目录/renamed.png" || lib != asset.LibraryID {
			t.Errorf("富化必须在新路径提交后: %+v %v", row, err)
		}
		return errors.New("synthetic enrichment failure") // 富化失败不回滚已成功移动。
	}
	name := "renamed.png"
	if err := s.MoveAsset(ctx, asset.AssetID, MoveRequest{TargetDirectory: "收藏/新目录", NewName: &name}); err != nil {
		t.Fatal(err)
	}
	row, err := s.q.GetAsset(ctx, asset.AssetID)
	if err != nil || row.RelPath != "收藏/新目录/renamed.png" || row.CreatedAt != asset.CreatedAt || row.Mtime != asset.Mtime || row.SizeBytes != asset.SizeBytes || row.LastPositionSeconds.Float64 != 12 {
		t.Fatalf("移动破坏身份/元数据: %+v %v", row, err)
	}
	for _, table := range []string{"favorites", "likes", "asset_tags", "view_events"} {
		var count int
		// table 只来自本测试固定列表。
		if err := s.conn.QueryRow("SELECT COUNT(*) FROM "+table+" WHERE asset_id=?", asset.AssetID).Scan(&count); err != nil || count != 1 {
			t.Fatalf("%s 关联丢失: %d %v", table, count, err)
		}
	}
	requireMoveBytes(t, filepath.Join(media, filepath.FromSlash(row.RelPath)), body)
	if _, err := os.Stat(filepath.Join(media, asset.RelPath)); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("旧路径未移走", err)
	}
	select {
	case <-sub.C:
	default:
		t.Fatal("成功移动必须发库变更")
	}
	before := row.UpdatedAt
	s.now = func() time.Time { return time.Now().Add(time.Hour) }
	if err := s.MoveAsset(ctx, asset.AssetID, MoveRequest{TargetDirectory: "收藏/新目录"}); err != nil {
		t.Fatal(err)
	}
	row, err = s.q.GetAsset(ctx, asset.AssetID)
	if err != nil || row.UpdatedAt != before || enrichCount != 1 {
		t.Fatal("幂等请求不能更新时刻/再富化", err)
	}
	assertNoMoveEvent(t, sub)
}

func TestMoveDatabaseFailureRollsBackAndRetries(t *testing.T) {
	s, asset, media, body, sub := moveFixture(t)
	if _, err := s.conn.Exec(`CREATE TRIGGER synthetic_move_fail BEFORE UPDATE OF rel_path ON assets BEGIN SELECT RAISE(ABORT,'synthetic update failure'); END`); err != nil {
		t.Fatal(err)
	}
	err := s.MoveAsset(context.Background(), asset.AssetID, MoveRequest{TargetDirectory: "next"})
	assertMoveFailure(t, err, InternalFailure)
	requireMoveBytes(t, filepath.Join(media, "original.png"), body)
	if _, err := os.Stat(filepath.Join(media, "next", "original.png")); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("成功回滚不应留重复件", err)
	}
	row, err := s.q.GetAsset(context.Background(), asset.AssetID)
	if err != nil || row.RelPath != asset.RelPath {
		t.Fatal("失败不能更新库路径", err)
	}
	assertNoMoveEvent(t, sub)
	if _, err := s.conn.Exec(`DROP TRIGGER synthetic_move_fail`); err != nil {
		t.Fatal(err)
	}
	if err := s.MoveAsset(context.Background(), asset.AssetID, MoveRequest{TargetDirectory: "next"}); err != nil {
		t.Fatal(err)
	}
	requireMoveBytes(t, filepath.Join(media, "next", "original.png"), body)
}

func TestMoveRollbackNeverOverwritesNewSource(t *testing.T) {
	s, asset, media, body, sub := moveFixture(t)
	if _, err := s.conn.Exec(`CREATE TRIGGER synthetic_move_fail BEFORE UPDATE OF rel_path ON assets BEGIN SELECT RAISE(ABORT,'synthetic update failure'); END`); err != nil {
		t.Fatal(err)
	}
	external := []byte("new external file at old path")
	s.renameFile = func(root *os.Root, source, target string) error {
		if target == filepath.FromSlash(asset.RelPath) {
			if err := root.WriteFile(target, external, 0o600); err != nil {
				return err
			}
		}
		return filing.RenameInRoot(root, source, target)
	}
	err := s.MoveAsset(context.Background(), asset.AssetID, MoveRequest{TargetDirectory: "next"})
	assertMoveFailure(t, err, InternalFailure)
	if !errors.Is(err, filing.ErrMoveTargetExists) {
		t.Fatalf("回滚占用应保留诊断原因: %v", err)
	}
	requireMoveBytes(t, filepath.Join(media, "original.png"), external)
	requireMoveBytes(t, filepath.Join(media, "next", "original.png"), body)
	assertNoMoveEvent(t, sub)
}

func TestMoveTargetOccupiedAfterCheckPreservesBothFiles(t *testing.T) {
	s, asset, media, body, sub := moveFixture(t)
	external := []byte("external target")
	s.renameFile = func(root *os.Root, source, target string) error {
		if err := root.WriteFile(target, external, 0o600); err != nil {
			return err
		}
		return filing.RenameInRoot(root, source, target)
	}
	err := s.MoveAsset(context.Background(), asset.AssetID, MoveRequest{TargetDirectory: "next"})
	assertMoveFailure(t, err, MoveFileConflict)
	requireMoveBytes(t, filepath.Join(media, "original.png"), body)
	requireMoveBytes(t, filepath.Join(media, "next", "original.png"), external)
	assertNoMoveEvent(t, sub)
}

func TestMoveRejectsDatabaseOccupancyAndStaleSnapshot(t *testing.T) {
	s, asset, media, body, sub := moveFixture(t)
	ctx := context.Background()
	_, err := s.q.UpsertAsset(ctx, db.UpsertAssetParams{AssetID: uuid.NewString(), LibraryID: asset.LibraryID, RelPath: "occupied.png", FileName: "occupied.png", MediaType: asset.MediaType, SizeBytes: asset.SizeBytes, Mtime: asset.Mtime, CreatedAt: asset.CreatedAt, UpdatedAt: asset.UpdatedAt})
	if err != nil {
		t.Fatal(err)
	}
	name := "occupied.png"
	assertMoveFailure(t, s.MoveAsset(ctx, asset.AssetID, MoveRequest{NewName: &name}), MoveRecordConflict)
	requireMoveBytes(t, filepath.Join(media, "original.png"), body)
	assertNoMoveEvent(t, sub)
	old, err := s.q.GetAssetWithLibrary(ctx, asset.AssetID)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.MoveAsset(ctx, asset.AssetID, MoveRequest{TargetDirectory: "moved"}); err != nil {
		t.Fatal(err)
	}
	// 模拟另一请求持锁前已经过时的路径快照，不依赖 sleep 或随机并发时序。
	err = filing.WithLibraryGate(asset.LibraryID, func() error { return s.commitMove(ctx, old, "stale.png", "stale.png") })
	assertMoveFailure(t, err, MoveRecordConflict)
	requireMoveBytes(t, filepath.Join(media, "moved", "original.png"), body)
}

func TestMoveInvalidInputsKeepOriginal(t *testing.T) {
	s, asset, media, body, sub := moveFixture(t)
	invalid := "NUL.png"
	assertMoveFailure(t, s.MoveAsset(context.Background(), asset.AssetID, MoveRequest{TargetDirectory: "../../escape"}), InvalidDirectory)
	assertMoveFailure(t, s.MoveAsset(context.Background(), asset.AssetID, MoveRequest{NewName: &invalid}), InvalidFilename)
	assertMoveFailure(t, s.MoveAsset(context.Background(), uuid.NewString(), MoveRequest{}), AssetMissing)
	requireMoveBytes(t, filepath.Join(media, "original.png"), body)
	assertNoMoveEvent(t, sub)
}

func TestMoveBusinessRecomputesCosAuthorAndWork(t *testing.T) {
	s, asset, media, body, _ := moveFixture(t)
	ctx := context.Background()
	if _, err := s.conn.Exec(`UPDATE libraries SET kind='cos' WHERE id=?`, asset.LibraryID); err != nil {
		t.Fatal(err)
	}
	scan := scanner.New(s.q, s.bus, nil, s.cfg.DataDir, nil)
	scan.SetConn(s.conn)
	s.enrich = scan.EnrichAsset
	for _, author := range []string{"作者甲", "作者乙"} {
		if err := s.MoveAsset(ctx, asset.AssetID, MoveRequest{TargetDirectory: author + "/作品"}); err != nil {
			t.Fatal(err)
		}
		row, err := s.q.GetAsset(ctx, asset.AssetID)
		if err != nil || !row.CosWork.Valid || row.CosWork.String != "作品" {
			t.Fatalf("COS 作品未重算: %+v %v", row, err)
		}
		var displayName string
		if err := s.conn.QueryRow(`SELECT au.display_name FROM authors au JOIN asset_authors aa ON aa.author_id=au.id WHERE aa.asset_id=?`, asset.AssetID).Scan(&displayName); err != nil || displayName != author {
			t.Fatalf("COS 作者未随移动更新: %s %v", displayName, err)
		}
	}
	if err := s.MoveAsset(ctx, asset.AssetID, MoveRequest{}); err != nil {
		t.Fatal(err)
	}
	row, err := s.q.GetAsset(ctx, asset.AssetID)
	if err != nil || row.CosWork.Valid || row.CreatedAt != asset.CreatedAt {
		t.Fatal("回库根未清作品或破坏身份", err)
	}
	var count int
	if err := s.conn.QueryRow(`SELECT COUNT(*) FROM asset_authors WHERE asset_id=?`, asset.AssetID).Scan(&count); err != nil || count != 0 {
		t.Fatal("回库根应解除 COS 作者映射", err)
	}
	requireMoveBytes(t, filepath.Join(media, "original.png"), body)
}
