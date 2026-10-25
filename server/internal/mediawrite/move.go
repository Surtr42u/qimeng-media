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
	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// MoveRequest 是整理意图；空目标目录为库根，未给/空新名保留原名。
type MoveRequest struct {
	TargetDirectory string
	NewName         *string
}

// MoveAsset 保留资产身份/关联，只改路径属性；先文件后库行，提交失败尝试无覆盖回滚。
func (s *Service) MoveAsset(ctx context.Context, assetID string, req MoveRequest) error {
	row, err := s.q.GetAssetWithLibrary(ctx, assetID)
	if errors.Is(err, sql.ErrNoRows) {
		return fail(AssetMissing, "资产不存在", nil)
	}
	if err != nil {
		return fail(InternalFailure, "查询资产", err)
	}
	dir := req.TargetDirectory
	if dir != "" {
		dir, err = filing.NormalizeRelPath(dir)
		if err != nil {
			return fail(InvalidDirectory, "目标目录不合法", err)
		}
	}
	name := path.Base(row.RelPath)
	if req.NewName != nil && *req.NewName != "" {
		name, err = filing.SanitizeFilename(*req.NewName)
		if err != nil {
			return fail(InvalidFilename, "新文件名不合法", err)
		}
	}
	newRel := path.Join(dir, name)
	if newRel == row.RelPath {
		return nil
	} // 幂等请求不更新时刻/富化/广播。
	if err := filing.WithLibraryGate(row.LibraryID, func() error {
		return s.commitMove(ctx, row, newRel, name)
	}); err != nil {
		return err
	}
	// 改名/移动的 size+mtime 不变，扫描不会自然重 ingest，必须显式重算富化。
	if err := s.enrichAsset(ctx, row.LibraryID, row.AssetID); err != nil {
		s.logger.Warn("移动后重算出处失败（可重扫自愈）", "err", err, "assetId", row.AssetID)
	}
	s.PublishLibraryChanged(events.LibraryChangeScope{LibraryID: row.LibraryID, AssetID: row.AssetID})
	return nil
}

// commitMove 在库锁内实时复核身份/根/路径和目标占用，完成文件+库行或尝试回滚。
func (s *Service) commitMove(ctx context.Context, row db.GetAssetWithLibraryRow, newRel, name string) error {
	current, err := s.q.GetAssetWithLibrary(ctx, row.AssetID)
	if errors.Is(err, sql.ErrNoRows) {
		return fail(AssetMissing, "资产不存在", nil)
	}
	if err != nil {
		return fail(InternalFailure, "复核移动资产", err)
	}
	if current.LibraryID != row.LibraryID || current.RelPath != row.RelPath || current.RootPath != row.RootPath {
		return fail(MoveRecordConflict, "资产位置已经变化", nil)
	}
	source := filepath.Join(row.RootPath, filepath.FromSlash(row.RelPath))
	target := filepath.Join(row.RootPath, filepath.FromSlash(newRel))
	if !filing.PathWithinRoot(row.RootPath, source) || !filing.PathWithinRoot(row.RootPath, target) {
		return fail(MovePathEscape, "移动路径不合法", nil)
	}
	root, err := os.OpenRoot(row.RootPath)
	if err != nil {
		return fail(InternalFailure, "打开媒体库", err)
	}
	defer filing.CloseRoot(root)
	if err := s.checkMoveTarget(ctx, root, row.LibraryID, newRel); err != nil {
		return err
	}
	if err := root.MkdirAll(filepath.FromSlash(path.Dir(newRel)), dirPerm); err != nil {
		return fail(InternalFailure, "创建目标目录", err)
	}
	sourceRel, targetRel := filepath.FromSlash(row.RelPath), filepath.FromSlash(newRel)
	if err := s.renameFile(root, sourceRel, targetRel); err != nil {
		if errors.Is(err, filing.ErrMoveTargetExists) {
			return fail(MoveFileConflict, "目标位置已有同名文件", err)
		}
		return fail(InternalFailure, "移动文件", err)
	}
	if _, err := s.q.MoveAssetPath(ctx, db.MoveAssetPathParams{
		RelPath: newRel, FileName: name, UpdatedAt: store.FormatTimestamp(s.now()), AssetID: row.AssetID,
	}); err != nil {
		if rollbackErr := s.renameFile(root, targetRel, sourceRel); rollbackErr != nil {
			// 新落点可能是唯一原件，原路径也可能已有外部新文件；保留两者，绝不清理或覆盖。
			s.logger.Error("更新资产路径失败且回滚失败（原件保留，需恢复核对）",
				"err", err, "rollbackErr", rollbackErr, "assetId", row.AssetID)
			return fail(InternalFailure, "更新资产路径并回滚", errors.Join(err, fmt.Errorf("回滚移动: %w", rollbackErr)))
		}
		return fail(InternalFailure, "更新资产路径", err)
	}
	return nil
}

func (s *Service) checkMoveTarget(ctx context.Context, root *os.Root, libraryID, rel string) error {
	if _, err := root.Lstat(filepath.FromSlash(rel)); err == nil {
		return fail(MoveFileConflict, "目标位置已有同名文件", nil)
	} else if !errors.Is(err, os.ErrNotExist) {
		return fail(InternalFailure, "检查移动目标", err)
	}
	if _, err := s.q.GetAssetByPath(ctx, db.GetAssetByPathParams{LibraryID: libraryID, RelPath: rel}); err == nil {
		return fail(MoveRecordConflict, "目标位置已被占用", nil)
	} else if !errors.Is(err, sql.ErrNoRows) {
		return fail(InternalFailure, "检查目标资产占用", err)
	}
	return nil
}
