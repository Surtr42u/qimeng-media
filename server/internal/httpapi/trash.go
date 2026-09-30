// trash.go：删除→回收站与回收站管理端点。
//
// 回收站布局与语义的纯函数在 filing 包（TrashPathFor/RestorePaths）；
// 本文件是磁盘 IO + 库行 + 事件的编排。meta 文件是回收站的真相源：
// 枚举=遍历 *.meta.json，恢复=读 meta 反算原路径（见 filing.TrashMeta）。
//
// 已知限制（M2 语义，DOMAIN_RULES §9 同步）：删除时库行硬删（FK 级联
// 清标签/收藏/点赞绑定），恢复=UpsertAsset 重建（asset_id 与浏览/播放
// 历史不变——view_events 无外键是 ADR-0005 的设计红利）；手工绑定
// （标签/收藏）不随恢复还原，用户在意时后续版本给 meta 加关联快照。
package httpapi

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
	"time"

	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/sysmon"
)

// trashIDSep 是回收站条目 ID "<19位stamp>_<assetID>" 的分隔符。
// stamp 定宽 19 位零填充（filing.TrashPathFor），UUID 不含下划线，
// SplitN(_, 2) 拆分无歧义。
const trashIDSep = "_"

// publishLibraryChanged 广播库变更（列表/详情端已变，各端刷新）。
// 广播型触发帧不带 payload（LibraryChangedEvent.data 可为 null）。
// 推荐流缓存失效不再在此直调：library.changed 事件的失效已收口到 Server
// 装配期的总线订阅（server.go New，一处覆盖所有发布点，2026-09-18 性能批二段）。
func (s *Server) publishLibraryChanged() {
	if err := s.bus.Publish(events.Event{Topic: events.TopicLibraryChanged}); err != nil {
		s.logger.Warn("发布库变更事件失败", "err", err)
	}
}

// trashMetricsRefreshTimeout 是 trash 指标刷新的耗时上限：刷新（WalkDir
// 遍历 + 逐条 Stat）跑在删除/恢复请求路径上（变更点推送），回收站极大时
// 不能拖住业务响应。listTrash 不接受 context（改签名会波及其全部调用点），
// 取侵入最小的自我约束：刷新体放 goroutine，最多同步等待一个超时时长——
// 超时即放弃等待，后台算完再 Set（gauge 是推送语义，晚到无害，指标保持
// 上次值，下次变更点再刷新）。与 libraryMetricsRefreshTimeout 同值是两个
// 独立决策（DB 查询上限 vs 磁盘遍历上限），可各自调整。
const trashMetricsRefreshTimeout = 3 * time.Second

// refreshTrashMetrics 把 trash_items / trash_bytes gauge 刷新为回收站现状。
// 真实源是磁盘 meta 文件（listTrash 遍历），不走库表——trash_items 表是
// 历史迁移遗留的死表（迁移只加不删，留着但不读）。bytes 逐条 os.Stat 求和
// 文件本体大小（与回收站面板 GetApiV1Trash 同口径，不含 meta 自身）。
// 变更点推送刷新：删除入站/恢复/单条物理删除/清空四个时机各调一次，到期
// 清扫有清除时也刷（trash_sweeper.go；零清除的巡检不刷）；除此之外不做定时
// 轮询（OBSERVABILITY 口径：推送刷新）。失败只记日志：指标刷新
// 失败不影响业务路径的成功响应。
func (s *Server) refreshTrashMetrics() {
	done := make(chan struct{})
	go func() {
		defer close(done)
		s.refreshTrashMetricsOnce()
	}()
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
func (s *Server) refreshTrashMetricsOnce() {
	entries, err := s.listTrash()
	if err != nil {
		s.logger.Warn("刷新回收站指标失败", "err", err)
		return
	}
	var bytes int64
	for _, e := range entries {
		if fi, err := os.Stat(e.file); err == nil {
			bytes += fi.Size()
		}
	}
	sysmon.Default.SetTrashItems(int64(len(entries)))
	sysmon.Default.SetTrashBytes(bytes)
}

// DeleteApiV1AssetsAssetId 删除资产：文件移入回收站 + 库行删除。
//
// 顺序约束：先移文件后删行——中途失败的最坏情形是"文件已在回收站、
// 行还在"（下次扫描发现旧路径消失，走移动合并启发式或清理），
// 而反过来"行没了、文件还在库目录"会让文件成为无主孤儿且扫描会
// 重新入库（删除静默失效）。两类漂移里前者可自愈，后者是数据事故。
func (s *Server) DeleteApiV1AssetsAssetId(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	row, err := s.q.GetAssetWithLibrary(r.Context(), assetID.String())
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, codeNotFound, "资产不存在")
		return
	}
	if err != nil {
		s.internalErr(w, "查询资产", err)
		return
	}
	src := filepath.Join(row.RootPath, filepath.FromSlash(row.RelPath))
	// 纵深防御：SECURITY 红线 1（路径穿越）。rel_path 来自库，扫描入库时
	// 已过 NormalizeRelPath；为什么数据库路径也再验一次——外部工具改库、
	// 迁移 bug、历史脏数据都可能让库行不再干净。删除是不可逆方向的文件
	// 移动（移进回收站），库数据被污染时绝不能把库外文件当资产删走，
	// PathWithinRoot 是 handler 侧最后一道闸（与 media 直链同一模式）。
	if !filing.PathWithinRoot(row.RootPath, src) {
		s.logger.Error("资产相对路径越界，删除已拦截", "assetId", row.AssetID)
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "路径不合法")
		return
	}
	if _, err := os.Stat(src); err != nil {
		// 库与磁盘漂移（文件已被外部移动/删除）：删除无从谈起，
		// 404 让用户感知而不是 500——行还在库里的清理由扫描器负责。
		writeErr(w, http.StatusNotFound, codeFileMissing, "库内文件不存在（可能已被外部移动）")
		return
	}
	now := s.now()
	trashFile, metaFile, err := filing.TrashPathFor(s.cfg.DataDir, row.RelPath, now, row.AssetID)
	if err != nil {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "资产路径不合法")
		return
	}
	if err := os.MkdirAll(filepath.Dir(trashFile), dirPerm); err != nil {
		s.internalErr(w, "创建回收站目录", err)
		return
	}
	if err := filing.MoveFile(src, trashFile); err != nil {
		s.internalErr(w, "移入回收站", err)
		return
	}
	meta := filing.TrashMeta{
		AssetID:      row.AssetID,
		LibraryID:    row.LibraryID,
		OriginalPath: row.RelPath,
		MediaType:    row.MediaType,
		DeletedAt:    now,
	}
	if err := writeTrashMeta(metaFile, meta); err != nil {
		// meta 是回收站真相源，写失败必须回滚文件移动，否则产生
		// "有文件无 meta"的不可恢复条目。
		if rbErr := filing.MoveFile(trashFile, src); rbErr != nil {
			s.logger.Error("回收站 meta 写入失败且回滚移动失败（需人工介入）",
				"err", err, "rollbackErr", rbErr, "trashFile", trashFile)
		}
		s.internalErr(w, "写入回收站元数据", err)
		return
	}
	if err := s.q.DeleteAsset(r.Context(), row.AssetID); err != nil {
		// 文件已在回收站、行删除失败：行残留会由下次扫描的"旧路径消失"
		// 逻辑清掉，不回滚文件（回滚可能又撞上 meta 已写的中间态）。
		s.logger.Error("删除库行失败（待扫描器清理）", "err", err, "assetId", row.AssetID)
		s.internalErr(w, "删除资产记录", err)
		return
	}
	s.publishLibraryChanged()
	// 删除落站改变了库内文件数与回收站占用：两个指标在此刷新（变更点推送）。
	s.refreshLibraryFileMetrics()
	s.refreshTrashMetrics()
	w.WriteHeader(http.StatusOK)
}

// writeTrashMeta 落盘回收站元数据 JSON。
func writeTrashMeta(metaFile string, meta filing.TrashMeta) error {
	b, err := json.Marshal(meta)
	if err != nil {
		return err
	}
	return os.WriteFile(metaFile, b, 0o600)
}

// trashEntry 是一次遍历得到的回收站条目（meta 路径与文件路径成对）。
type trashEntry struct {
	id       string // "<stamp>_<assetID>"
	meta     filing.TrashMeta
	metaFile string
	file     string // 回收站内的文件本体
}

// listTrash 遍历回收站全部 meta（stamp 定宽零填充 → WalkDir 字典序即删除时间序）。
// 单条 meta 损坏（JSON 非法/读取失败）跳过并继续——回收站是恢复兜底，
// 个别坏条目不应让整个列表 500。
//
// 两遍式设计（遍历只收集、读取走 os.Root）：遍历回调里不做任何文件读取，
// meta 读取全部经 OpenRoot 锚定的 *os.Root 完成——回调期间即使某段被换成
// 符号链接，Root 作用域也保证读取不会逃出回收站根（TOCTOU 收敛到单点）。
func (s *Server) listTrash() ([]trashEntry, error) {
	root := filepath.Join(s.cfg.DataDir, filing.TrashRootName)
	f, err := os.OpenRoot(root)
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil // 回收站还不存在（从未删除过任何东西）
	}
	if err != nil {
		return nil, err
	}
	defer func() { _ = f.Close() }()

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
	out := make([]trashEntry, 0, len(metaRels))
	for _, rel := range metaRels {
		parts := strings.SplitN(filepath.ToSlash(rel), "/", 3)
		if len(parts) < 3 { // stamp/<assetID>/<原路径>.meta.json 结构损坏
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
		out = append(out, trashEntry{
			id:       parts[0] + trashIDSep + parts[1],
			meta:     meta,
			metaFile: metaFile,
			file:     strings.TrimSuffix(metaFile, filing.TrashMetaSuffix),
		})
	}
	return out, nil
}

// GetApiV1Trash 回收站列表（按删除时间从新到旧）。
func (s *Server) GetApiV1Trash(w http.ResponseWriter, r *http.Request) {
	entries, err := s.listTrash()
	if err != nil {
		s.internalErr(w, "遍历回收站", err)
		return
	}
	items := make([]gen.TrashItem, 0, len(entries))
	for i := len(entries) - 1; i >= 0; i-- { // WalkDir 是旧→新，面板要新的在前
		e := &entries[i]
		size := int64(0)
		if fi, err := os.Stat(e.file); err == nil {
			size = fi.Size()
		}
		id := e.id
		name := path.Base(e.meta.OriginalPath)
		orig := e.meta.OriginalPath
		deleted := e.meta.DeletedAt
		// 到期展示与到期清扫（trash_sweeper.go）必须同源取保留天数，
		// 否则配置覆盖后面板日期与实际清除漂移（trashRetentionDays 兜底链）。
		expires := e.meta.DeletedAt.AddDate(0, 0, s.trashRetentionDays())
		sb := size
		items = append(items, gen.TrashItem{
			Id:           &id,
			FileName:     &name,
			OriginalPath: &orig,
			DeletedAt:    &deleted,
			SizeBytes:    &sb,
			ExpiresAt:    &expires,
		})
	}
	writeJSON(w, http.StatusOK, items)
}

// findTrashEntry 按 ID 定位条目；找不到（含已恢复/已物理删除）返回错误 → 404。
func (s *Server) findTrashEntry(trashID string) (trashEntry, bool) {
	parts := strings.SplitN(trashID, trashIDSep, 2)
	if len(parts) != 2 || len(parts[0]) != 19 {
		return trashEntry{}, false
	}
	entries, err := s.listTrash()
	if err != nil {
		return trashEntry{}, false
	}
	for _, e := range entries {
		if e.id == trashID {
			return e, true
		}
	}
	return trashEntry{}, false
}

// restore 关键段闭包内的越界分支以错误类型返回（HTTP 响应统一在锁外写，
// 避免闭包内外两处 WriteHeader 路径）；500 级失败带阶段前缀原样上抛。
var errRestoreEscaped = errors.New("restore target escapes library root")

// PostApiV1TrashTrashIdRestore 恢复：文件搬回库内原路径（冲突自动重命名）
// + UpsertAsset 重建库行（asset_id 不变）。
func (s *Server) PostApiV1TrashTrashIdRestore(w http.ResponseWriter, r *http.Request, trashID gen.TrashId) {
	e, ok := s.findTrashEntry(trashID)
	if !ok {
		writeErr(w, http.StatusNotFound, codeNotFound, "回收站条目不存在")
		return
	}
	lib, err := s.q.GetLibrary(r.Context(), e.meta.LibraryID)
	if err != nil {
		writeErr(w, http.StatusNotFound, codeNotFound, "原库已不存在，无法恢复")
		return
	}
	origRel, err := filing.RestorePaths(e.meta)
	if err != nil {
		writeErr(w, http.StatusBadRequest, codeInvalidMeta, "回收站元数据不合法，无法恢复")
		return
	}
	// #9 关键段（锁内）：占用探测 → 冲突自动重命名 → 越界校验 → rename。
	// 协议承诺冲突自动重命名（"基名 (2).ext" 递增——恢复永远成功，不因
	// 冲突 409）：探测与改名必须对同库串行，否则并发上传/恢复会解析出
	// 同一冲突名、rename 静默覆盖。targetRel 在锁内确定后用于库行。
	var targetRel string
	gerr := filing.WithLibraryGate(lib.ID, func() error {
		targetRel = origRel
		if _, err := os.Stat(filepath.Join(lib.RootPath, filepath.FromSlash(origRel))); err == nil {
			dir, name := path.Split(origRel)
			exists := func(n string) bool {
				_, err := os.Stat(filepath.Join(lib.RootPath, filepath.FromSlash(dir+n)))
				return err == nil
			}
			targetRel = path.Join(dir, filing.ResolveConflict(name, exists))
		}
		target := filepath.Join(lib.RootPath, filepath.FromSlash(targetRel))
		// 纵深防御：SECURITY 红线 1。RestorePaths 已对 meta 的 OriginalPath 过
		// NormalizeRelPath；为什么库根与拼接结果也再验一次——恢复目标是"库内
		// 写文件"，库行 root_path 与 meta 均属持久化数据，任一被污染（改库/
		// 坏 meta/根路径配置漂移）都不该把文件写出库根。与删除侧同一闸门。
		if !filing.PathWithinRoot(lib.RootPath, target) {
			s.logger.Error("恢复目标越出库根，已拦截", "assetId", e.meta.AssetID)
			return errRestoreEscaped
		}
		if err := os.MkdirAll(filepath.Dir(target), dirPerm); err != nil {
			return fmt.Errorf("创建恢复目录: %w", err)
		}
		return filing.MoveFile(e.file, target)
	})
	switch {
	case gerr == nil:
	case errors.Is(gerr, errRestoreEscaped):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "路径不合法")
		return
	default:
		s.internalErr(w, "恢复文件", gerr)
		return
	}
	target := filepath.Join(lib.RootPath, filepath.FromSlash(targetRel))
	if err := os.Remove(e.metaFile); err != nil {
		s.logger.Warn("删除回收站 meta 失败（文件已恢复，残留 meta 不影响功能）",
			"err", err, "meta", e.metaFile)
	}
	// 重建库行：size/mtime 以恢复后的真实文件为准（Rename 不改 mtime）。
	fi, err := os.Stat(target)
	if err != nil {
		s.internalErr(w, "读取恢复后文件信息", err)
		return
	}
	now := store.FormatTimestamp(s.now())
	mtime := store.FormatTimestamp(fi.ModTime())
	if _, err := s.q.UpsertAsset(r.Context(), db.UpsertAssetParams{
		AssetID:   e.meta.AssetID,
		LibraryID: lib.ID,
		RelPath:   targetRel,
		FileName:  path.Base(targetRel),
		MediaType: e.meta.MediaType,
		SizeBytes: fi.Size(),
		Mtime:     mtime,
		CreatedAt: now, // 恢复即重新入库：created_at 取恢复时刻（meta 不存原值，接受语义偏移）
		UpdatedAt: now,
	}); err != nil {
		s.internalErr(w, "重建资产记录", err)
		return
	}
	// 重建库行只写基础列；富化列（normal=出处/角色，cos=作者关联+cos_work）
	// 由扫描写入、meta 不存快照，恢复后必须显式重算——文件 mtime 未变，
	// 之后的扫描只会跳过，不会自然补上。尽力而为：失败/扫描器未装配都不
	// 恢复失败（记录已重建，下次该文件 size/mtime 变化重 ingest 时自愈）。
	if err := s.scanner.EnrichAsset(r.Context(), lib.ID, e.meta.AssetID); err != nil && !errors.Is(err, ErrScannerUnavailable) {
		s.logger.Warn("回收站恢复后富化重算失败（待重扫自愈）",
			"assetId", e.meta.AssetID, "err", err)
	}
	s.publishLibraryChanged()
	// 恢复改变了库内文件数与回收站占用：两个指标在此刷新（变更点推送）。
	s.refreshLibraryFileMetrics()
	s.refreshTrashMetrics()
	w.WriteHeader(http.StatusOK)
}

// DeleteApiV1TrashTrashId 物理删除回收站内单个条目（文件+meta 成对删除）。
func (s *Server) DeleteApiV1TrashTrashId(w http.ResponseWriter, r *http.Request, trashID gen.TrashId) {
	e, ok := s.findTrashEntry(trashID)
	if !ok {
		writeErr(w, http.StatusNotFound, codeNotFound, "回收站条目不存在")
		return
	}
	// stamp/<assetID>/ 子树里只有这一对文件+meta（一次删除一个文件），
	// 整树删除顺带清掉空目录壳。
	if err := os.RemoveAll(filepath.Dir(e.metaFile)); err != nil {
		s.internalErr(w, "物理删除回收站条目", err)
		return
	}
	// 条目永久消失：该资产缩略图缓存不再可达，联动清理（软删除→恢复
	// 路径不清——asset_id 不变恢复后继续命中缓存，见 thumbnail/cleanup.go）。
	s.thumbs.DeleteAssetThumbs(e.meta.AssetID)
	// 物理删除不走 publishLibraryChanged（条目早已出库，不发布库变更），
	// 修订号在此显式推进（revision.go 的 bump 链清单）。
	s.bumpLibraryRevision()
	s.logger.Info("回收站条目已物理删除", "id", e.id, "originalPath", e.meta.OriginalPath)
	// 库内文件数不变（条目早已出库），只刷回收站两 gauge（变更点推送）。
	s.refreshTrashMetrics()
	w.WriteHeader(http.StatusNoContent)
}

// DeleteApiV1Trash 清空回收站（物理删除全部条目）。
func (s *Server) DeleteApiV1Trash(w http.ResponseWriter, r *http.Request) {
	// meta 是回收站真相源：RemoveAll 之后无法再反查 assetID，缩略图联动
	// 清理必须先收集（遍历失败只降级——缓存残留待对账兜底，不拦清空）。
	entries, err := s.listTrash()
	if err != nil {
		s.logger.Warn("清空前遍历回收站失败（缩略图联动清理跳过）", "err", err)
	}
	root := filepath.Join(s.cfg.DataDir, filing.TrashRootName)
	if err := os.RemoveAll(root); err != nil {
		s.internalErr(w, "清空回收站", err)
		return
	}
	if err := os.MkdirAll(root, dirPerm); err != nil {
		s.internalErr(w, "重建回收站目录", err)
		return
	}
	for _, e := range entries {
		s.thumbs.DeleteAssetThumbs(e.meta.AssetID)
	}
	// 清空=全部条目物理删除：同单条物理删除，修订号显式推进。
	s.bumpLibraryRevision()
	s.logger.Info("回收站已清空")
	// 清空后回收站归零：gauge 显式 Set 回 0（变更点推送）。
	s.refreshTrashMetrics()
	w.WriteHeader(http.StatusNoContent)
}
