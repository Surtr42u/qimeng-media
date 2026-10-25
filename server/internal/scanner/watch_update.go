package scanner

import (
	"context"
	"database/sql"
	"errors"
	"os"

	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// updateKnownFile 只更新已有路径，未知/消失路径交整库移动合并处理。
// 返回 true 表示需要对账；绝不在单事件视角删除记录或创建移动目标的新身份。
func (w *watcher) updateKnownFile(ctx context.Context, absPath string) bool {
	g := w.s.gate(w.lib.ID)
	// 全量扫描可能持锁很久；转为可取消的对账等待，不能让监听关停
	// 或根路径切换被一个独立的手动扫描阻塞在 Mutex.Lock 上。
	if !g.mu.TryLock() {
		return true
	}
	defer g.mu.Unlock()
	if ctx.Err() != nil {
		return false
	}
	rel, ok := relWithin(w.lib.RootPath, absPath)
	if !ok || !filing.PathWithinRoot(w.lib.RootPath, absPath) {
		return false
	}
	info, err := os.Stat(absPath)
	if err != nil {
		return true
	}
	if info.IsDir() || IsHiddenName(info.Name()) {
		return false
	}
	mediaType, ok := ClassifyMedia(info.Name())
	if !ok {
		return false
	}
	cur, err := w.s.q.GetAssetByPath(ctx, db.GetAssetByPathParams{LibraryID: w.lib.ID, RelPath: rel})
	if errors.Is(err, sql.ErrNoRows) {
		return true
	}
	if err != nil {
		w.logger.Warn("scanner: 增量查询失败", "err", err)
		return true
	}
	if cur.SizeBytes == info.Size() && cur.Mtime == store.FormatTimestamp(info.ModTime()) {
		return false
	}
	asset, _, err := w.s.ingestFile(ctx, w.lib, absPath, rel, mediaType, info)
	if err != nil {
		w.logger.Warn("scanner: 增量更新失败", "err", err)
		return true
	}
	w.s.invalidateThumbs(asset.AssetID, rel)
	w.s.publish(events.TopicLibraryChanged, ScanResult{LibraryID: w.lib.ID, Updated: 1}, asset.AssetID)
	return false
}
