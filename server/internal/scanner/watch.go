package scanner

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/fsnotify/fsnotify"

	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// DefaultDebounce 增量事件防抖窗口（任务基线 300ms）。
// 为什么必须有防抖：往库里写入一个 1GB 视频时，写入器持续 append，
// fsnotify 会把每次写都报成独立事件——create + write×N +（拷贝工具的）
// rename/chmod，一个文件轻松产生上千事件。逐事件处理意味着上千次
// ffprobe 轰炸和一个可能还在写入的半成品文件被反复探测。
const DefaultDebounce = 300 * time.Millisecond

// DefaultPollInterval 轮询兜底周期（任务基线 5min）。
// 为什么轮询不可省：fsnotify 在 Windows 上对 SMB/NFS 网络卷、在 Linux 上
// 对不支持的文件系统静默失效（事件干脆不来，不是延迟）；定时全量扫描
// 是变更检测的最后防线（ARCHITECTURE §3「fsnotify + 定时轮询兜底」）。
const DefaultPollInterval = 5 * time.Minute

// watcher 单个库的增量监听器。生命周期由 Watch 管理（阻塞至 ctx 取消）。
type watcher struct {
	s   *Scanner
	lib db.Library
	fw  *fsnotify.Watcher
	// debounce 防抖窗口（测试可缩短；语义见 schedule）。
	debounce time.Duration
	logger   *slog.Logger

	mu      sync.Mutex
	pending map[string]struct{} // 已排定定时器的路径（去重第二道闸）
}

// Watch 对单个库启动递归 fsnotify 监听，阻塞至 ctx 取消（调用方自行
// go 起）。返回 nil 表示正常收尾。
//
// 设计要点：
//   - 递归注册：fsnotify 只监听已 Add 的目录，启动时 WalkDir 全子树注册，
//     运行期新建目录（Create 事件）动态补注册；
//   - 跳过的目录（SkipDir：隐藏/系统）不注册——省 watch 句柄且免过滤；
//   - 变更处理走 Scanner 的库闸门（mu 等待语义），与全量扫描/轮询互斥；
//   - 单文件删除不做移动合并：合并启发式需要"扫描周期差集"的完整视野
//     （哪些路径整体消失、哪些整体出现），单事件视角无法可靠判定，
//     强行判定会把"删一个+巧合的新文件"误合并。合并是全量扫描的职责。
func (s *Scanner) Watch(ctx context.Context, lib db.Library) error {
	fw, err := fsnotify.NewWatcher()
	if err != nil {
		return fmt.Errorf("scanner: 创建 fsnotify watcher: %w", err)
	}
	defer fw.Close() // 幂等；重复 Close 返回 nil 被忽略

	debounce := s.watchDebounce
	if debounce <= 0 { // 直接构造 Scanner 未走 New 时的兜底
		debounce = DefaultDebounce
	}
	w := &watcher{
		s:        s,
		lib:      lib,
		fw:       fw,
		debounce: debounce,
		logger:   s.logger.With("libraryId", lib.ID),
		pending:  make(map[string]struct{}),
	}
	if err := w.addTree(lib.RootPath); err != nil {
		return fmt.Errorf("scanner: 注册库 %s 监听: %w", lib.RootPath, err)
	}
	w.logger.Info("scanner: 增量监听已启动", "root", lib.RootPath)

	for {
		select {
		case <-ctx.Done():
			w.logger.Info("scanner: 增量监听停止")
			return nil
		case err, ok := <-fw.Errors:
			if !ok {
				return nil
			}
			// fsnotify 错误多为 OS 层（句柄耗尽/目录消失）：记日志继续跑，
			// 缺口由轮询兜底；退出循环会让该库从此失去增量能力且无人重启。
			w.logger.Warn("scanner: fsnotify 错误（轮询兜底）", "err", err)
		case ev, ok := <-fw.Events:
			if !ok {
				return nil
			}
			w.handleEvent(ctx, ev)
		}
	}
}

// handleEvent 分派单条 fsnotify 事件。
func (w *watcher) handleEvent(ctx context.Context, ev fsnotify.Event) {
	// 数据目录内的事件全部忽略（缩略图/回收站/DB 自己的写入不属于库内容，
	// 自噬防御，见 Scanner.dataDir 注释）。
	if w.s.dataDir != "" && w.s.pathInsideDataDir(ev.Name) {
		return
	}
	// 新目录：立即递归注册（目录创建没有防抖必要——注册动作幂等且便宜；
	// 里面的文件事件依赖注册完成后才能收到，拖延反而漏事件）。
	if ev.Has(fsnotify.Create) {
		if info, err := os.Stat(ev.Name); err == nil && info.IsDir() {
			if err := w.addTree(ev.Name); err != nil {
				w.logger.Warn("scanner: 注册新目录监听失败", "dir", ev.Name, "err", err)
			}
			return
		}
	}
	w.schedule(ctx, ev.Name)
}

// addTree 递归注册目录树（含根）。已注册目录重复 Add 返回 nil（fsnotify
// 幂等语义），因此新建目录的子树补注册天然安全。
func (w *watcher) addTree(root string) error {
	var firstErr error
	err := filepath.WalkDir(root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			if firstErr == nil && p == root {
				firstErr = err // 根打不开是调用方错误，向上传
			}
			w.logger.Warn("scanner: 监听注册遍历失败", "path", p, "err", err)
			if d != nil && d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if !d.IsDir() {
			return nil
		}
		if p != root && SkipDir(d.Name()) {
			return filepath.SkipDir
		}
		if err := w.fw.Add(p); err != nil {
			w.logger.Warn("scanner: 目录监听注册失败", "path", p, "err", err)
		}
		return nil
	})
	if firstErr != nil {
		return firstErr
	}
	return err
}

// schedule 把路径排入防抖处理。
//
// 窗口合并语义（不是"静默期"语义）：首条事件启动 debounce 定时器，
// 窗口内的后续事件什么都不做，定时器到点统一处理一次。
// 为什么不用"每次事件重置定时器"的静默期方案：写入大文件时 write 事件
// 持续到达，静默期会被无限推迟——文件写多久扫描器就等多久，慢写入的
// 网络盘上一个文件可能拖延几分钟；固定窗口保证最迟 debounce 后必处理
// （届时若仍在写入，下次变更检测会因 size/mtime 变化再触发一次，不丢）。
func (w *watcher) schedule(ctx context.Context, path string) {
	w.mu.Lock()
	if _, exists := w.pending[path]; exists {
		w.mu.Unlock()
		return // 窗口内重复事件：合并（见函数注释）
	}
	w.pending[path] = struct{}{}
	w.mu.Unlock()

	time.AfterFunc(w.debounce, func() {
		w.mu.Lock()
		delete(w.pending, path)
		w.mu.Unlock()
		w.processFile(ctx, path)
	})
}

// processFile 防抖到期后的单文件处理：存在 → 变更检测后 upsert；
// 消失 → 删除记录。走库闸门（阻塞等待正在进行的全量扫描）。
func (w *watcher) processFile(ctx context.Context, absPath string) {
	g := w.s.gate(w.lib.ID)
	g.mu.Lock()
	defer g.mu.Unlock()

	rel, ok := relWithin(w.lib.RootPath, absPath)
	if !ok {
		return // 理论不可达（watch 只注册库内路径），防御性红线兜底
	}

	info, err := os.Stat(absPath)
	if err != nil {
		if !errors.Is(err, fs.ErrNotExist) {
			w.logger.Warn("scanner: 增量 stat 失败", "path", rel, "err", err)
			return
		}
		w.removeIfPresent(ctx, rel)
		return
	}
	if info.IsDir() || IsHiddenName(info.Name()) {
		return
	}
	mediaType, ok := ClassifyMedia(info.Name())
	if !ok {
		// 非媒体文件出现在已入库媒体路径上（如 xxx.mp4 被覆盖成 xxx.mp4.txt
		// 之外的常规改名不会同路径换类型；此分支最常见于隐藏文件被取消
		// 隐藏属性等边角），交给下一轮全量扫描的差集清理。
		return
	}

	// 变更检测：与全量扫描同一套 size+mtime 判定（防抖窗口尾部的"写完了"
	// 事件与窗口头部事件指向同一版本时，这里直接短路，省一次探测）。
	cur, err := w.s.q.GetAssetByPath(ctx, db.GetAssetByPathParams{
		LibraryID: w.lib.ID, RelPath: rel,
	})
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		w.logger.Warn("scanner: 增量查询失败", "path", rel, "err", err)
		return
	}
	if err == nil &&
		cur.SizeBytes == info.Size() &&
		cur.Mtime == store.FormatTimestamp(info.ModTime()) {
		return
	}

	if _, err := w.s.ingestFile(ctx, w.lib, absPath, rel, mediaType, info); err != nil {
		w.logger.Error("scanner: 增量入库失败", "path", rel, "err", err)
		return
	}
	// err==sql.ErrNoRows 即此前无记录 → 新增；否则是更新。
	changed := ScanResult{LibraryID: w.lib.ID, Updated: 1}
	if errors.Is(err, sql.ErrNoRows) {
		changed = ScanResult{LibraryID: w.lib.ID, Added: 1}
	}
	w.s.publish(events.TopicLibraryChanged, changed)
}

// removeIfPresent 删除已消失路径的入库记录（若存在）。
// 增量删除不做移动合并——理由见 Watch 注释。
func (w *watcher) removeIfPresent(ctx context.Context, rel string) {
	cur, err := w.s.q.GetAssetByPath(ctx, db.GetAssetByPathParams{
		LibraryID: w.lib.ID, RelPath: rel,
	})
	if err != nil {
		return // 无记录：删除事件对未入库路径是常态（如 .tmp 临时文件清走）
	}
	if err := w.s.q.DeleteAsset(ctx, cur.AssetID); err != nil {
		w.logger.Error("scanner: 增量删除失败", "path", rel, "err", err)
		return
	}
	w.logger.Info("scanner: 文件消失（增量），删除记录",
		"relPath", rel, "assetId", cur.AssetID)
	w.s.publish(events.TopicLibraryChanged, ScanResult{
		LibraryID: w.lib.ID, Removed: 1,
	})
	// 增量删除同样可能让 COS 作者变孤立（作者目录最后一个文件被删），
	// 顺手清理，不等下一次轮询扫描（与 Scan 收尾的清理同一语义）。
	w.s.cleanupOrphanCosAuthors(ctx)
}

// StartBackground 轮询兜底：每 interval 对全部注册库做一次全量扫描。
// 阻塞至 ctx 取消（调用方 go 起）；interval<=0 用默认 5min。
// 是否启用由配置决定（调用方不启动即关闭，本函数自身无开关）。
//
// 防重入：Scan 自带闸门——Watch 触发的增量处理持闸门期间撞上轮询，
// 轮询对该库拿不到 CAS 直接跳过本轮（ErrAlreadyScanning 吞掉），
// 下一周期自然补上，不排队不积压。
func (s *Scanner) StartBackground(ctx context.Context, interval time.Duration) error {
	if interval <= 0 {
		interval = DefaultPollInterval
	}
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	s.logger.Info("scanner: 轮询兜底已启动", "interval", interval.String())

	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			libs, err := s.q.ListLibraries(ctx)
			if err != nil {
				s.logger.Warn("scanner: 轮询列举库失败", "err", err)
				continue
			}
			for _, lib := range libs {
				res, err := s.Scan(ctx, lib)
				switch {
				case errors.Is(err, ErrAlreadyScanning):
					// 上一轮还没跑完（超大库+短间隔）：跳过，不报错不积压
				case err != nil:
					s.logger.Warn("scanner: 轮询扫描库失败", "libraryId", lib.ID, "err", err)
				default:
					s.logger.Info("scanner: 轮询扫描完成",
						"libraryId", lib.ID, "added", res.Added, "updated", res.Updated,
						"moved", res.Moved, "removed", res.Removed)
				}
			}
		}
	}
}
