package mediawrite

import (
	"bytes"
	"context"
	"errors"
	"image"
	"image/png"
	"math"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

func uploadFixture(t *testing.T) (*Service, string, string, []byte) {
	t.Helper()
	root := t.TempDir()
	media := filepath.Join(root, "media")
	if err := os.Mkdir(media, 0o700); err != nil {
		t.Fatal(err)
	}
	conn, err := store.Open(filepath.Join(root, "test.db"))
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
	id := uuid.NewString()
	_, err = q.CreateLibrary(context.Background(), db.CreateLibraryParams{ID: id, Name: "synthetic", RootPath: media, Kind: "normal", CreatedAt: store.FormatTimestamp(time.Now())})
	if err != nil {
		t.Fatal(err)
	}
	bus := events.NewBus(nil, 0)
	t.Cleanup(bus.Close)
	svc := New(Options{Conn: conn, Queries: q, Bus: bus, Config: &config.Config{DataDir: filepath.Join(root, "data"), Upload: config.UploadConfig{MaxBytes: 128 << 20}}})
	t.Cleanup(func() {
		if err := svc.uploads.Close(); err != nil {
			t.Error(err)
		}
	})
	var body bytes.Buffer
	if err := png.Encode(&body, image.NewRGBA(image.Rect(0, 0, 1, 1))); err != nil {
		t.Fatal(err)
	}
	return svc, id, media, body.Bytes()
}

func TestDirectUploadBusinessWithoutHTTP(t *testing.T) {
	svc, library, media, body := uploadFixture(t)
	ctx := context.Background()
	for n := 0; n < 2; n++ {
		asset, err := svc.Direct(ctx, DirectUpload{LibraryID: library, Directory: "nested", Filename: "original.png", ContentLength: int64(len(body)), Body: bytes.NewReader(body)})
		if err != nil {
			t.Fatal(err)
		}
		content, err := os.ReadFile(filepath.Join(media, filepath.FromSlash(asset.RelPath)))
		if err != nil || !bytes.Equal(content, body) {
			t.Fatal("原件字节未保持", err)
		}
		if n == 1 && asset.FileName != "original (2).png" {
			t.Fatalf("冲突解析改变: %q", asset.FileName)
		}
	}
	_, err := svc.Direct(ctx, DirectUpload{LibraryID: library, Directory: "../escape", Filename: "original.png", Body: bytes.NewReader(body), ContentLength: -1})
	var failure *Failure
	if !errors.As(err, &failure) || failure.Kind != InvalidDirectory {
		t.Fatalf("穿越未拒绝: %v", err)
	}
	svc.cfg.Upload.MaxBytes = int64(len(body))
	_, err = svc.Direct(ctx, DirectUpload{LibraryID: library, Filename: "oversize.png", ContentLength: -1, Body: bytes.NewReader(append(append([]byte{}, body...), 1))})
	if err != nil {
		t.Fatalf("旧上限应被忽略: %v", err)
	}
	if _, err := os.Stat(filepath.Join(media, "oversize.png")); err != nil {
		t.Fatal(err)
	}

}

func TestCompleteRetriesPublishedPathWithoutDuplicate(t *testing.T) {
	svc, library, media, body := uploadFixture(t)
	ctx := context.Background()
	snapshot, err := svc.CreateUploadSession(ctx, SessionUpload{LibraryID: library, Filename: "retry.png", Size: int64(len(body))})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := svc.uploads.Append(snapshot.ID, 0, bytes.NewReader(body)); err != nil {
		t.Fatal(err)
	}
	sub, err := svc.bus.Subscribe(events.TopicUploadDone, events.TopicLibraryChanged)
	if err != nil {
		t.Fatal(err)
	}
	defer sub.Close()
	if _, err := svc.conn.Exec("CREATE TRIGGER synthetic_fail BEFORE INSERT ON assets BEGIN SELECT RAISE(ABORT,'synthetic failure'); END"); err != nil {
		t.Fatal(err)
	}
	if _, err := svc.CompleteUploadSession(ctx, snapshot.ID); err == nil {
		t.Fatal("入库故障未传导")
	}
	select {
	case <-sub.C:
		t.Fatal("失败不能发成功事件")
	default:
	}
	sealed, err := svc.uploads.Seal(snapshot.ID)
	if err != nil || sealed.PlacedRel != "retry.png" {
		t.Fatalf("落点未记住: %+v, %v", sealed, err)
	}
	if source, err := os.ReadFile(sealed.Path); err != nil || !bytes.Equal(source, body) {
		t.Fatal("失败不能丢弃分片源", err)
	}
	if _, err := svc.conn.Exec("DROP TRIGGER synthetic_fail"); err != nil {
		t.Fatal(err)
	}
	asset, err := svc.CompleteUploadSession(ctx, snapshot.ID)
	if err != nil || asset.RelPath != "retry.png" {
		t.Fatalf("重试落点改变: %+v, %v", asset, err)
	}
	entries, err := os.ReadDir(media)
	if err != nil || len(entries) != 1 {
		t.Fatalf("重试产生重复/临时文件: %v, %v", entries, err)
	}
	var count int
	if err := svc.conn.QueryRow("SELECT COUNT(*) FROM assets").Scan(&count); err != nil || count != 1 {
		t.Fatal("重试未保持唯一资产", err)
	}
	if _, err := os.Stat(sealed.Path); !os.IsNotExist(err) {
		t.Fatal("成功后分片源应清理")
	}
	if _, err := svc.q.GetAsset(ctx, asset.AssetID); err != nil {
		t.Fatal("已发布资产不可读取", err)
	}
}

func TestUploadPolicyCorruptionAndCap(t *testing.T) {
	svc, _, _, _ := uploadFixture(t)
	ctx := context.Background()
	for _, input := range []struct {
		json     string
		cap      int64
		disabled bool
	}{
		{`{"upload":{"maxBytesMb":64,"autoAccept":true}}`, math.MaxInt64, false},
		{`{"upload":{"maxBytesMb":8192,"autoAccept":false}}`, math.MaxInt64, true},
		{`{"scan":{"workers":"corrupt"},"upload":{"autoAccept":false}}`, math.MaxInt64, false},
		{`corrupt`, math.MaxInt64, false},
	} {
		if err := svc.q.UpsertSetting(ctx, db.UpsertSettingParams{Key: authoring.SettingKeyClientConfig, Value: input.json, UpdatedAt: store.FormatTimestamp(time.Now())}); err != nil {
			t.Fatal(err)
		}
		actual := svc.UploadPolicy(ctx)
		if actual.MaxBytes != input.cap || actual.Disabled != input.disabled {
			t.Fatalf("坏配置回落/部署上限改变: %+v", actual)
		}
	}
}
