package filing

import (
	"encoding/json"
	"errors"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

// 缓存只保存可由 meta 重建的索引；恢复写入方显式失效。避免首扫 N 个文件
// 各遍历一次整个回收站。调用方已持库锁，缓存本身仍需保护跨库访问。
var restoreIndex sync.Map
var restoreIndexMu sync.Mutex // 覆盖重建→发布，防止失效后旧快照再次写回。

// InvalidateRestoreIntents 必须在写入/移除恢复意图后、释放库锁前调用。
func InvalidateRestoreIntents(dataDir, libraryID string) {
	restoreIndexMu.Lock()
	defer restoreIndexMu.Unlock()
	restoreIndex.Delete(struct{ dir, library string }{dataDir, libraryID})
}

// PendingRestorePaths 显式提供恢复期间由 meta 预留的路径。扫描每次提交前在同一
// 库锁内读取，避免将“已搬回、身份尚未持久化”的文件注册为新身份；重启后仍有效。
// 使用既有回收站 meta 作为唯一恢复意图来源，不引入第二份状态。
func PendingRestorePaths(dataDir, libraryID string) (map[string]struct{}, error) {
	restoreIndexMu.Lock()
	defer restoreIndexMu.Unlock()
	key := struct{ dir, library string }{dataDir, libraryID}
	if cached, ok := restoreIndex.Load(key); ok {
		return cached.(map[string]struct{}), nil
	}
	paths, err := readPendingRestorePaths(dataDir, libraryID)
	if err == nil {
		restoreIndex.Store(key, paths)
	}
	return paths, err
}

func readPendingRestorePaths(dataDir, libraryID string) (map[string]struct{}, error) {
	paths := map[string]struct{}{}
	if dataDir == "" {
		return paths, nil
	}
	root, err := os.OpenRoot(filepath.Join(dataDir, TrashRootName))
	if errors.Is(err, fs.ErrNotExist) {
		return paths, nil
	}
	if err != nil {
		return nil, err
	}
	defer CloseRoot(root)
	err = fs.WalkDir(root.FS(), ".", func(p string, d fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if d.IsDir() || !strings.HasSuffix(p, TrashMetaSuffix) {
			return nil
		}
		b, err := root.ReadFile(p)
		if errors.Is(err, fs.ErrNotExist) {
			return nil
		} // 其他库已完成恢复。
		if err != nil {
			return err
		}
		var meta TrashMeta
		if err := json.Unmarshal(b, &meta); err != nil {
			return err
		}
		if meta.LibraryID != libraryID || meta.RestorePath == "" {
			return nil
		}
		rel, err := NormalizeRelPath(meta.RestorePath)
		if err != nil {
			return err
		}
		paths[rel] = struct{}{}
		return nil
	})
	return paths, err
}

// InvalidateAllRestoreIntents 用于清空回收站；索引只是缓存，丢弃即可重建。
func InvalidateAllRestoreIntents(dataDir string) {
	restoreIndexMu.Lock()
	defer restoreIndexMu.Unlock()
	restoreIndex.Range(func(key, value any) bool {
		if key.(struct{ dir, library string }).dir == dataDir {
			restoreIndex.Delete(key)
		}
		return true
	})
}

// CloseRoot 统一记录目录句柄关闭失败；不静默丢弃系统错误。
func CloseRoot(root *os.Root) {
	if err := root.Close(); err != nil {
		slog.Warn("filing: 关闭根目录句柄失败", "err", err)
	}
}
