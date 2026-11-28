// ingest_local.go 只做协议适配；文件移动/复制、摘要复核与建索引由
// mediawrite.IngestLocal 编排（2026-11-28 本机直接入库批）。
package httpapi

import (
	"errors"
	"net/http"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/mediawrite"
)

// PostApiV1LibrariesLibraryIdIngestLocal 本机/局域网直接入库。
//
// 与 POST /assets/upload 的分工：本端点要求源文件已在本机（读根内），服务端
// 直接移动/复制并建索引，毫秒级完成；外网/手机/跨设备仍走流式 upload。
// 鉴权与其它管理端点一致（Bearer / AI 客户端 scope），不额外开免鉴权口。
func (s *Server) PostApiV1LibrariesLibraryIdIngestLocal(w http.ResponseWriter, r *http.Request, libraryID gen.LibraryId) {
	var req gen.PostApiV1LibrariesLibraryIdIngestLocalJSONRequestBody
	if !decodeJSON(w, r, &req) {
		return
	}
	// move 缺省 true（本机入库语义 = 移动）；显式 false 才保留源。
	move := true
	if req.Move != nil {
		move = *req.Move
	}
	dir := ""
	if req.Directory != nil {
		dir = *req.Directory
	}
	result, err := s.writes.IngestLocal(r.Context(), mediawrite.IngestLocalRequest{
		LibraryID:  libraryID,
		SourcePath: req.SourcePath,
		FileName:   req.FileName,
		Directory:  dir,
		Move:       move,
	})
	if err != nil {
		s.writeIngestLocalError(w, err)
		return
	}
	status := http.StatusCreated
	if result.Status == mediawrite.IngestLocalStatusPresent {
		status = http.StatusOK
	}
	writeJSON(w, status, gen.IngestLocalResult{
		Status:     gen.IngestLocalResultStatus(result.Status),
		AssetId:    result.AssetID,
		LibraryId:  result.LibraryID,
		RelPath:    result.RelPath,
		FileName:   result.FileName,
		MediaType:  gen.IngestLocalResultMediaType(result.MediaType),
		SizeBytes:  result.SizeBytes,
		Sha256:     optionalString(result.SHA256),
		SourceKept: result.SourceKept,
	})
}

// writeIngestLocalError 把本机入库的服务层错误映射为协议响应；内部路径与
// 底层错误只进日志（SECURITY 红线 7 信息泄露）。
func (s *Server) writeIngestLocalError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, mediawrite.ErrIngestLocalDisabled):
		writeErr(w, http.StatusForbidden, codePathEscape, "服务端未配置本机入库读根（ingest_local.read_roots）")
		return
	case errors.Is(err, mediawrite.ErrIngestSourceOutsideRoots):
		writeErr(w, http.StatusForbidden, codePathEscape, "源路径不在授权读根内")
		return
	case errors.Is(err, mediawrite.ErrIngestSourceMissing):
		writeErr(w, http.StatusNotFound, codeFileMissing, "源文件不存在")
		return
	case errors.Is(err, mediawrite.ErrIngestSourceNotRegular):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "源必须是普通文件（不接受目录/符号链接）")
		return
	case errors.Is(err, mediawrite.ErrIngestTargetExists),
		errors.Is(err, filing.ErrMoveTargetExists):
		writeErr(w, http.StatusConflict, codeConflict, "目标已存在且内容不同，未覆盖（请改名或先处理既有文件）")
		return
	case errors.Is(err, mediawrite.ErrTargetEscape):
		writeErr(w, http.StatusBadRequest, codePathEscape, "目标路径不合法")
		return
	case errors.Is(err, filing.ErrUploadTooLarge):
		writeErr(w, http.StatusRequestEntityTooLarge, codeUploadTooLarge, "文件超过大小上限")
		return
	case errors.Is(err, filing.ErrUploadExtension):
		writeErr(w, http.StatusBadRequest, codeInvalidExtension, "扩展名不在白名单")
		return
	case errors.Is(err, filing.ErrUploadMimeMismatch):
		writeErr(w, http.StatusBadRequest, codeMimeMismatch, "文件内容与扩展名不符")
		return
	case errors.Is(err, filing.ErrUploadFilename):
		writeErr(w, http.StatusBadRequest, codeInvalidFilename, "文件名不合法")
		return
	}
	var failure *mediawrite.Failure
	if errors.As(err, &failure) {
		switch failure.Kind {
		case mediawrite.InvalidLibrary:
			writeErr(w, http.StatusNotFound, codeLibraryNotFound, "libraryId 须指向已注册库")
			return
		case mediawrite.InvalidDirectory:
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "目标目录不合法")
			return
		case mediawrite.StorageUnavailable:
			writeErr(w, http.StatusInsufficientStorage, codeStorageUnavailable, "存储目录不可用或剩余空间不足，请检查存储后重试")
			return
		}
	}
	s.internalErr(w, "本机直接入库", err)
}

// optionalString 空串 → nil（协议可选字段缺省，避免下发空 sha256）。
func optionalString(v string) *string {
	if v == "" {
		return nil
	}
	return &v
}
