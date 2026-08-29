// dirs.go：目录树端点（文件整理用：上传选目标目录、移动选目标目录）。
// 目录是文件系统的现实（不是库表视图）——空目录在库表里无痕迹但
// 整理时必须可选它，所以遍历磁盘而非聚合 assets 表。
package httpapi

import (
	"database/sql"
	"errors"
	"net/http"
	"os"
	"path/filepath"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/httpapi/gen"
)

// GetApiV1Dirs 目录树。libraryId 业务必填（协议未标 required 但
// 目录树必须锚定单一库根，缺省无明确语义），缺失 400、库不存在 404。
func (s *Server) GetApiV1Dirs(w http.ResponseWriter, r *http.Request, params gen.GetApiV1DirsParams) {
	if params.LibraryId == nil || *params.LibraryId == "" {
		writeErr(w, http.StatusBadRequest, "INVALID_PARAM", "libraryId 必填（目录树锚定单一库）")
		return
	}
	lib, err := s.q.GetLibrary(r.Context(), *params.LibraryId)
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, "NOT_FOUND", "库不存在")
		return
	}
	if err != nil {
		s.internalErr(w, "查询库", err)
		return
	}
	writeJSON(w, http.StatusOK, buildDirTree(lib.RootPath, ""))
}

// buildDirTree 递归组装 DirTree。fileCount = 该目录直接子文件数
// （不递归——"进这个目录有多少文件"，子目录的计数在各自节点上）。
// 路径用 '/' 分隔（协议层统一 slash 形态，与 assets.rel_path 一致）。
// 单个子目录读失败跳过（权限/并发删除）：树是浏览辅助，不因一处
// 抖动整体 500。
func buildDirTree(root, rel string) gen.DirTree {
	abs := filepath.Join(root, filepath.FromSlash(rel))
	entries, err := os.ReadDir(abs)
	if err != nil {
		p := rel
		fc := 0
		return gen.DirTree{Path: &p, FileCount: &fc}
	}
	dirs := make([]gen.DirTree, 0, len(entries))
	fileCount := 0
	for _, e := range entries {
		if e.IsDir() {
			child := e.Name()
			if rel != "" {
				child = rel + "/" + e.Name()
			}
			dirs = append(dirs, buildDirTree(root, child))
			continue
		}
		fileCount++
	}
	p, fc := rel, fileCount
	return gen.DirTree{Path: &p, Dirs: &dirs, FileCount: &fc}
}

// PostApiV1Dirs 新建目录（库内任意层级，DOMAIN_RULES §9）。
// 幂等语义：目录已存在视为成功（MkdirAll），重复点击无害。
func (s *Server) PostApiV1Dirs(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Path      string `json:"path"`
		LibraryID string `json:"libraryId"`
	}
	if !decodeJSON(w, r, &req) {
		return
	}
	if req.LibraryID == "" {
		writeErr(w, http.StatusBadRequest, "INVALID_PARAM", "libraryId 必填")
		return
	}
	lib, err := s.q.GetLibrary(r.Context(), req.LibraryID)
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, "NOT_FOUND", "库不存在")
		return
	}
	if err != nil {
		s.internalErr(w, "查询库", err)
		return
	}
	rel, err := filing.NormalizeRelPath(req.Path)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "INVALID_PARAM", "目录路径不合法")
		return
	}
	if err := os.MkdirAll(filepath.Join(lib.RootPath, filepath.FromSlash(rel)), 0o755); err != nil {
		s.internalErr(w, "创建目录", err)
		return
	}
	w.WriteHeader(http.StatusCreated)
}
