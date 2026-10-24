package syncchannel

import (
	"bytes"
	"context"
	"database/sql"
	"errors"
	"image"
	"image/png"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authorimport"
	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/localsync"
	"qimeng-media/server/internal/mediawrite"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

func syncFixture(t *testing.T) (*Service, *sql.DB, db.Library) {
	t.Helper()
	base := t.TempDir()
	cfg := &config.Config{DataDir: filepath.Join(base, "data"), LocalSync: config.LocalSyncConfig{Root: filepath.Join(base, "inbox")}}
	media := filepath.Join(base, "media")
	for _, dir := range []string{cfg.DataDir, cfg.LocalSync.Root, media} {
		if err := os.Mkdir(dir, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	conn, err := store.Open(filepath.Join(base, "synthetic.db"))
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
	lib, err := q.CreateLibrary(context.Background(), db.CreateLibraryParams{ID: uuid.NewString(), Name: "synthetic", RootPath: media, Kind: "normal", CreatedAt: store.FormatTimestamp(time.Now())})
	if err != nil {
		t.Fatal(err)
	}
	bus := events.NewBus(nil, 0)
	t.Cleanup(bus.Close)
	writes := mediawrite.New(mediawrite.Options{Conn: conn, Queries: q, Bus: bus, Config: cfg})
	t.Cleanup(func() {
		if err := writes.UploadSessions().Close(); err != nil {
			t.Error(err)
		}
	})
	return New(Options{Config: cfg, Queries: q, Writes: writes, TXT: authorimport.New(conn, q, nil, nil)}), conn, lib
}

func writeOld(t *testing.T, name string, body []byte) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(name), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(name, body, 0o600); err != nil {
		t.Fatal(err)
	}
	old := time.Now().Add(-time.Hour)
	if err := os.Chtimes(name, old, old); err != nil {
		t.Fatal(err)
	}
}

func TestSyncMediaPrecheckAndDBFailureKeepOnlyOriginal(t *testing.T) {
	s, conn, lib := syncFixture(t)
	ctx := context.Background()
	var body bytes.Buffer
	if err := png.Encode(&body, image.NewRGBA(image.Rect(0, 0, 2, 2))); err != nil {
		t.Fatal(err)
	}
	src := filepath.Join(s.cfg.LocalSync.Root, lib.Name, "original.png")
	writeOld(t, src, body.Bytes())
	entry := localsync.Entry{RelPath: lib.Name + "/original.png", Size: int64(body.Len() + 123)}
	s.processLocalSyncMedia(ctx, entry, lib, lib.Name, s.writes.UploadPolicy(ctx))
	if _, err := os.Stat(src); err != nil {
		t.Fatal("预复核不符必须保源", err)
	}
	if st := s.Snapshot(); st.Failed != 1 || !strings.Contains(st.Items[0].Error, "文件在处理间隙发生变化") {
		t.Fatalf("错误状态: %+v", st)
	}
	if _, err := conn.Exec(`CREATE TRIGGER fail_sync BEFORE INSERT ON assets BEGIN SELECT RAISE(ABORT,'synthetic failure'); END`); err != nil {
		t.Fatal(err)
	}
	s.RunOnce(ctx)
	s.RunOnce(ctx)
	got, err := os.ReadFile(filepath.Join(lib.RootPath, "original.png"))
	if err != nil || !bytes.Equal(got, body.Bytes()) {
		t.Fatal("DB 失败仍保留唯一完整库位文件", err)
	}
	if _, err := os.Stat(src); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("移动成功后不重复搬运: %v", err)
	}
	if st := s.Snapshot(); st.SyncedTotal != 1 || len(st.Recent) != 1 || !strings.Contains(st.Recent[0].Error, "扫描器补齐") {
		t.Fatalf("失败补偿状态: %+v", st)
	}
	s.RunOnce(ctx)
	files, err := os.ReadDir(lib.RootPath)
	if err != nil || len(files) != 1 {
		t.Fatalf("后续扫描不能制造重复原件: %v %v", files, err)
	}
}

func TestSyncTXTArchiveRetryAndActualReadLimit(t *testing.T) {
	s, _, _ := syncFixture(t)
	ctx := context.Background()
	src := filepath.Join(s.cfg.LocalSync.Root, "authors.txt")
	content := []byte("1  作者甲\n作品\noriginal.png\n")
	writeOld(t, src, content)
	s.moveFile = func(string, string) error { return errors.New("synthetic archive failure") }
	s.RunOnce(ctx)
	s.RunOnce(ctx)
	if _, err := os.Stat(src); err != nil {
		t.Fatal("归档失败必须保源", err)
	}
	sources, err := authorattach.LoadSources(ctx, s.q)
	if err != nil || len(sources) != 1 || sources[0].Content != string(content) {
		t.Fatalf("已提交片段不得回滚: %+v %v", sources, err)
	}
	s.moveFile = filing.MoveFile
	s.RunOnce(ctx)
	got, err := os.ReadFile(filepath.Join(s.cfg.LocalSync.Root, localsync.ReservedInternalDirName, "authors.txt"))
	if err != nil || !bytes.Equal(got, content) {
		t.Fatal("重试归档未保持原字节", err)
	}
	sources, err = authorattach.LoadSources(ctx, s.q)
	if err != nil || len(sources) != 1 || s.Snapshot().SyncedTotal != 1 {
		t.Fatal("重试不能重复片段/成功计数", err)
	}
	// 扫描时大小伪装为 1，但实际文件已增长；不得整读或导入超限内容。
	large := filepath.Join(s.cfg.LocalSync.Root, "growing.txt")
	writeOld(t, large, bytes.Repeat([]byte("a"), int(localSyncMaxTxtBytes+1)))
	s.processLocalSyncTxt(ctx, localsync.Entry{RelPath: "growing.txt", Size: 1})
	if _, err := os.Stat(large); err != nil {
		t.Fatal("超限必须保源", err)
	}
	if st := s.Snapshot(); st.Failed != 1 || !strings.Contains(st.Items[0].Error, "超过") {
		t.Fatalf("实际读取上限未生效: %+v", st)
	}
}

func TestReconcilePlacedSizeNeverDeletesOnlyCopy(t *testing.T) {
	s, _, _ := syncFixture(t)
	for _, test := range []struct {
		name        string
		expect      int64
		blockSource bool
		want        lsPlaceResult
	}{
		{"same", 10, false, lsPlaceProceed}, {"back", 999, false, lsPlaceMovedBack}, {"blocked", 999, true, lsPlaceKeptInLibrary},
	} {
		t.Run(test.name, func(t *testing.T) {
			root := t.TempDir()
			src := filepath.Join(root, "source")
			target := filepath.Join(root, "target")
			writeOld(t, target, []byte("0123456789"))
			if test.blockSource {
				if err := os.Mkdir(src, 0o700); err != nil {
					t.Fatal(err)
				}
			}
			got, reason := s.lsReconcilePlacedSize(src, target, test.expect)
			if got != test.want {
				t.Fatalf("处置=%v want=%v reason=%v", got, test.want, reason)
			}
			kept := target
			if got == lsPlaceMovedBack {
				kept = src
			}
			if data, err := os.ReadFile(kept); err != nil || string(data) != "0123456789" {
				t.Fatal("唯一副本损坏/丢失", err)
			}
		})
	}
}

func TestSnapshotCountsAllAndDoesNotShareState(t *testing.T) {
	s, _, _ := syncFixture(t)
	for i := 0; i < localSyncStatusItemCap+1; i++ {
		s.lsMarkFailed(uuid.NewString(), "media", "", "failure", 1)
	}
	st := s.Snapshot()
	if len(st.Items) != localSyncStatusItemCap || st.Failed != localSyncStatusItemCap+1 {
		t.Fatalf("截断影响全量计数: %+v", st)
	}
	st.Items[0].Error = "changed outside"
	if s.Snapshot().Items[0].Error != "failure" {
		t.Fatal("快照暴露可变内部状态")
	}
}
