// trash.go 仅适配回收站协议；文件/meta/事务/通知和清扫归 mediawrite。
package httpapi

import (
	"errors"
	"net/http"
	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/mediawrite"
)

// 隔离 HTTP 测试的移动故障注入；生产 main 的 mediawrite 使用真实 MoveFile。
var moveFile = filing.MoveFile

func (s *Server) publishLibraryChanged() { s.writes.PublishLibraryChanged() }

func (s *Server) DeleteApiV1AssetsAssetId(w http.ResponseWriter, r *http.Request, id gen.AssetId) {
	if err := s.writes.MoveToTrash(r.Context(), id.String()); err != nil {
		s.writeTrashServiceError(w, err)
		return
	}
	w.WriteHeader(http.StatusOK)
}
func (s *Server) GetApiV1Trash(w http.ResponseWriter, r *http.Request) {
	entries, err := s.writes.TrashItems()
	if err != nil {
		s.internalErr(w, "遍历回收站", err)
		return
	}
	items := make([]gen.TrashItem, 0, len(entries))
	for _, entry := range entries {
		items = append(items, gen.TrashItem{Id: ptr(entry.ID), FileName: ptr(entry.Filename), OriginalPath: ptr(entry.OriginalPath), DeletedAt: ptr(entry.DeletedAt), SizeBytes: ptr(entry.SizeBytes), ExpiresAt: ptr(entry.ExpiresAt)})
	}
	writeJSON(w, http.StatusOK, items)
}
func (s *Server) PostApiV1TrashTrashIdRestore(w http.ResponseWriter, r *http.Request, id gen.TrashId) {
	if err := s.writes.RestoreTrash(r.Context(), id); err != nil {
		s.writeTrashServiceError(w, err)
		return
	}
	w.WriteHeader(http.StatusOK)
}
func (s *Server) DeleteApiV1TrashTrashId(w http.ResponseWriter, r *http.Request, id gen.TrashId) {
	if err := s.writes.PurgeTrash(id); err != nil {
		s.writeTrashServiceError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
func (s *Server) DeleteApiV1Trash(w http.ResponseWriter, r *http.Request) {
	if err := s.writes.ClearTrash(); err != nil {
		s.writeTrashServiceError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) writeTrashServiceError(w http.ResponseWriter, err error) {
	if errors.Is(err, mediawrite.ErrRestoreEscaped) {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "路径不合法")
		return
	}
	var failure *mediawrite.Failure
	if !errors.As(err, &failure) {
		s.internalErr(w, "回收站处理", err)
		return
	}
	switch failure.Kind {
	case mediawrite.AssetMissing:
		writeErr(w, http.StatusNotFound, codeNotFound, "资产不存在")
	case mediawrite.MediaFileMissing:
		writeErr(w, http.StatusNotFound, codeFileMissing, "库内文件不存在（可能已被外部移动）")
	case mediawrite.InvalidAssetPath:
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "路径不合法")
	case mediawrite.InvalidTrashPath:
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "资产路径不合法")
	case mediawrite.TrashMissing:
		writeErr(w, http.StatusNotFound, codeNotFound, "回收站条目不存在")
	case mediawrite.OriginalLibraryGone:
		writeErr(w, http.StatusNotFound, codeNotFound, "原库已不存在，无法恢复")
	case mediawrite.InvalidTrashMeta:
		writeErr(w, http.StatusBadRequest, codeInvalidMeta, "回收站元数据不合法，无法恢复")
	default:
		s.internalErr(w, failure.Op, failure.Err)
	}
}
