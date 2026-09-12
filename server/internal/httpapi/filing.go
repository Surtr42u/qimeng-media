// filing.go：移动/重命名端点（ADR-0004 身份机制的正面应用——
// 移动=改路径属性，asset_id/created_at/全部关联数据零改动）。
// 路径安全校验统一走 filing 包（本包存在的意义，见 filing doc.go）。
package httpapi

import (
	"database/sql"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path"
	"path/filepath"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// move 关键段闭包内各失败分支的 sentinel：HTTP 响应统一在锁外写，
// 闭包内以错误类型表达原有分支（409 双查 / 400 越界），避免闭包内外
// 两处 WriteHeader 路径。500 级失败带阶段前缀原样上抛。
var (
	errMoveFsConflict = errors.New("move target exists on filesystem")
	errMoveDbConflict = errors.New("move target occupied by asset row")
	errMoveEscaped    = errors.New("move path escapes library root")
)

// PostApiV1AssetsAssetIdMove 移动/重命名。
//
// 冲突语义（协议 409）：目标位置已有同名文件（文件系统或库行任一存在）
// 即拒绝——移动不覆盖，覆盖属删除+移动的组合操作，必须显式分步执行。
//
// 顺序约束：先动文件后改库行。中途失败的最坏情形是"文件已挪、行还指
// 旧路径"——扫描器的移动合并启发式（size+mtime 一致）会把行修正过来；
// 反过来"行指新路径、文件在旧位置"则成为对账黑洞。
func (s *Server) PostApiV1AssetsAssetIdMove(w http.ResponseWriter, r *http.Request, assetID gen.AssetId) {
	row, err := s.q.GetAssetWithLibrary(r.Context(), assetID.String())
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, codeNotFound, "资产不存在")
		return
	}
	if err != nil {
		s.internalErr(w, "查询资产", err)
		return
	}
	var req gen.MoveRequest
	if !decodeJSON(w, r, &req) {
		return
	}
	// 目标目录：空 = 库根（目录语义，与"资产路径必须非空"的
	// NormalizeRelPath 语义不同——空串是合法目录值）；非空则过
	// NormalizeRelPath（SECURITY 红线 1：所有写库路径的统一入口）。
	dir := req.TargetDir
	if dir != "" {
		dir, err = filing.NormalizeRelPath(dir)
		if err != nil {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "目标目录不合法")
			return
		}
	}
	// 文件名：显式传入才清洗改名；不传保留原名（原名入库前已经过扫描器
	// 校验，直接信任库值——重复清洗反而可能改动历史合法名）。
	name := path.Base(row.RelPath)
	if req.NewName != nil && *req.NewName != "" {
		name, err = filing.SanitizeFilename(*req.NewName)
		if err != nil {
			writeErr(w, http.StatusBadRequest, codeInvalidFilename, "新文件名不合法")
			return
		}
	}
	newRel := path.Join(dir, name)
	if newRel == row.RelPath {
		w.WriteHeader(http.StatusOK) // 幂等：目标即当前位置，无事可做
		return
	}
	srcAbs := filepath.Join(row.RootPath, filepath.FromSlash(row.RelPath))
	// #9 关键段（锁内）：冲突双查 → 越界校验 → rename → 库行更新（失败
	// 回滚文件移动）。同库串行后双查结果不会被并发上传/移动/恢复在 rename
	// 前抢注，回滚也不会撞上并发创建的同名目标。
	gerr := filing.WithLibraryGate(row.LibraryID, func() error {
		// 冲突双查：文件系统（真实占用）与库行（唯一索引占位，可能是
		// 库/磁盘漂移的残留行）。命中任一 → 409。
		targetAbs := filepath.Join(row.RootPath, filepath.FromSlash(newRel))
		if _, err := os.Stat(targetAbs); err == nil {
			return errMoveFsConflict
		}
		if _, err := s.q.GetAssetByPath(r.Context(), db.GetAssetByPathParams{LibraryID: row.LibraryID, RelPath: newRel}); err == nil {
			return errMoveDbConflict
		}
		// SECURITY 红线 1 的 handler 侧兜底（与 media 直链/trash 同一纵深防御
		// 模式）：目标侧 dir+name 各过校验、源侧 rel_path 直信库行——库数据
		// 被污染（外部改库/迁移 bug）时 Join 后这道闸是最后防线，两侧都验。
		if !filing.PathWithinRoot(row.RootPath, targetAbs) || !filing.PathWithinRoot(row.RootPath, srcAbs) {
			s.logger.Error("移动路径越界，已拦截", "assetId", row.AssetID,
				"src", row.RelPath, "dst", newRel)
			return errMoveEscaped
		}
		if err := os.MkdirAll(filepath.Dir(targetAbs), dirPerm); err != nil {
			return fmt.Errorf("创建目标目录: %w", err)
		}
		if err := os.Rename(srcAbs, targetAbs); err != nil {
			return fmt.Errorf("移动文件: %w", err)
		}
		if _, err := s.q.MoveAssetPath(r.Context(), db.MoveAssetPathParams{
			RelPath: newRel, FileName: name,
			UpdatedAt: store.FormatTimestamp(s.now()),
			AssetID:   row.AssetID,
		}); err != nil {
			// 库行没动、文件已挪：回滚文件移动恢复原状，两边一致好过
			// 靠扫描器自愈的中间态（自愈是兜底不是常态路径）。
			if rbErr := os.Rename(targetAbs, srcAbs); rbErr != nil {
				s.logger.Error("移动库行失败且回滚文件失败（待扫描器合并修正）",
					"err", err, "rollbackErr", rbErr, "assetId", row.AssetID)
			}
			return fmt.Errorf("更新资产路径: %w", err)
		}
		return nil
	})
	switch {
	case gerr == nil:
	case errors.Is(gerr, errMoveFsConflict):
		writeErr(w, http.StatusConflict, codeTargetExists, "目标位置已有同名文件")
		return
	case errors.Is(gerr, errMoveDbConflict):
		writeErr(w, http.StatusConflict, codeTargetExists, "目标位置已被占用")
		return
	case errors.Is(gerr, errMoveEscaped):
		writeErr(w, http.StatusBadRequest, codePathEscape, "路径不合法")
		return
	default:
		s.internalErr(w, "移动资产", gerr)
		return
	}
	// 改名/移动改变出处匹配输入而 size+mtime 不变（不会触发重 ingest），
	// 写入路径显式重算富化；失败只警告——富化可由重扫自愈，不阻塞移动。
	if err := s.scanner.EnrichAsset(r.Context(), row.LibraryID, row.AssetID); err != nil {
		if !errors.Is(err, ErrScannerUnavailable) {
			s.logger.Warn("移动后重算出处失败（可重扫自愈）", "err", err, "assetId", row.AssetID)
		}
	}
	s.publishLibraryChanged()
	w.WriteHeader(http.StatusOK)
}
