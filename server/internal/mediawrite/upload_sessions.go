package mediawrite

import (
	"context"
	"database/sql"
	"errors"
	"os"

	"github.com/google/uuid"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/scanner"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/sysmon"
	"qimeng-media/server/internal/uploadsess"
)

type SessionUpload struct {
	UploadID                       string
	SourceMtime                    *int64
	LibraryID, Filename, Directory string
	Size                           int64
}

// CreateUploadSession 的前置校验在创建暂存文件之前完成，避免坏请求占用会话。
func (s *Service) CreateUploadSession(ctx context.Context, input SessionUpload) (uploadsess.Snapshot, error) {
	empty := uploadsess.Snapshot{}
	if err := validateUploadMetadata(input.UploadID, input.SourceMtime); err != nil {
		return empty, err
	}
	if input.UploadID == "" {
		input.UploadID = uuid.NewString()
	}
	unlock := s.lockUpload(input.UploadID)
	defer unlock()
	hash := uploadRequestHash("chunked", input.LibraryID, input.Filename, input.Directory, input.Size, input.SourceMtime)
	if r, err := s.uploadReceipt(ctx, input.UploadID); err == nil {
		if r.RequestHash != hash {
			return empty, ErrUploadConflict
		}
		return uploadsess.Snapshot{ID: input.UploadID, Offset: r.SizeBytes, Size: r.SizeBytes}, nil
	} else if !errors.Is(err, sql.ErrNoRows) {
		return empty, err
	}
	name, _, err := uploadFilename(input.Filename)
	if err != nil {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		return empty, err
	}
	dir, err := normalizeUploadDir(input.Directory)
	if err != nil {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		return empty, err
	}
	if input.LibraryID == "" || input.Size < 1 {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		return empty, fail(InvalidSessionInput, "libraryId 与 size 必填", nil)
	}
	lib, err := s.q.GetLibrary(ctx, input.LibraryID)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && lib.Enabled != 1) {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		return empty, fail(ActiveLibraryRequired, "查询库", err)
	}
	if err != nil {
		return empty, fail(InternalFailure, "查询库", err)
	}
	policy := s.UploadPolicy(ctx)
	if policy.Disabled {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		return empty, fail(UploadDisabled, "上传已关闭", nil)
	}
	if input.Size > policy.MaxBytes {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		return empty, filing.ErrUploadTooLarge
	}
	// 复用不新增空间预留；不能因已传入的字节占盘而阻挡创建响应丢失后的重试。
	if snapshot, found, err := s.uploads.Reuse(input.UploadID, hash); found || err != nil {
		return snapshot, err
	}
	if err := os.MkdirAll(s.cfg.DataDir, 0o700); err != nil {
		return empty, fail(StorageUnavailable, "创建上传数据目录", err)
	}
	if err := s.checkUploadStorage(ctx, s.cfg.DataDir, input.Size); err != nil {
		return empty, err
	}
	if err := s.checkUploadStorage(ctx, lib.RootPath, input.Size); err != nil {
		return empty, err
	}
	snapshot, err := s.uploads.CreateWithMetadata(lib.ID, name, dir, input.Size, uploadsess.Metadata{ID: input.UploadID, RequestHash: hash, SourceMtime: input.SourceMtime})
	if err != nil {
		return empty, fail(InternalFailure, "创建上传会话", err)
	}
	return snapshot, nil
}

// CompleteUploadSession 独占完成流程，失败保留已封存源和已发布落点，成功才释放会话。
func (s *Service) CompleteUploadSession(ctx context.Context, id string) (db.Asset, error) {
	requestUnlock := s.lockUpload(id)
	defer requestUnlock()
	if asset, found, err := s.replayUpload(ctx, id, ""); found || err != nil {
		return asset, err
	}
	unlock, err := s.uploads.LockCompletion(id)
	if err != nil {
		return db.Asset{}, err
	}
	defer unlock()
	if err := s.uploads.Touch(id); err != nil {
		return db.Asset{}, fail(InternalFailure, "查询上传会话", err)
	}
	seal, err := s.uploads.Seal(id)
	if err != nil {
		return db.Asset{}, fail(InternalFailure, "终结上传会话", err)
	}
	policy := s.UploadPolicy(ctx)
	if policy.Disabled {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		return db.Asset{}, fail(UploadDisabled, "上传已关闭", nil)
	}
	head, err := ReadUploadHead(seal.Path)
	if err != nil {
		return db.Asset{}, fail(InternalFailure, "读取分片文件头", err)
	}
	if err := filing.ValidateUpload(seal.FileName, seal.Size, policy.MaxBytes, head); err != nil {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		return db.Asset{}, err
	}
	lib, err := s.q.GetLibrary(ctx, seal.LibraryID)
	if errors.Is(err, sql.ErrNoRows) {
		return db.Asset{}, fail(LibraryGone, "查询库", err)
	}
	if err != nil {
		return db.Asset{}, fail(InternalFailure, "查询库", err)
	}
	mediaType, ok := scanner.ClassifyMedia(seal.FileName)
	if !ok {
		sysmon.Default.IncUpload(sysmon.UploadFail)
		return db.Asset{}, filing.ErrUploadExtension
	}
	if err := s.checkUploadStorage(ctx, s.cfg.DataDir, 0); err != nil {
		return db.Asset{}, err
	}
	if err := s.checkUploadStorage(ctx, lib.RootPath, 0); err != nil {
		return db.Asset{}, err
	}
	name, rel, absolute, err := s.PlaceSealed(lib, seal)
	if err != nil {
		if errors.Is(err, ErrSealedSizeMismatch) {
			sysmon.Default.IncUpload(sysmon.UploadFail)
		}
		return db.Asset{}, fail(InternalFailure, "落盘分片成品文件", err)
	}
	receipt := &db.SaveUploadReceiptParams{UploadID: id, RequestHash: seal.RequestHash}
	asset, err := s.ingestPlacedUpload(ctx, lib.ID, absolute, rel, name, mediaType, receipt)
	if err != nil {
		return db.Asset{}, fail(InternalFailure, "入库上传资产", err)
	}
	if err := s.uploads.Finish(id); err != nil && !errors.Is(err, uploadsess.ErrSessionNotFound) {
		s.logger.Warn("清理已完成上传会话失败", "id", id, "err", err)
	}
	return asset, nil
}

func uploadFilename(raw string) (string, string, error) {
	name, err := filing.SanitizeFilename(raw)
	if err != nil {
		return "", "", filing.ErrUploadFilename
	}
	mediaType, ok := scanner.ClassifyMedia(name)
	if !ok {
		return "", "", filing.ErrUploadExtension
	}
	return name, mediaType, nil
}
