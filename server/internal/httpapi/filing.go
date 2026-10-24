// filing.go 仅适配移动/改名协议；路径、文件、库行、回滚与富化编排归 mediawrite。
package httpapi

import (
	"database/sql"
	"errors"
	"net/http"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/mediawrite"
)

func (s *Server) PostApiV1AssetsAssetIdMove(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	// 保留旧端点校验优先级：不存在资产先 404，再检查 JSON；这里只作请求存在性校验。
	// 业务服务不信此快照，会在自身库锁内复核身份/路径/库根。
	if _, err := s.q.GetAssetWithLibrary(r.Context(), assetID.String()); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			writeErr(w, http.StatusNotFound, codeNotFound, "资产不存在")
			return
		}
		s.internalErr(w, "查询资产", err)
		return
	}
	var req gen.MoveRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	if err := s.writes.MoveAsset(r.Context(), assetID.String(), mediawrite.MoveRequest{TargetDirectory: req.TargetDir, NewName: req.NewName}); err != nil {
		s.writeMoveServiceError(w, err)
		return
	}
	w.WriteHeader(http.StatusOK)
}

func (s *Server) writeMoveServiceError(w http.ResponseWriter, err error) {
	var failure *mediawrite.Failure
	if !errors.As(err, &failure) {
		s.internalErr(w, "移动资产", err)
		return
	}
	switch failure.Kind {
	case mediawrite.AssetMissing:
		writeErr(w, http.StatusNotFound, codeNotFound, "资产不存在")
	case mediawrite.InvalidDirectory:
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "目标目录不合法")
	case mediawrite.InvalidFilename:
		writeErr(w, http.StatusBadRequest, codeInvalidFilename, "新文件名不合法")
	case mediawrite.MoveFileConflict:
		writeErr(w, http.StatusConflict, codeTargetExists, "目标位置已有同名文件")
	case mediawrite.MoveRecordConflict:
		writeErr(w, http.StatusConflict, codeTargetExists, "目标位置已被占用")
	case mediawrite.MovePathEscape:
		writeErr(w, http.StatusBadRequest, codePathEscape, "路径不合法")
	default:
		s.internalErr(w, "移动资产", err)
	}
}
