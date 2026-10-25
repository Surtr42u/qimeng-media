package mediawrite

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path"
	"path/filepath"

	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/eventstats"
	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// restore 关键段闭包内的越界分支以错误类型返回（HTTP 响应统一在锁外写，
// 避免闭包内外两处 WriteHeader 路径）；500 级失败带阶段前缀原样上抛。
var ErrRestoreEscaped = errors.New("restore target escapes library root")

// RestoreTrash 恢复：文件搬回库内原路径（冲突自动重命名）
// + UpsertAsset 重建库行（asset_id 不变）。
func (s *Service) RestoreTrash(ctx context.Context, trashID string) error {
	unlockTrash := filing.LockLibrary(trashOperationGate)
	defer unlockTrash()

	e, ok := s.findTrashEntry(trashID)
	if !ok {
		return fail(TrashMissing, "回收站条目不存在", nil)
	}
	lib, err := s.q.GetLibrary(ctx, e.Meta.LibraryID)
	if err != nil {
		return fail(OriginalLibraryGone, "原库已不存在，无法恢复", nil)
	}
	origRel, err := filing.RestorePaths(e.Meta)
	if err != nil {
		return fail(InvalidTrashMeta, "回收站元数据不合法，无法恢复", nil)
	}
	// #9 关键段（锁内）：占用探测 → 冲突自动重命名 → 越界校验 → rename。
	// 协议承诺冲突自动重命名（"基名 (2).ext" 递增——恢复永远成功，不因
	// 冲突 409）：探测与改名必须对同库串行，否则并发上传/恢复会解析出
	// 同一冲突名、rename 静默覆盖。targetRel 在锁内确定后用于库行。
	gerr := filing.WithLibraryGate(lib.ID, func() error { return s.restoreTrashFile(ctx, trashID, &e, lib, origRel) })
	switch {
	case gerr == nil:
	case errors.Is(gerr, ErrRestoreEscaped):
		return fail(InvalidAssetPath, "路径不合法", nil)
	default:
		return fail(InternalFailure, "恢复文件", gerr)
	}
	// 重建库行只写基础列；富化列（normal=出处/角色，cos=作者关联+cos_work）
	// 由扫描写入、meta 不存快照，恢复后必须显式重算——文件 mtime 未变，
	// 之后的扫描只会跳过，不会自然补上。尽力而为：失败/扫描器未装配都不
	// 恢复失败（记录已重建，下次该文件 size/mtime 变化重 ingest 时自愈）。
	if err := s.enrichAsset(ctx, lib.ID, e.Meta.AssetID); err != nil {
		s.logger.Warn("回收站恢复后富化重算失败（待重扫自愈）",
			"assetId", e.Meta.AssetID, "err", err)
	}
	s.PublishLibraryChanged(events.LibraryChangeScope{LibraryID: lib.ID, AssetID: e.Meta.AssetID})
	// 恢复改变了库内文件数与回收站占用：两个指标在此刷新（变更点推送）。
	s.RefreshLibraryFileMetrics()
	s.RefreshTrashMetrics()
	return nil
}

// commitTrashRestore 在库锁内原身份/日统计同事务提交，成功才移除 meta。
func (s *Service) commitTrashRestore(ctx context.Context, e TrashEntry, lib db.Library, targetRel string, fi os.FileInfo) error {
	occupied, err := s.q.GetAssetByPath(ctx, db.GetAssetByPathParams{LibraryID: lib.ID, RelPath: targetRel})
	if err == nil && occupied.AssetID != e.Meta.AssetID {
		return errors.New("恢复落点被其他身份占用，保留意图")
	}
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer s.rollback(tx, "恢复")
	qx := s.q.WithTx(tx)
	now := store.FormatTimestamp(s.now())
	asset, err := qx.UpsertAsset(ctx, db.UpsertAssetParams{
		AssetID: e.Meta.AssetID, LibraryID: lib.ID, RelPath: targetRel, FileName: path.Base(targetRel),
		MediaType: e.Meta.MediaType, SizeBytes: fi.Size(), Mtime: store.FormatTimestamp(fi.ModTime()), CreatedAt: now, UpdatedAt: now,
	})
	if err != nil {
		return fmt.Errorf("重建资产记录（恢复意图保留，可重试）: %w", err)
	}
	if asset.AssetID != e.Meta.AssetID {
		return errors.New("恢复路径被其他资产身份占用，保留意图")
	}
	if err := eventstats.RebuildInTx(ctx, qx, e.Meta.AssetID); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	filing.InvalidateRestoreIntents(s.cfg.DataDir, lib.ID)
	if err := os.Remove(e.MetaFile); err != nil {
		return fmt.Errorf("清除已提交恢复意图: %w", err)
	}

	return nil
}

// restoreTrashFile 在库锁内复核意图/发布文件，再调用事务提交；不删除唯一原件。
func (s *Service) restoreTrashFile(ctx context.Context, trashID string, e *TrashEntry, lib db.Library, origRel string) error {
	// 前一个请求可能已经完成或写入了恢复意图，锁内重读。
	current, exists := s.findTrashEntry(trashID)
	if !exists {
		return os.ErrNotExist
	}
	*e = current
	targetRel := origRel
	pending := e.Meta.RestorePath != ""
	if pending {
		rel, err := filing.NormalizeRelPath(e.Meta.RestorePath)
		if err != nil {
			return ErrRestoreEscaped
		}
		targetRel = rel
	}
	if _, err := os.Stat(filepath.Join(lib.RootPath, filepath.FromSlash(origRel))); !pending && err == nil {
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
		s.logger.Error("恢复目标越出库根，已拦截", "assetId", e.Meta.AssetID)
		return ErrRestoreEscaped
	}
	root, err := os.OpenRoot(lib.RootPath)
	if err != nil {
		return err
	}
	defer filing.CloseRoot(root)
	if err := root.MkdirAll(filepath.FromSlash(path.Dir(targetRel)), dirPerm); err != nil {
		return fmt.Errorf("创建恢复目录: %w", err)
	}
	// 意图必须先落盘。源缺失的重试只接受与意图指纹一致的目标，绝不重新改名。
	if _, err := os.Stat(e.File); err == nil {
		fi, err := os.Stat(e.File)
		if err != nil {
			return err
		}
		e.Meta.RestorePath = targetRel
		e.Meta.RestoreSize = fi.Size()
		e.Meta.RestoreMtime = fi.ModTime()
		filing.InvalidateRestoreIntents(s.cfg.DataDir, lib.ID)
		if err := writeTrashMeta(e.MetaFile, e.Meta); err != nil {
			return err
		}
		if err := s.moveFile(e.File, target); err != nil {
			if !errors.Is(err, filing.ErrMoveTargetExists) && !errors.Is(err, filing.ErrMoveDidNotCreateTarget) {
				s.CleanupMoveResidue(e.File, target)
			}
			return err
		}
	} else if !pending || !errors.Is(err, os.ErrNotExist) {
		return err
	}
	fi, err := os.Stat(target)
	if err != nil {
		return err
	}
	if fi.Size() != e.Meta.RestoreSize || !fi.ModTime().Equal(e.Meta.RestoreMtime) {
		return errors.New("恢复落点与意图不一致，保留元数据等待处理")
	}
	return s.commitTrashRestore(ctx, *e, lib, targetRel, fi)
}
