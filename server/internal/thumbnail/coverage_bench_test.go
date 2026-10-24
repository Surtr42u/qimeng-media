package thumbnail

import (
	"fmt"
	"os"
	"path/filepath"
	"testing"

	"qimeng-media/server/internal/store"
)

// 只测已缓存覆盖检查，不包含 SQL、路径安全检查、原件扫描或真实媒体解码。
func BenchmarkThumbnailCoverageChecks(b *testing.B) {
	g := &Generator{dataDir: b.TempDir(), longSide: int(SizeGrid)}
	src := filepath.Join(b.TempDir(), "source.bin")
	if err := os.WriteFile(src, []byte("synthetic"), 0o600); err != nil {
		b.Fatal(err)
	}
	fi, err := os.Stat(src)
	if err != nil {
		b.Fatal(err)
	}
	mtime := store.FormatTimestamp(fi.ModTime())
	const count = 1000
	ids := make([]string, count)
	for i := range ids {
		ids[i] = fmt.Sprintf("synthetic-%d", i)
		dst := g.thumbDst(ids[i], SizeGrid, g.RepresentationVersion(fi.Size(), mtime))
		if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
			b.Fatal(err)
		}
		if err := os.WriteFile(dst, []byte("cache"), 0o600); err != nil {
			b.Fatal(err)
		}
	}
	b.Run("live-source", func(b *testing.B) {
		b.ReportAllocs()
		for i := 0; i < b.N; i++ {
			for _, id := range ids {
				if !g.HasThumbnail(id, SizeGrid, src) {
					b.Fatal("missing")
				}
			}
		}
	})
	b.Run("stored-metadata", func(b *testing.B) {
		b.ReportAllocs()
		for i := 0; i < b.N; i++ {
			for _, id := range ids {
				if !g.HasStoredThumbnail(id, SizeGrid, fi.Size(), mtime) {
					b.Fatal("missing")
				}
			}
		}
	})
}
