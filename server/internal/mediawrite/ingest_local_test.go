package mediawrite

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"image"
	"image/png"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// ingestLocalFixture 建最小写入服务：库根 media、独立读根 sources；
// rename 可注入（模拟跨分区 EXDEV）。
func ingestLocalFixture(t *testing.T, rename func(string, string) error) (*Service, string, string, string) {
	t.Helper()
	root := t.TempDir()
	media := filepath.Join(root, "media")
	sources := filepath.Join(root, "sources")
	for _, d := range []string{media, sources} {
		if err := os.Mkdir(d, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	conn, err := store.Open(filepath.Join(root, "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := store.Migrate(conn); err != nil {
		t.Fatal(err)
	}
	q := db.New(conn)
	id := uuid.NewString()
	if _, err := q.CreateLibrary(context.Background(), db.CreateLibraryParams{
		ID: id, Name: "synthetic", RootPath: media, Kind: "normal",
		CreatedAt: store.FormatTimestamp(time.Now()),
	}); err != nil {
		t.Fatal(err)
	}
	bus := events.NewBus(nil, 0)
	t.Cleanup(bus.Close)
	svc := New(Options{
		Conn: conn, Queries: q, Bus: bus,
		Config: &config.Config{
			DataDir:     filepath.Join(root, "data"),
			Upload:      config.UploadConfig{MaxBytes: 128 << 20},
			IngestLocal: config.IngestLocalConfig{ReadRoots: []string{sources}},
		},
		IngestRename: rename,
	})
	t.Cleanup(func() { _ = svc.uploads.Close() })
	return svc, id, media, sources
}

// writePNG 写一张真实 PNG（内容嗅探校验要求扩展名与魔数一致）。
func writePNG(t *testing.T, path string, side int) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, side, side))
	var buf []byte
	if err := func() error {
		f, err := os.Create(path)
		if err != nil {
			return err
		}
		if err := png.Encode(f, img); err != nil {
			_ = f.Close()
			return err
		}
		return f.Close()
	}(); err != nil {
		t.Fatal(err)
	}
	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	buf = body
	return buf
}

// TestIngestLocalMovesWithinVolume 覆盖同分区移动：源消失、目标落位、索引可见、
// rename 快路径不计算摘要。
func TestIngestLocalMovesWithinVolume(t *testing.T) {
	svc, libID, media, sources := ingestLocalFixture(t, nil)
	src := filepath.Join(sources, "a.png")
	body := writePNG(t, src, 1)

	res, err := svc.IngestLocal(context.Background(), IngestLocalRequest{
		LibraryID: libID, SourcePath: src, FileName: "A 1.png", Directory: "合集 (1)", Move: true,
	})
	if err != nil {
		t.Fatalf("IngestLocal: %v", err)
	}
	if res.Status != IngestLocalStatusIngested || res.SourceKept || res.RelPath != "合集 (1)/A 1.png" {
		t.Fatalf("结果不符: %+v", res)
	}
	if res.SHA256 != "" {
		t.Fatalf("同卷 rename 不应计算摘要: %q", res.SHA256)
	}
	if _, err := os.Stat(src); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("move=true 源应已移走: %v", err)
	}
	got, err := os.ReadFile(filepath.Join(media, "合集 (1)", "A 1.png"))
	if err != nil || string(got) != string(body) {
		t.Fatalf("库内内容不符: %v", err)
	}
	asset, err := svc.q.GetAssetByPath(context.Background(), db.GetAssetByPathParams{LibraryID: libID, RelPath: "合集 (1)/A 1.png"})
	if err != nil || asset.AssetID != res.AssetID {
		t.Fatalf("索引行缺失或 ID 不符: %+v err=%v", asset, err)
	}
}

// TestIngestLocalAlreadyPresent 覆盖幂等：同名同内容第二次调用返回
// already_present，源不动，返回既有资产 ID 与摘要。
func TestIngestLocalAlreadyPresent(t *testing.T) {
	svc, libID, _, sources := ingestLocalFixture(t, nil)
	first := filepath.Join(sources, "a.png")
	body := writePNG(t, first, 1)
	if _, err := svc.IngestLocal(context.Background(), IngestLocalRequest{
		LibraryID: libID, SourcePath: first, FileName: "same.png", Move: true,
	}); err != nil {
		t.Fatal(err)
	}
	// 另一份同内容文件（模拟重复下载的副本）
	second := filepath.Join(sources, "dup.png")
	if err := os.WriteFile(second, body, 0o644); err != nil {
		t.Fatal(err)
	}
	res, err := svc.IngestLocal(context.Background(), IngestLocalRequest{
		LibraryID: libID, SourcePath: second, FileName: "same.png", Move: true,
	})
	if err != nil {
		t.Fatalf("幂等调用应成功: %v", err)
	}
	if res.Status != IngestLocalStatusPresent || !res.SourceKept {
		t.Fatalf("应为 already_present 且源保留: %+v", res)
	}
	sum := sha256.Sum256(body)
	if res.SHA256 != hex.EncodeToString(sum[:]) {
		t.Fatalf("摘要不符: %q", res.SHA256)
	}
	if _, err := os.Stat(second); err != nil {
		t.Fatalf("already_present 不得动源: %v", err)
	}
}

// TestIngestLocalConflictOnDifferentContent 覆盖撞名不同内容：409 语义，不覆盖。
func TestIngestLocalConflictOnDifferentContent(t *testing.T) {
	svc, libID, media, sources := ingestLocalFixture(t, nil)
	src := filepath.Join(sources, "a.png")
	writePNG(t, src, 1)
	if _, err := svc.IngestLocal(context.Background(), IngestLocalRequest{
		LibraryID: libID, SourcePath: src, FileName: "x.png", Move: true,
	}); err != nil {
		t.Fatal(err)
	}
	other := filepath.Join(sources, "b.png")
	writePNG(t, other, 4) // 不同尺寸 → 不同内容
	_, err := svc.IngestLocal(context.Background(), IngestLocalRequest{
		LibraryID: libID, SourcePath: other, FileName: "x.png", Move: true,
	})
	if !errors.Is(err, ErrIngestTargetExists) {
		t.Fatalf("应拒绝覆盖: %v", err)
	}
	// 既有文件仍在、源仍在
	if _, err := os.Stat(filepath.Join(media, "x.png")); err != nil {
		t.Fatalf("既有目标不应被删: %v", err)
	}
	if _, err := os.Stat(other); err != nil {
		t.Fatalf("冲突时源不应被动: %v", err)
	}
}

// TestIngestLocalCopyKeepsSource 覆盖 move=false：复制入库、源保留、摘要返回。
func TestIngestLocalCopyKeepsSource(t *testing.T) {
	svc, libID, media, sources := ingestLocalFixture(t, nil)
	src := filepath.Join(sources, "a.png")
	body := writePNG(t, src, 1)
	res, err := svc.IngestLocal(context.Background(), IngestLocalRequest{
		LibraryID: libID, SourcePath: src, FileName: "copy.png", Move: false,
	})
	if err != nil {
		t.Fatal(err)
	}
	if res.Status != IngestLocalStatusIngested || !res.SourceKept || res.SHA256 == "" {
		t.Fatalf("copy 结果不符: %+v", res)
	}
	if _, err := os.Stat(src); err != nil {
		t.Fatalf("move=false 源必须保留: %v", err)
	}
	got, err := os.ReadFile(filepath.Join(media, "copy.png"))
	if err != nil || string(got) != string(body) {
		t.Fatalf("复制内容不符: %v", err)
	}
}

// TestIngestLocalCrossDeviceCopyVerifiesBeforeDeletingSource 覆盖跨分区：
// rename 返回 EXDEV → 复制 + 摘要复核 + 建索引成功后才删源。
func TestIngestLocalCrossDeviceCopyVerifiesBeforeDeletingSource(t *testing.T) {
	exdev := errors.New("invalid cross-device link")
	svc, libID, media, sources := ingestLocalFixture(t, func(_, _ string) error { return exdev })
	src := filepath.Join(sources, "a.png")
	body := writePNG(t, src, 2)
	res, err := svc.IngestLocal(context.Background(), IngestLocalRequest{
		LibraryID: libID, SourcePath: src, FileName: "cross.png", Move: true,
	})
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(body)
	if res.SHA256 != hex.EncodeToString(sum[:]) || res.SourceKept {
		t.Fatalf("跨分区结果不符: %+v", res)
	}
	if _, err := os.Stat(src); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("校验通过且入库后应删源: %v", err)
	}
	if _, err := os.Stat(filepath.Join(media, "cross.png")); err != nil {
		t.Fatalf("目标未落位: %v", err)
	}
}

// TestIngestLocalRejectsOutsideRootsAndDisabled 覆盖源越界与未配置读根。
func TestIngestLocalRejectsOutsideRootsAndDisabled(t *testing.T) {
	svc, libID, _, _ := ingestLocalFixture(t, nil)
	outside := filepath.Join(t.TempDir(), "x.png")
	writePNG(t, outside, 1)
	if _, err := svc.IngestLocal(context.Background(), IngestLocalRequest{
		LibraryID: libID, SourcePath: outside, FileName: "x.png", Move: true,
	}); !errors.Is(err, ErrIngestSourceOutsideRoots) {
		t.Fatalf("越界源应拒绝: %v", err)
	}
	svc.cfg.IngestLocal.ReadRoots = nil
	if _, err := svc.IngestLocal(context.Background(), IngestLocalRequest{
		LibraryID: libID, SourcePath: outside, FileName: "x.png",
	}); !errors.Is(err, ErrIngestLocalDisabled) {
		t.Fatalf("未配置读根应拒绝: %v", err)
	}
	if _, err := os.Stat(outside); err != nil {
		t.Fatalf("拒绝路径不得动源: %v", err)
	}
}

// TestIngestLocalRejectsFakeExtension 覆盖内容嗅探：exe 改名 png 拒绝。
func TestIngestLocalRejectsFakeExtension(t *testing.T) {
	svc, libID, _, sources := ingestLocalFixture(t, nil)
	fake := filepath.Join(sources, "fake.png")
	if err := os.WriteFile(fake, []byte("MZ\x90\x00not a png"), 0o644); err != nil {
		t.Fatal(err)
	}
	_, err := svc.IngestLocal(context.Background(), IngestLocalRequest{
		LibraryID: libID, SourcePath: fake, FileName: "fake.png", Move: true,
	})
	if !errors.Is(err, filing.ErrUploadMimeMismatch) {
		t.Fatalf("伪造扩展名应拒绝: %v", err)
	}
}
