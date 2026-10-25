package scanner

import (
	"fmt"
	"runtime"
	"testing"

	"qimeng-media/server/internal/store/db"
)

// BenchmarkScanSnapshotIndex 只对比 2 万合成记录的路径索引构建，
// 不含 SQL 解码、文件遍历、视频探测，不可当作整库端到端加速比例。
func BenchmarkScanSnapshotIndex(b *testing.B) {
	assets := make([]db.Asset, 20000)
	for i := range assets {
		assets[i] = db.Asset{AssetID: fmt.Sprint(i), RelPath: fmt.Sprintf("album/%06d.jpg", i), SizeBytes: int64(i)}
	}
	b.Run("legacy-asset-copy", func(b *testing.B) {
		b.ReportAllocs()
		for b.Loop() {
			known := make(map[string]db.Asset, len(assets))
			for _, asset := range assets {
				known[asset.RelPath] = asset
			}
			runtime.KeepAlive(known)
		}
	})
	snapshots := make([]db.ListScanAssetSnapshotsRow, len(assets))
	for i, a := range assets {
		snapshots[i] = db.ListScanAssetSnapshotsRow{AssetID: a.AssetID, RelPath: a.RelPath, SizeBytes: a.SizeBytes, Mtime: a.Mtime, UpdatedAt: a.UpdatedAt}
	}
	b.Run("snapshot-index", func(b *testing.B) {
		b.ReportAllocs()
		for b.Loop() {
			runtime.KeepAlive(indexScanAssets(snapshots))
		}
	})
}
