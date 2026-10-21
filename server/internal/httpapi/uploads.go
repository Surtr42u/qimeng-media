// uploads.go 适配会话协议与生命周期；创建/完成的多步业务归 mediawrite。
package httpapi

import (
	"context"
	"errors"
	"net/http"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/mediawrite"
	"qimeng-media/server/internal/safego"
	"qimeng-media/server/internal/uploadsess"
)

// PostApiV1Uploads 仅解码请求并调用会话创建编排。
func (s *Server) PostApiV1Uploads(w http.ResponseWriter, r *http.Request) {
	var req gen.CreateUploadRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	dir := ""
	if req.Dir != nil {
		dir = *req.Dir
	}
	snapshot, err := s.writes.CreateUploadSession(r.Context(), mediawrite.SessionUpload{LibraryID: req.LibraryId, Filename: req.FileName, Directory: dir, Size: req.Size})
	if err != nil {
		s.writeUploadServiceError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, uploadSessionToGen(snapshot))
}

// GetApiV1UploadsId 断点探测：客户端重试/续传前先问服务端"传到哪了"。
// 只读不续命——空轮询不能让弃传会话逃过过期清扫。
func (s *Server) GetApiV1UploadsId(w http.ResponseWriter, r *http.Request, id gen.UploadId) {
	snap, err := s.uploads.Snapshot(id.String())
	if errors.Is(err, uploadsess.ErrSessionNotFound) {
		writeErr(w, http.StatusNotFound, codeNotFound, "上传会话不存在或已过期")
		return
	}
	if err != nil {
		s.internalErr(w, "查询上传会话", err)
		return
	}
	writeJSON(w, http.StatusOK, uploadSessionToGen(snap))
}

// PatchApiV1UploadsId 追加分片：offset 必须等于服务端权威值（不等 409 回
// 权威 UploadSession）；单片上限/累计上限的语义映射见 uploadsess 哨兵错误。
// 磁盘写满/读流中断 → 500 且会话保留（客户端可整片重试，ADR-0028）。
func (s *Server) PatchApiV1UploadsId(w http.ResponseWriter, r *http.Request, id gen.UploadId, params gen.PatchApiV1UploadsIdParams) {
	if params.Offset < 0 {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "offset 不能为负")
		return
	}
	snap, err := s.uploads.Append(id.String(), params.Offset, r.Body)
	switch {
	case err == nil:
		writeJSON(w, http.StatusOK, uploadSessionToGen(snap))
	case errors.Is(err, uploadsess.ErrSessionNotFound):
		writeErr(w, http.StatusNotFound, codeNotFound, "上传会话不存在或已过期")
	case errors.Is(err, uploadsess.ErrOffsetMismatch):
		// tus 语义：409 响应体即权威状态，客户端重同步后重发本分片。
		// 会话刚被并发清扫出表的窗口按 500 兜底（下一轮探测即 404）。
		authSnap, gerr := s.uploads.Snapshot(id.String())
		if gerr != nil {
			s.internalErr(w, "查询上传会话", gerr)
			return
		}
		writeJSON(w, http.StatusConflict, uploadSessionToGen(authSnap))
	case errors.Is(err, uploadsess.ErrSizeOverflow):
		writeErr(w, http.StatusBadRequest, codeUploadTooLarge, "累计字节超出会话声明的 size")
	case errors.Is(err, uploadsess.ErrChunkTooLarge):
		writeErr(w, http.StatusRequestEntityTooLarge, codeUploadTooLarge, "单次分片超过上限")
	default:
		s.internalErr(w, "写入上传分片", err)
	}
}

// PostApiV1UploadsIdComplete 仅调用编排并装配签名响应。
func (s *Server) PostApiV1UploadsIdComplete(w http.ResponseWriter, r *http.Request, id gen.UploadId) {
	asset, err := s.writes.CompleteUploadSession(r.Context(), id.String())
	if err != nil {
		s.writeUploadServiceError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, s.assembleUploadDetail(asset))
}

// DeleteApiV1UploadsId 放弃会话：删临时文件与会话，不入库、不产生任何资产。
func (s *Server) DeleteApiV1UploadsId(w http.ResponseWriter, r *http.Request, id gen.UploadId) {
	if err := s.uploads.Drop(id.String()); err != nil {
		if errors.Is(err, uploadsess.ErrSessionNotFound) {
			writeErr(w, http.StatusNotFound, codeNotFound, "上传会话不存在或已过期")
			return
		}
		s.internalErr(w, "放弃上传会话", err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// uploadSessionToGen 把会话快照装配为协议 UploadSession 载荷。
func uploadSessionToGen(snap uploadsess.Snapshot) gen.UploadSession {
	// ID 由 uploadsess 经 uuid.NewString() 生成，MustParse 不可失败；
	// 协议 id 为 uuid 格式（openapi UploadSession.id format: uuid）。
	return gen.UploadSession{
		Id:     uuid.MustParse(snap.ID),
		Offset: snap.Offset,
		Size:   snap.Size,
	}
}

// StartUploadSweeper 组合根（main）调用一次：断点续传会话过期清扫 daemon，
// ctx 取消即退出（与 StartTrashSweeper 同款生命周期模式）。清扫不 bump
// 修订号——未产生任何资产（ADR-0028）。
func (s *Server) StartUploadSweeper(ctx context.Context) {
	safego.Go(s.logger, "上传会话清扫", func() {
		ticker := time.NewTicker(uploadsess.SweepInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				s.sweepUploadSessionsOnce()
			}
		}
	})
}

// sweepUploadSessionsOnce 一轮会话过期清扫（测试直接调用注入时钟验证）。
func (s *Server) sweepUploadSessionsOnce() {
	expired, orphans := s.uploads.SweepOnce()
	if expired > 0 {
		s.logger.Info("过期上传会话已回收", "sessions", expired, "orphanFiles", orphans)
		return
	}
	if orphans > 0 {
		s.logger.Info("孤儿分片临时文件已清理", "count", orphans)
	}
}

// CloseUploadSessions 优雅关停时清空在途会话与其临时分片文件（尽力而为，
// 失败只记日志：遗留半成品由下轮孤儿清理兜底）。
func (s *Server) CloseUploadSessions() {
	if err := s.uploads.Close(); err != nil {
		s.logger.Warn("关停清理上传会话失败（遗留分片由过期清扫兜底）", "err", err)
	}
}
