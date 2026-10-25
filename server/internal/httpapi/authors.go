// authors.go 适配作者/TXT 协议；导入、保护与重建事务在 authorimport，片段存取在 authorattach。
package httpapi

import (
	"context"
	"errors"
	"net/http"
	"sort"
	"strings"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authorimport"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// GetApiV1Authors 作者列表：全部作者 + 关联文件数 + 关注标记，
// 按 display_name 升序（查询注释写明口径，与 GET /tags 同风格）。
// 行装配走 authorFromListRow 单源（authors_manage.go，作者管理端点共用）。
func (s *Server) GetApiV1Authors(w http.ResponseWriter, r *http.Request) {
	rows, err := s.q.ListAuthors(r.Context())
	if err != nil {
		s.internalErr(w, "查询作者列表", err)
		return
	}
	out := make([]gen.Author, 0, len(rows))
	for _, row := range rows {
		out = append(out, authorFromListRow(row))
	}
	writeJSON(w, http.StatusOK, out)
}

// PostApiV1AuthorsImportTxt 导入作者 TXT：三格式自动识别 → 统一重建（含
// 重导入保护）→ 返回本次导入计数。409 = 新内容会丢弃上传写入条目，须带
// conflictResolution 重发（REQ §3.3 第 10 条）。
func (s *Server) PostApiV1AuthorsImportTxt(w http.ResponseWriter, r *http.Request) {
	var body gen.PostApiV1AuthorsImportTxtJSONRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	if strings.TrimSpace(body.Content) == "" {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "content 不能为空")
		return
	}
	// conflictResolution 枚举校验（openapi enum [keep, remove]；生成物不做
	// 运行时校验，这里手查——空串视为未传，向后兼容旧客户端）。
	resolution := ""
	if body.ConflictResolution != nil {
		resolution = strings.TrimSpace(string(*body.ConflictResolution))
		if resolution != authorimport.Keep && resolution != authorimport.Remove {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "conflictResolution 只允许 keep 或 remove")
			return
		}
	}

	// 溯源章（ADR-0032）：手动 HTTP 导入通道（备份/本机同步各自在调用点
	// 盖 import/local-sync，见 import.go 与 syncchannel/txt.go）。
	res, err := s.importTxt(r.Context(), body.Filename, body.Content, resolution, store.OriginTXT)
	if err != nil {
		var conflict *authorimport.Conflict
		if errors.As(err, &conflict) {
			writeJSON(w, http.StatusConflict, buildTxtImportConflict(conflict))
			return
		}
		s.internalErr(w, "导入作者 TXT", err)
		return
	}
	// 作者-文件关联变了（COS 分流与 cosWork 装配输入），推荐缓存失效
	s.invalidateRecommendCache()
	// 片段内容已变（新片段/覆盖/keep 并回）：同步刷新本地镜像（尽力而为，
	// REQ §3.4——镜像失败不影响导入结果，事务已提交）。
	s.mirror.Refresh(r.Context(), s.q)
	writeJSON(w, http.StatusOK, res)
}

// buildTxtImportConflict 把冲突哨兵错误装配为 409 响应载荷
// （gen.TxtImportConflict，协议 schema）。
func buildTxtImportConflict(e *authorimport.Conflict) gen.TxtImportConflict {
	authors := make([]gen.TxtImportConflictAuthor, 0, len(e.Missing))
	for _, m := range e.Missing {
		id, name := m.AuthorID, m.DisplayName
		works := append([]string(nil), m.Works...)
		sources := append([]string(nil), m.Sources...)
		authors = append(authors, gen.TxtImportConflictAuthor{
			AuthorId: &id, DisplayName: &name, Works: &works, Sources: &sources,
		})
	}
	filename := e.Filename
	return gen.TxtImportConflict{Filename: &filename, Authors: &authors}
}

// importTxt 映射手动TXT导入的HTTP结果；事务在authorimport。
// 便携备份由portablebackup直接复用authorimport，不经过本协议适配。
func (s *Server) importTxt(ctx context.Context, filename *string, content, resolution, origin string) (gen.TxtImportResult, error) {
	result, err := s.txt.Import(ctx, filename, content, resolution, origin)
	return txtResultToGen(result), err
}

func txtResultToGen(result authorimport.Result) gen.TxtImportResult {
	return gen.TxtImportResult{AuthorsImported: ptr(result.AuthorsImported), FilesMatched: ptr(result.FilesMatched), MergedUploadEntries: result.MergedUploadEntries}
}

// GetApiV1AuthorsImportTxt 已导入的 TXT 片段列表（旧版数据管理「TXT导入
// 作者」卡片；按文件名升序，匿名导入的空名排最前；importedAt = 最近导入
// 时刻，旧数据无该字段则缺省）。
func (s *Server) GetApiV1AuthorsImportTxt(w http.ResponseWriter, r *http.Request) {
	sources, err := authorattach.LoadSources(r.Context(), s.q)
	if err != nil {
		s.internalErr(w, "查询已导入 TXT", err)
		return
	}
	out := make([]gen.TxtImportedFile, 0, len(sources))
	for _, src := range sources {
		item := gen.TxtImportedFile{Filename: src.Filename}
		if src.ImportedAt != "" {
			item.ImportedAt = ptr(src.ImportedAt)
		}
		out = append(out, item)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Filename < out[j].Filename })
	writeJSON(w, http.StatusOK, out)
}

// DeleteApiV1AuthorsImportTxt 移除一个已导入 TXT 片段并从剩余片段统一
// 重建（204；不存在 → 404）。
func (s *Server) DeleteApiV1AuthorsImportTxt(w http.ResponseWriter, r *http.Request, params gen.DeleteApiV1AuthorsImportTxtParams) {
	if err := s.txt.Remove(r.Context(), params.Filename); err != nil {
		if errors.Is(err, authorimport.ErrSourceNotFound) {
			writeErr(w, http.StatusNotFound, codeNotFound, "TXT 片段不存在")
			return
		}
		s.internalErr(w, "移除 TXT 片段", err)
		return
	}
	// 片段移除触发关联重建，推荐输入变了，缓存失效
	s.invalidateRecommendCache()
	// 片段内容变了（少了一份）：同步刷新本地镜像（尽力而为，REQ §3.4）。
	s.mirror.Refresh(r.Context(), s.q)
	w.WriteHeader(http.StatusNoContent)
}

// PostApiV1AuthorsImportTxtRebuild 重放全部已导入 TXT 片段并重建常规作者-文件
// 关联（库重建/关联意外丢失后的修复入口）。幂等：片段内容不变，不写回
// 片段存储，只按已存片段并集删旧关联再全量重插；无片段时直接返回零值；
// 不刷新镜像（片段内容未变，挂点只挂「改变片段内容」的操作）。
// 计数口径：authorsImported=片段合并后的作者数；
// filesMatched=全部（作者, 去重作品）对的匹配文件数（countWorks 传每个
// 作者的全部作品名 = 全量计数，insertLinks 内按作品去重不翻倍）。
func (s *Server) PostApiV1AuthorsImportTxtRebuild(w http.ResponseWriter, r *http.Request) {
	result, err := s.txt.Rebuild(r.Context())
	if err != nil {
		s.internalErr(w, "重放 TXT 重建关联", err)
		return
	}
	// 无片段时保留原先不失效缓存的语义。
	if result.Rebuilt {
		s.invalidateRecommendCache()
	}
	writeJSON(w, http.StatusOK, txtResultToGen(result))
}

// PutApiV1AuthorsAuthorIdFollow 设置/取消关注作者（DOMAIN_RULES §6/§7：
// 作者维度布尔标记，取消即清除、不删除作者及其文件）。
// 0 行受影响 = 作者不存在 → 404（查询注释约定）。
func (s *Server) PutApiV1AuthorsAuthorIdFollow(w http.ResponseWriter, r *http.Request, authorID string) {
	var body gen.PutApiV1AuthorsAuthorIdFollowJSONRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	followed := int64(0)
	if body.Follow {
		followed = 1
	}
	rows, err := s.q.SetAuthorFollow(r.Context(), db.SetAuthorFollowParams{
		Followed: followed,
		ID:       authorID,
	})
	if err != nil {
		s.internalErr(w, "设置关注作者", err)
		return
	}
	if rows == 0 {
		writeErr(w, http.StatusNotFound, codeNotFound, "作者不存在")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
