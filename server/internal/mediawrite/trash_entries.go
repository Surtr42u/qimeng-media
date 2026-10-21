package mediawrite

import (
	"encoding/json"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"time"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/safego"
	"qimeng-media/server/internal/sysmon"
)

const trashIDSep = "_"
const trashOperationGate = "qimeng:trash-operations"

// writeTrashMeta 落盘回收站元数据 JSON。
func writeTrashMeta(metaFile string, meta filing.TrashMeta) error {
	b, err := json.Marshal(meta)
	if err != nil {
		return err
	}
	tmp := metaFile + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	if err := os.Rename(tmp, metaFile); err != nil {
		return err
	}
	return nil
}

// TrashEntry 是一次遍历得到的回收站条目（meta 路径与文件路径成对）。
type TrashEntry struct {
	ID       string // "<stamp>_<assetID>"
	Meta     filing.TrashMeta
	MetaFile string
	File     string // 回收站内的文件本体
}

// ListTrash 遍历回收站全部 meta（stamp 定宽零填充 → WalkDir 字典序即删除时间序）。
// 单条 meta 损坏（JSON 非法/读取失败）跳过并继续——回收站是恢复兜底，
// 个别坏条目不应让整个列表 500。
//
// 两遍式设计（遍历只收集、读取走 os.Root）：遍历回调里不做任何文件读取，
// meta 读取全部经 OpenRoot 锚定的 *os.Root 完成——回调期间即使某段被换成
// 符号链接，Root 作用域也保证读取不会逃出回收站根（TOCTOU 收敛到单点）。
func (s *Service) ListTrash() ([]TrashEntry, error) {
	root := filepath.Join(s.cfg.DataDir, filing.TrashRootName)
	f, err := os.OpenRoot(root)
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil // 回收站还不存在（从未删除过任何东西）
	}
	if err != nil {
		return nil, err
	}
	defer filing.CloseRoot(f)

	var metaRels []string
	err = filepath.WalkDir(root, func(p string, d fs.DirEntry, werr error) error {
		if werr != nil {
			// 根都打不开（dataDir 异常）：致命上抛。子项失联跳过——列表/
			// 恢复与物理删除/清空/到期清扫无互斥，并发删改时 Windows 上
			// 目录枚举会撞上刚被 RemoveAll 的子树（偶发 500 的根因）；
			// 容忍口径与 scanner 的 WalkDir 一致（坏角落不毁整个列表）。
			if p == root {
				return werr
			}
			s.logger.Warn("回收站遍历失败，跳过", "path", p, "err", werr)
			if d != nil && d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if d.IsDir() || !strings.HasSuffix(p, filing.TrashMetaSuffix) {
			return nil
		}
		if rel, rerr := filepath.Rel(root, p); rerr == nil {
			metaRels = append(metaRels, rel)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	out := make([]TrashEntry, 0, len(metaRels))
	for _, rel := range metaRels {
		parts := strings.SplitN(filepath.ToSlash(rel), "/", 3)
		if len(parts) < 3 { // stamp/<assetID>/<原路径>.Meta.json 结构损坏
			continue
		}
		b, rerr := f.ReadFile(filepath.FromSlash(rel))
		if rerr != nil {
			s.logger.Warn("跳过不可读的回收站条目", "meta", rel, "err", rerr)
			continue
		}
		var meta filing.TrashMeta
		if json.Unmarshal(b, &meta) != nil {
			s.logger.Warn("跳过损坏的回收站条目", "meta", rel)
			continue
		}
		metaFile := filepath.Join(root, rel)
		out = append(out, TrashEntry{
			ID:       parts[0] + trashIDSep + parts[1],
			Meta:     meta,
			MetaFile: metaFile,
			File:     strings.TrimSuffix(metaFile, filing.TrashMetaSuffix),
		})
	}
	return out, nil
}

// findTrashEntry 按 ID 定位条目；找不到（含已恢复/已物理删除）返回 false。
func (s *Service) findTrashEntry(trashID string) (TrashEntry, bool) {
	parts := strings.SplitN(trashID, trashIDSep, 2)
	if len(parts) != 2 || len(parts[0]) != 19 {
		return TrashEntry{}, false
	}
	entries, err := s.ListTrash()
	if err != nil {
		return TrashEntry{}, false
	}
	for _, e := range entries {
		if e.ID == trashID {
			return e, true
		}
	}
	return TrashEntry{}, false
}

// trashMetricsRefreshTimeout 是 trash 指标刷新的耗时上限：刷新（WalkDir
// 遍历 + 逐条 Stat）跑在删除/恢复请求路径上（变更点推送），回收站极大时
// 不能拖住业务响应。listTrash 不接受 context（改签名会波及其全部调用点），
// 取侵入最小的自我约束：刷新体放 goroutine，最多同步等待一个超时时长——
// 超时即放弃等待，后台算完再 Set（gauge 是推送语义，晚到无害，指标保持
// 上次值，下次变更点再刷新）。与 libraryMetricsRefreshTimeout 同值是两个
// 独立决策（DB 查询上限 vs 磁盘遍历上限），可各自调整。
const trashMetricsRefreshTimeout = 3 * time.Second

// RefreshTrashMetrics 把 trash_items / trash_bytes gauge 刷新为回收站现状。
// 真实源是磁盘 meta 文件（listTrash 遍历），不走库表——trash_items 表是
// 历史迁移遗留的死表（迁移只加不删，留着但不读）。bytes 逐条 os.Stat 求和
// 文件本体大小（与回收站面板 GetApiV1Trash 同口径，不含 meta 自身）。
// 变更点推送刷新：删除入站/恢复/单条物理删除/清空四个时机各调一次，到期
// 清扫有清除时也刷（trash_sweeper.go；零清除的巡检不刷）；除此之外不做定时
// 轮询（OBSERVABILITY 口径：推送刷新）。失败只记日志：指标刷新
// 失败不影响业务路径的成功响应。
func (s *Service) RefreshTrashMetrics() {
	done := make(chan struct{})
	safego.Go(s.logger, "回收站指标刷新", func() {
		defer close(done) // panic 恢复路径也关闭 done，避免请求侧白等满超时
		s.refreshTrashMetricsOnce()
	})
	select {
	case <-done:
	case <-time.After(trashMetricsRefreshTimeout):
		s.logger.Warn("刷新回收站指标超时，放弃等待（后台完成后自行 Set）",
			"timeout", trashMetricsRefreshTimeout)
	}
}

// refreshTrashMetricsOnce 是 refreshTrashMetrics 的实际刷新体（无超时保护，
// 只应在刷新 goroutine 内调用；遍历与 Stat 均为只读操作，gauge Set 并发
// 安全）。
func (s *Service) refreshTrashMetricsOnce() {
	entries, err := s.ListTrash()
	if err != nil {
		s.logger.Warn("刷新回收站指标失败", "err", err)
		return
	}
	var bytes int64
	for _, e := range entries {
		if fi, err := os.Stat(e.File); err == nil {
			bytes += fi.Size()
		}
	}
	sysmon.Default.SetTrashItems(int64(len(entries)))
	sysmon.Default.SetTrashBytes(bytes)
}
