// upload.go 只做协议适配；文件、事务、富化和事件由 mediawrite 编排。
package httpapi

import (
	"database/sql"
	"errors"
	"net/http"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/mediawrite"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/uploadsess"
)

func (s *Server) PostApiV1AssetsUpload(w http.ResponseWriter, r *http.Request, params gen.PostApiV1AssetsUploadParams) {
	id := ""
	if params.UploadId != nil {
		id = params.UploadId.String()
	}
	asset, err := s.writes.Direct(r.Context(), mediawrite.DirectUpload{LibraryID: params.LibraryId,
		Directory: params.Dir, Filename: params.Filename, ContentLength: r.ContentLength, Body: r.Body, UploadID: id, SourceMtime: params.SourceMtime})
	if err != nil {
		s.writeUploadServiceError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, s.assembleUploadDetail(asset))
}

// assembleUploadDetail 装配两条上传通道 201 响应的 AssetDetail：新上传资产
// 没有标签/作者/统计等关联数据，只填基础字段 + 签名直链（与详情端点同一
// buildSummary 口径组装基础部分），客户端拿到最终 relPath 无需再发一次详情。
func (s *Server) assembleUploadDetail(asset db.Asset) gen.AssetDetail {
	base := buildSummary(s, asset.AssetID, asset.FileName, asset.MediaType, asset.SizeBytes,
		asset.Mtime, asset.CreatedAt, sql.NullString{}, false, 0, nil, nil)
	detail := gen.AssetDetail{
		AddedAt:    base.AddedAt,
		FileName:   base.FileName,
		Id:         base.Id,
		IsFavorite: base.IsFavorite,
		LikeCount:  base.LikeCount,
		MediaType:  base.MediaType,
		ModifiedAt: base.ModifiedAt,
		SizeBytes:  base.SizeBytes,
		Source:     base.Source,
		ThumbUrl:   base.ThumbUrl,
		Directory:  ptr(dirOf(asset.RelPath)),
		RelPath:    ptr(asset.RelPath),
		ViewCount:  ptr(0),
		PlayCount:  ptr(0),
		Characters: ptr([]string{}),
		Tags:       ptr([]gen.AssetDetailTag{}),
		Authors:    ptr([]gen.AssetDetailAuthor{}),
	}
	orig := s.signedMediaURL(mediaPathOrig + asset.AssetID)
	detail.OrigUrl = &orig
	if asset.DurationMs.Valid {
		detail.DurationMs = ptr(asset.DurationMs.Int64)
	}
	if asset.Width.Valid {
		detail.Width = ptr(int(asset.Width.Int64))
	}
	if asset.Height.Valid {
		detail.Height = ptr(int(asset.Height.Int64))
	}
	return detail
}

// writeUploadValidationError 把四道校验哨兵错误映射为协议响应（直传收流
// 与分片续传 complete 终检两处共用，映射口径单一来源）。
func writeUploadValidationError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, filing.ErrUploadTooLarge):
		writeErr(w, http.StatusRequestEntityTooLarge, codeUploadTooLarge, "文件超过大小上限")
	case errors.Is(err, filing.ErrUploadExtension):
		writeErr(w, http.StatusBadRequest, codeInvalidExtension, "扩展名不在白名单")
	case errors.Is(err, filing.ErrUploadMimeMismatch):
		writeErr(w, http.StatusBadRequest, codeMimeMismatch, "文件内容与扩展名不符")
	default: // ErrUploadFilename
		writeErr(w, http.StatusBadRequest, codeInvalidFilename, "文件名不合法")
	}
}

// writeUploadErr 把读流错误映射为协议响应：超限 413、其余 400。
func writeUploadErr(w http.ResponseWriter, err error) {
	var mbe *http.MaxBytesError
	if errors.As(err, &mbe) {
		writeErr(w, http.StatusRequestEntityTooLarge, codeUploadTooLarge, "文件超过大小上限")
		return
	}
	writeErr(w, http.StatusBadRequest, codeInvalidBody, "上传流读取失败")
}

// 业务失败在这里一次映射为 HTTP；诊断 Op/底层路径只进入 internalErr 日志。
func (s *Server) writeUploadServiceError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, mediawrite.ErrUploadMetadata):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "上传任务 ID 或源修改时间不合法")
		return
	case errors.Is(err, mediawrite.ErrUploadConflict), errors.Is(err, uploadsess.ErrSessionConflict):
		writeErr(w, http.StatusConflict, codeUploadIDConflict, "上传任务 ID 已用于不同参数，请重新入队")
		return
	case errors.Is(err, mediawrite.ErrUploadedAssetGone):
		writeErr(w, http.StatusGone, codeUploadAssetGone, "上传已完成，但原资产已删除；不会自动重新上传")
		return
	case errors.Is(err, uploadsess.ErrCapacity):
		writeErr(w, http.StatusTooManyRequests, codeUploadCapacity, "在途上传数量或暂存总容量已达上限，请完成或取消已有上传后重试")
		return
	case errors.Is(err, mediawrite.ErrTargetEscape):
		writeErr(w, http.StatusBadRequest, codePathEscape, "目标路径不合法")
		return
	case errors.Is(err, mediawrite.ErrSealedSizeMismatch):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "分片成品与会话声明大小不符，请放弃本会话重新上传")
		return
	case errors.Is(err, uploadsess.ErrSessionNotFound):
		writeErr(w, http.StatusNotFound, codeNotFound, "上传会话不存在或已过期")
		return
	case errors.Is(err, uploadsess.ErrNotComplete):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "分片未传完（offset 未达 size）")
		return
	case errors.Is(err, filing.ErrUploadTooLarge), errors.Is(err, filing.ErrUploadExtension), errors.Is(err, filing.ErrUploadMimeMismatch), errors.Is(err, filing.ErrUploadFilename):
		writeUploadValidationError(w, err)
		return
	}
	var failure *mediawrite.Failure
	if !errors.As(err, &failure) {
		s.internalErr(w, "上传处理", err)
		return
	}
	switch failure.Kind {
	case mediawrite.StorageUnavailable:
		writeErr(w, http.StatusInsufficientStorage, codeStorageUnavailable, "存储目录不可用或剩余空间不足，请检查存储后重试")
	case mediawrite.InvalidDirectory:
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "目标目录不合法")
	case mediawrite.InvalidLibrary:
		writeErr(w, http.StatusBadRequest, codeLibraryNotFound, "libraryId 必填且指向已注册库")
	case mediawrite.ActiveLibraryRequired:
		writeErr(w, http.StatusBadRequest, codeLibraryNotFound, "libraryId 须指向已注册且启用的库")
	case mediawrite.InvalidSessionInput:
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "libraryId 与 size 必填（size ≥ 1）")
	case mediawrite.LibraryGone:
		writeErr(w, http.StatusNotFound, codeNotFound, "上传会话所属库不存在")
	case mediawrite.UploadDisabled:
		writeErr(w, http.StatusForbidden, codeUploadDisabled, "上传已被关闭（设置页自动接收上传开关）")
	case mediawrite.ReadStreamFailure:
		writeUploadErr(w, failure.Err)
	default:
		s.internalErr(w, failure.Op, failure.Err)
	}
}
