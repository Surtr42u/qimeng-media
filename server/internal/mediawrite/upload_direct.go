package mediawrite

import (
	"context"
	"database/sql"
	"errors"
	"io"
	"os"
	"path"
	"path/filepath"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/sysmon"
)

type DirectUpload struct {
	LibraryID, Directory, Filename string
	ContentLength                  int64
	Body                           io.Reader
}

// Direct 校验、收流、冲突解析、发布、入库和通知；长时间收流在库锁外。
// 文件发布成功后入库失败不擅自删原件，保留既有重扫/重试恢复边界。
func (s *Service) Direct(ctx context.Context, input DirectUpload) (db.Asset, error) {
	name, mediaType, err := uploadFilename(input.Filename)
	if err != nil {
		return db.Asset{}, err
	}
	dir, err := normalizeUploadDir(input.Directory)
	if err != nil {
		return db.Asset{}, err
	}
	lib, err := s.q.GetLibrary(ctx, input.LibraryID)
	if errors.Is(err, sql.ErrNoRows) || input.LibraryID == "" {
		return db.Asset{}, fail(InvalidLibrary, "查询库", err)
	}
	if err != nil {
		return db.Asset{}, fail(InternalFailure, "查询库", err)
	}
	policy := s.UploadPolicy(ctx)
	if policy.Disabled {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		return db.Asset{}, fail(UploadDisabled, "上传已关闭", nil)
	}
	if input.ContentLength > policy.MaxBytes {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		return db.Asset{}, filing.ErrUploadTooLarge
	}
	base := filepath.Join(lib.RootPath, filepath.FromSlash(dir))
	if !filing.PathWithinRoot(lib.RootPath, base) {
		return db.Asset{}, ErrTargetEscape
	}
	root, err := os.OpenRoot(lib.RootPath)
	if err != nil {
		return db.Asset{}, fail(InternalFailure, "打开媒体库根", err)
	}
	defer filing.CloseRoot(root)
	if err := root.MkdirAll(filepath.FromSlash(path.Join(".", dir)), dirPerm); err != nil {
		return db.Asset{}, fail(InternalFailure, "创建目标目录", err)
	}
	tmp, err := s.receiveUploadToRoot(root, dir, input.Filename, input.Body, input.ContentLength, policy.MaxBytes)
	if err != nil {
		return db.Asset{}, err
	}
	tmpRel := filepath.FromSlash(path.Join(dir, filepath.Base(tmp)))
	defer s.cleanStaging(root, tmpRel)()
	var finalName, targetRel string
	err = filing.WithLibraryGate(lib.ID, func() error {
		finalName = filing.ResolveConflict(name, func(n string) bool { _, err := os.Stat(filepath.Join(base, n)); return err == nil })
		targetRel = path.Join(dir, finalName)
		if !filing.PathWithinRoot(lib.RootPath, filepath.Join(lib.RootPath, filepath.FromSlash(targetRel))) {
			return ErrTargetEscape
		}
		return root.Rename(tmpRel, filepath.FromSlash(targetRel))
	})
	if err != nil {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		return db.Asset{}, fail(InternalFailure, "落盘上传文件", err)
	}
	asset, err := s.IngestPlacedUpload(ctx, lib.ID, filepath.Join(lib.RootPath, filepath.FromSlash(targetRel)), targetRel, finalName, mediaType)
	if err != nil {
		return db.Asset{}, fail(InternalFailure, "入库上传资产", err)
	}
	return asset, nil
}

func normalizeUploadDir(raw string) (string, error) {
	if raw == "" {
		return "", nil
	}
	dir, err := filing.NormalizeRelPath(raw)
	if err != nil {
		return "", fail(InvalidDirectory, "目标目录不合法", err)
	}
	return dir, nil
}
