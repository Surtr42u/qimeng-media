package mediawrite

import (
	"context"
	"database/sql"
	"errors"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/scanner"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/sysmon"
	"qimeng-media/server/internal/uploadsess"
)

type SessionUpload struct {
	LibraryID, Filename, Directory string
	Size                           int64
}

// CreateUploadSession 的前置校验在创建暂存文件之前完成，避免坏请求占用会话。
func (s *Service) CreateUploadSession(ctx context.Context, input SessionUpload) (uploadsess.Snapshot, error) {
	empty := uploadsess.Snapshot{}
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
	snapshot, err := s.uploads.Create(lib.ID, name, dir, input.Size)
	if err != nil {
		return empty, fail(InternalFailure, "创建上传会话", err)
	}
	return snapshot, nil
}

// CompleteUploadSession 独占完成流程，失败保留已封存源和已发布落点，成功才释放会话。
func (s *Service) CompleteUploadSession(ctx context.Context, id string) (db.Asset, error) {
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
	name, rel, absolute, err := s.PlaceSealed(lib, seal)
	if err != nil {
		if errors.Is(err, ErrSealedSizeMismatch) {
			sysmon.Default.IncUpload(sysmon.UploadFail)
		}
		return db.Asset{}, fail(InternalFailure, "落盘分片成品文件", err)
	}
	asset, err := s.IngestPlacedUpload(ctx, lib.ID, absolute, rel, name, mediaType)
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
