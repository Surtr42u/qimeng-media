package mediawrite

import (
	"context"
	"database/sql"
	"errors"
	"os"
	"path"
	"path/filepath"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/filing"
)

// MoveToTrash 删除资产：文件移入回收站 + 库行删除。
//
// 顺序约束：先移文件后删行——中途失败的最坏情形是"文件已在回收站、
// 行还在"（下次扫描发现旧路径消失，走移动合并启发式或清理），
// 而反过来"行没了、文件还在库目录"会让文件成为无主孤儿且扫描会
// 重新入库（删除静默失效）。两类漂移里前者可自愈，后者是数据事故。
func (s *Service) MoveToTrash(ctx context.Context, assetID string) error {
	unlockTrash := filing.LockLibrary(trashOperationGate)
	defer unlockTrash()

	row, err := s.q.GetAssetWithLibrary(ctx, assetID)
	if errors.Is(err, sql.ErrNoRows) {
		return fail(AssetMissing, "资产不存在", nil)
	}
	if err != nil {
		return fail(InternalFailure, "查询资产", err)
	}
	unlock := filing.LockLibrary(row.LibraryID)
	defer unlock()
	row, err = s.q.GetAssetWithLibrary(ctx, assetID)
	if err != nil {
		return fail(InternalFailure, "重新查询待删除资产", err)
	}
	src := filepath.Join(row.RootPath, filepath.FromSlash(row.RelPath))
	// 纵深防御：SECURITY 红线 1（路径穿越）。rel_path 来自库，扫描入库时
	// 已过 NormalizeRelPath；为什么数据库路径也再验一次——外部工具改库、
	// 迁移 bug、历史脏数据都可能让库行不再干净。删除是不可逆方向的文件
	// 移动（移进回收站），库数据被污染时绝不能把库外文件当资产删走，
	// PathWithinRoot 是 handler 侧最后一道闸（与 media 直链同一模式）。
	if !filing.PathWithinRoot(row.RootPath, src) {
		s.logger.Error("资产相对路径越界，删除已拦截", "assetId", row.AssetID)
		return fail(InvalidAssetPath, "路径不合法", nil)
	}
	if _, err := os.Stat(src); err != nil {
		// 库与磁盘漂移（文件已被外部移动/删除）：删除无从谈起，
		// 404 让用户感知而不是 500——行还在库里的清理由扫描器负责。
		return fail(MediaFileMissing, "库内文件不存在（可能已被外部移动）", nil)
	}
	now := s.now()
	trashFile, metaFile, err := filing.TrashPathFor(s.cfg.DataDir, row.RelPath, now, row.AssetID)
	if err != nil {
		return fail(InvalidTrashPath, "资产路径不合法", nil)
	}
	if err := os.MkdirAll(filepath.Dir(trashFile), dirPerm); err != nil {
		return fail(InternalFailure, "创建回收站目录", err)
	}
	if err := s.moveFile(src, trashFile); err != nil {
		// 入站半截副本发生在 meta 写入之前——列表/清扫/指标全按 *.Meta.json
		// 遍历，留着就是永久不可见的幽灵文件，失败即清（best-effort）。
		if !errors.Is(err, filing.ErrMoveTargetExists) && !errors.Is(err, filing.ErrMoveDidNotCreateTarget) {
			s.CleanupMoveResidue(src, trashFile)
		}
		return fail(InternalFailure, "移入回收站", err)
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
		if rbErr := s.moveFile(trashFile, src); rbErr != nil {
			// 回滚目标的半截副本清掉（best-effort）：它落在库内原路径上，
			// 残留会被扫描器注册成坏资产。trashFile 本体（完整副本、无 meta）
			// 故意保留——回滚失败时它是唯一完好副本，宁留勿删。
			if !errors.Is(rbErr, filing.ErrMoveTargetExists) && !errors.Is(rbErr, filing.ErrMoveDidNotCreateTarget) {
				s.CleanupMoveResidue(trashFile, src)
			}
			s.logger.Error("回收站 meta 写入失败且回滚移动失败（需恢复核对）",
				"err", err, "rollbackErr", rbErr, "trashFile", trashFile)
		}
		return fail(InternalFailure, "写入回收站元数据", err)
	}
	if err := s.q.DeleteAsset(ctx, row.AssetID); err != nil {
		// 文件已在回收站、行删除失败：行残留会由下次扫描的"旧路径消失"
		// 逻辑清掉，不回滚文件（回滚可能又撞上 meta 已写的中间态）。
		s.logger.Error("删除库行失败（待扫描器清理）", "err", err, "assetId", row.AssetID)
		return fail(InternalFailure, "删除资产记录", err)
	}
	s.PublishLibraryChanged(events.LibraryChangeScope{LibraryID: row.LibraryID, AssetID: row.AssetID})
	// 删除落站改变了库内文件数与回收站占用：两个指标在此刷新（变更点推送）。
	s.RefreshLibraryFileMetrics()
	s.RefreshTrashMetrics()
	return nil
}

// dropTrashWorks 在回收站文件被物理删除前，用同一数据库事务清理该条目的
// TXT 作品行。MoveToTrash 已删除 assets 行（asset_authors 随外键级联清空），
// 因此 DropAssetWorks 会走「资产行不存在」的文件名级兜底；库内仍有同名资产
// 时它保留作品行。调用方负责确认没有其他同名可恢复条目后再调用。
func (s *Service) dropTrashWorks(ctx context.Context, assetID, fileName string) error {
	if s.conn == nil {
		return errors.New("mediawrite: 回收站物理删除缺少数据库连接")
	}
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer s.rollback(tx, "回收站物理删除作者TXT作品行")
	qtx := s.q.WithTx(tx)
	if err := (&authorattach.Service{Logger: s.logger}).DropAssetWorks(ctx, qtx, s.now(), assetID, fileName); err != nil {
		return err
	}
	return tx.Commit()
}

// hasRestorableSameName 报告除 target 外是否还有同名（basename）的回收站
// 条目——它们仍可能被恢复，作品行不能删。entries 是本次操作看到的完整快照。
func hasRestorableSameName(entries []TrashEntry, targetID, fileName string) bool {
	for _, other := range entries {
		if other.ID != targetID && path.Base(other.Meta.OriginalPath) == fileName {
			return true
		}
	}
	return false
}

// PurgeTrash 物理删除回收站内单个条目（文件+meta 成对删除）。
func (s *Service) PurgeTrash(trashID string) error {
	unlockTrash := filing.LockLibrary(trashOperationGate)
	defer unlockTrash()

	e, ok := s.findTrashEntry(trashID)
	if !ok {
		return fail(TrashMissing, "回收站条目不存在", nil)
	}
	entries, err := s.ListTrash()
	if err != nil {
		return fail(InternalFailure, "遍历回收站", err)
	}
	fileName := path.Base(e.Meta.OriginalPath)
	if !hasRestorableSameName(entries, e.ID, fileName) {
		// 永久删除前先同事务清理 TXT 作品行：失败则不删文件，条目保留可重试。
		if err := s.dropTrashWorks(context.Background(), e.Meta.AssetID, fileName); err != nil {
			return fail(InternalFailure, "清理作者TXT作品行", err)
		}
	}
	// stamp/<assetID>/ 子树里只有这一对文件+meta（一次删除一个文件），
	// 整树删除顺带清掉空目录壳。
	filing.InvalidateRestoreIntents(s.cfg.DataDir, e.Meta.LibraryID)
	defer filing.InvalidateRestoreIntents(s.cfg.DataDir, e.Meta.LibraryID)
	if err := os.RemoveAll(filepath.Dir(e.MetaFile)); err != nil {
		return fail(InternalFailure, "物理删除回收站条目", err)
	}
	// 条目永久消失：该资产缩略图缓存不再可达，联动清理（软删除→恢复
	// 路径不清——asset_id 不变恢复后继续命中缓存，见 thumbnail/cleanup.go）。
	filing.InvalidateRestoreIntents(s.cfg.DataDir, e.Meta.LibraryID)
	s.thumbs.DeleteAssetThumbs(e.Meta.AssetID)
	// 物理删除不走 publishLibraryChanged（条目早已出库，不发布库变更），
	// 修订号在此显式推进（revision.go 的 bump 链清单）。
	s.bumpLibraryRevision()
	s.logger.Info("回收站条目已物理删除", "id", e.ID, "originalPath", e.Meta.OriginalPath)
	// 库内文件数不变（条目早已出库），只刷回收站两 gauge（变更点推送）。
	s.RefreshTrashMetrics()
	return nil
}

// ClearTrash 清空回收站（物理删除全部条目）。
func (s *Service) ClearTrash() error {
	unlockTrash := filing.LockLibrary(trashOperationGate)
	defer unlockTrash()

	// meta 是回收站真相源：RemoveAll 之后无法再反查 assetID/原文件名，
	// TXT 作品行清理与缩略图联动都必须先收集。遍历失败不能降级：不知道
	// 要清哪些条目就物理删除会留下 TXT 污染，保留回收站等待重试。
	entries, err := s.ListTrash()
	if err != nil {
		return fail(InternalFailure, "遍历回收站", err)
	}
	if len(entries) > 0 {
		if s.conn == nil {
			return fail(InternalFailure, "清空回收站", errors.New("缺少数据库连接"))
		}
		tx, err := s.conn.BeginTx(context.Background(), nil)
		if err != nil {
			return fail(InternalFailure, "清空回收站", err)
		}
		defer s.rollback(tx, "清空回收站前清理作者TXT作品行")
		qtx := s.q.WithTx(tx)
		attacher := &authorattach.Service{Logger: s.logger}
		for _, e := range entries {
			if err := attacher.DropAssetWorks(context.Background(), qtx, s.now(), e.Meta.AssetID, path.Base(e.Meta.OriginalPath)); err != nil {
				return fail(InternalFailure, "清理作者TXT作品行", err)
			}
		}
		if err := tx.Commit(); err != nil {
			return fail(InternalFailure, "清理作者TXT作品行", err)
		}
	}
	filing.InvalidateAllRestoreIntents(s.cfg.DataDir)
	defer filing.InvalidateAllRestoreIntents(s.cfg.DataDir)
	root := filepath.Join(s.cfg.DataDir, filing.TrashRootName)
	if err := os.RemoveAll(root); err != nil {
		return fail(InternalFailure, "清空回收站", err)
	}
	if err := os.MkdirAll(root, dirPerm); err != nil {
		return fail(InternalFailure, "重建回收站目录", err)
	}
	filing.InvalidateAllRestoreIntents(s.cfg.DataDir)
	for _, e := range entries {
		s.thumbs.DeleteAssetThumbs(e.Meta.AssetID)
	}
	// 清空=全部条目物理删除：同单条物理删除，修订号显式推进。
	s.bumpLibraryRevision()
	s.logger.Info("回收站已清空")
	// 清空后回收站归零：gauge 显式 Set 回 0（变更点推送）。
	s.RefreshTrashMetrics()
	return nil
}
