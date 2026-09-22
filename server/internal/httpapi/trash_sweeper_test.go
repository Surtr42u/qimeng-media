package httpapi

// 回收站到期清扫与缩略图删除联动的端到端测试（DOMAIN_RULES §9/§11，
// 2026-09-22 磁盘生命周期批）。复用 browse_test.go 的 testEnv（真实
// 文件 + 真实 SQLite + fakeClock——到期判定靠拨钟，不真等时间）。

import (
	"errors"
	"io/fs"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	"qimeng-media/server/internal/thumbnail"
)

// plantThumbCache 给资产在真实缓存目录伪造全档位（默认档 256/512/1024
// × webp/jpg）缓存文件，返回路径集供“清没了”断言。
func plantThumbCache(t *testing.T, dataDir, assetID string) []string {
	t.Helper()
	var paths []string
	for _, size := range []thumbnail.Size{thumbnail.SizeSmall, thumbnail.SizeGrid, thumbnail.SizePreview} {
		for _, ext := range []string{".webp", ".jpg"} {
			p := thumbnail.ThumbPath(dataDir, thumbnail.CacheKey(assetID, size), ext)
			if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
				t.Fatalf("建缓存目录失败: %v", err)
			}
			if err := os.WriteFile(p, []byte("fake"), 0o644); err != nil {
				t.Fatalf("伪造缓存失败: %v", err)
			}
			paths = append(paths, p)
		}
	}
	return paths
}

func assertFilesGone(t *testing.T, paths []string) {
	t.Helper()
	for _, p := range paths {
		if _, err := os.Stat(p); !errors.Is(err, fs.ErrNotExist) {
			t.Errorf("文件未被清理: %s (err=%v)", p, err)
		}
	}
}

// deleteAssetViaAPI 走真实删除端点把资产送进回收站（meta.DeletedAt 取
// fakeClock 当前时刻）。
func (e *testEnv) deleteAssetViaAPI(t *testing.T, filename string) string {
	t.Helper()
	id, ok := e.assetIDByName(t, filename)
	if !ok {
		t.Fatalf("测试前置失败：%s 不在列表", filename)
	}
	resp := e.do(t, http.MethodDelete, "/api/v1/assets/"+id, "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("删除 %s 期望 200，得到 %d", filename, resp.StatusCode)
	}
	return id
}

// TestTrashSweeperPurgesExpired 核心契约：未到期条目保留，到期条目被
// 物理清除且缩略图缓存联动清理。
func TestTrashSweeperPurgesExpired(t *testing.T) {
	env := newTestEnv(t)
	env.cfg.Trash.RetentionDays = 30

	id := env.deleteAssetViaAPI(t, "a.jpg")
	thumbPaths := plantThumbCache(t, env.dataDir, id)

	// 刚删除（未到期）：清扫后条目与缓存都在。
	env.s.sweepTrashOnce()
	if items := env.trashList(t); len(items) != 1 {
		t.Fatalf("未到期条目被误清：回收站剩余 %d 条，期望 1", len(items))
	}
	for _, p := range thumbPaths {
		if _, err := os.Stat(p); err != nil {
			t.Fatalf("未到期条目的缩略图被误清: %s (err=%v)", p, err)
		}
	}

	// 拨钟越过保留期：条目清除 + 缩略图联动清理。
	env.clock.advance(31 * 24 * time.Hour)
	env.s.sweepTrashOnce()
	if items := env.trashList(t); len(items) != 0 {
		t.Fatalf("到期条目未被清除：回收站剩余 %d 条，期望 0", len(items))
	}
	assertFilesGone(t, thumbPaths)
}

// TestTrashExpiresAtUsesRetentionConfig 列表 ExpiresAt 必须按配置的保留
// 天数计算（与清扫判定同源，配置覆盖后不漂移）。
func TestTrashExpiresAtUsesRetentionConfig(t *testing.T) {
	env := newTestEnv(t)
	env.cfg.Trash.RetentionDays = 7
	env.deleteAssetViaAPI(t, "a.jpg")

	items := env.trashList(t)
	if len(items) != 1 {
		t.Fatalf("回收站期望 1 条，得到 %d", len(items))
	}
	it := items[0]
	if it.DeletedAt == nil || it.ExpiresAt == nil {
		t.Fatal("回收站条目缺少 DeletedAt/ExpiresAt")
	}
	got := it.ExpiresAt.Sub(*it.DeletedAt)
	if got != 7*24*time.Hour {
		t.Errorf("ExpiresAt-DeletedAt = %v, 期望 7d（按配置 retention_days）", got)
	}
}

// TestTrashPhysicalDeleteCleansThumbs 单条物理删除端点的联动清理。
func TestTrashPhysicalDeleteCleansThumbs(t *testing.T) {
	env := newTestEnv(t)
	id := env.deleteAssetViaAPI(t, "a.jpg")
	thumbPaths := plantThumbCache(t, env.dataDir, id)

	items := env.trashList(t)
	if len(items) != 1 || items[0].Id == nil {
		t.Fatalf("回收站期望 1 条含 Id，得到 %+v", items)
	}
	resp := env.do(t, http.MethodDelete, "/api/v1/trash/"+*items[0].Id, "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("物理删除期望 204，得到 %d", resp.StatusCode)
	}
	assertFilesGone(t, thumbPaths)
}

// TestTrashEmptyAllCleansThumbs 清空回收站先收集 meta 再删，缩略图联动
// 对全部条目生效。
func TestTrashEmptyAllCleansThumbs(t *testing.T) {
	env := newTestEnv(t)
	id1 := env.deleteAssetViaAPI(t, "a.jpg")
	id2 := env.deleteAssetViaAPI(t, "b.jpg")
	all := append(plantThumbCache(t, env.dataDir, id1), plantThumbCache(t, env.dataDir, id2)...)

	resp := env.do(t, http.MethodDelete, "/api/v1/trash", "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("清空期望 204，得到 %d", resp.StatusCode)
	}
	if items := env.trashList(t); len(items) != 0 {
		t.Fatalf("清空后回收站期望 0 条，得到 %d", len(items))
	}
	assertFilesGone(t, all)
}
