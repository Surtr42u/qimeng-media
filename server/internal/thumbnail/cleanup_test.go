package thumbnail

// DeleteAssetThumbs 契约测试：全档位全扩展名清理、long_side 漂移键覆盖、
// 幂等、不误删他资产（键含 assetID，跨资产不可能同键）。

import (
	"os"
	"path/filepath"
	"testing"
)

// plantThumb 在指定键路径伪造一份缓存文件（不做真转码——删除联动只关心
// 文件是否落在 CacheKey/ThumbPath 计算出的路径上）。
func plantThumb(t *testing.T, g *Generator, assetID string, size Size, ext string) string {
	t.Helper()
	p := ThumbPath(g.dataDir, CacheKey(assetID, size), ext)
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatalf("建缓存目录失败: %v", err)
	}
	if err := os.WriteFile(p, []byte("fake-thumb"), 0o644); err != nil {
		t.Fatalf("伪造缓存文件失败: %v", err)
	}
	return p
}

// TestDeleteAssetThumbsRemovesAllSizesAndExts 默认档位（256/512/1024）
// × 双扩展名（webp/jpg）全部清理，且不碰其他资产的缓存。
func TestDeleteAssetThumbsRemovesAllSizesAndExts(t *testing.T) {
	g := NewGenerator(t.TempDir(), nil, Options{})
	t.Cleanup(g.Close)
	id := "11111111-1111-1111-1111-111111111111"
	other := "22222222-2222-2222-2222-222222222222"

	var planted []string
	for _, size := range []Size{SizeSmall, SizeGrid, SizePreview} {
		for _, ext := range []string{".webp", ".jpg"} {
			planted = append(planted, plantThumb(t, g, id, size, ext))
		}
	}
	otherPath := plantThumb(t, g, other, SizeGrid, ".webp")

	g.DeleteAssetThumbs(id)
	for _, p := range planted {
		if _, err := os.Stat(p); !os.IsNotExist(err) {
			t.Errorf("缓存未被清理: %s (err=%v)", p, err)
		}
	}
	if _, err := os.Stat(otherPath); err != nil {
		t.Errorf("误删了其他资产的缓存: %s (err=%v)", otherPath, err)
	}
	// 幂等：再次调用对不存在的文件静默成功。
	g.DeleteAssetThumbs(id)
}

// TestDeleteAssetThumbsCoversLongSideDrift long_side 配置覆盖生效时，
// 当前键（配置像素）与漂移前旧键（SizeGrid 默认档）都要清理。
func TestDeleteAssetThumbsCoversLongSideDrift(t *testing.T) {
	g := NewGenerator(t.TempDir(), nil, Options{LongSide: 700})
	t.Cleanup(g.Close)
	id := "33333333-3333-3333-3333-333333333333"

	cur := plantThumb(t, g, id, Size(g.GridLongSide()), ".webp")
	drifted := plantThumb(t, g, id, SizeGrid, ".webp")

	g.DeleteAssetThumbs(id)
	for _, p := range []string{cur, drifted} {
		if _, err := os.Stat(p); !os.IsNotExist(err) {
			t.Errorf("缓存未被清理（long_side 漂移键覆盖失败）: %s (err=%v)", p, err)
		}
	}
}
