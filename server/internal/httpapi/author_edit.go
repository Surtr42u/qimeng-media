// author_edit.go：资产编辑端点（ADR-0024）——通用来源词表（GET/PUT
// /authors/source-vocabulary）、单作者片段来源区（GET/PUT
// /authors/{authorId}/sources）、资产作者关联整体替换（PUT
// /assets/{assetId}/authors）。片段手术与编排收敛在 authorattach/authoring
// 包（ADR-0019：编排不堆 httpapi），本文件只做 HTTP 接线、参数校验与
// 事务边界。核心语义：TXT 片段本体是唯一真相，编辑写进片段原地更新。
package httpapi

import (
	"database/sql"
	"errors"
	"fmt"
	"net/http"
	"strings"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/scanner"
)

// maxSourceVocabularyItems 是来源词表项数上限。与 api/openapi.yaml
// SourceVocabulary 的 sources 约束（maxItems: 32）双写同步：协议侧改动须
// 同步这里，反之亦然（同步责任注释风格见 pagination.go）。
const maxSourceVocabularyItems = 32

// msgAuthorNotFound 是作者编辑端点「作者不存在」的用户可读文案（与关注端
// 点同文案属巧合对齐，非机器同步值）。
const msgAuthorNotFound = "作者不存在"

// normalizeSourceWords 来源词表项规范化：逐项 trim、剔空、保序去重；任一项
// 含控制字符或超 authoring.MaxSourceWordRunes（协议 items maxLength 双写）
// → 400。项数上限按原始数组判（maxItems 约束数组本身，空项不豁免——沿用
// 原上传挂靠参数的同款口径）。失败时响应已写完，返回 false。
func normalizeSourceWords(w http.ResponseWriter, raw []string) ([]string, bool) {
	if len(raw) > maxSourceVocabularyItems {
		writeErr(w, http.StatusBadRequest, codeInvalidParam,
			fmt.Sprintf("sources 超过 %d 项上限", maxSourceVocabularyItems))
		return nil, false
	}
	seen := make(map[string]bool, len(raw))
	out := make([]string, 0, len(raw))
	for _, item := range raw {
		item = strings.TrimSpace(item)
		if item == "" || seen[item] {
			continue // 空项静默剔除（客户端表单常见空尾巴）、重复勾选幂等
		}
		if !authoring.ValidSourceWord(item) {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "来源含非法字符或超过长度上限")
			return nil, false
		}
		seen[item] = true
		out = append(out, item)
	}
	return out, true
}

// writeAuthorEditErr 作者编辑端点的哨兵错误映射：不存在 404、COS 作者 400、
// 其余内部错误。
func (s *Server) writeAuthorEditErr(w http.ResponseWriter, what string, err error) {
	switch {
	case errors.Is(err, authorattach.ErrAuthorNotFound):
		writeErr(w, http.StatusNotFound, codeNotFound, msgAuthorNotFound)
	case errors.Is(err, authorattach.ErrAuthorNotRegular):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "COS 作者不支持来源编辑")
	default:
		s.internalErr(w, what, err)
	}
}

// GetApiV1AuthorsSourceVocabulary 通用来源词表（来源建议唯一数据源；无记录
// = 空数组）。kv 键不存在（出厂态：从未预填且用户从未 PUT——PUT 恒写键，
// 清空也写空数组）时执行一次性自动预填：从全部已导入片段统计被多位作者
// 共用的通用平台名（authorattach.PrefillSourceVocabulary 口径），有结果才
// 写键并返回预填结果；键存在即用户已表态，永不再预填。事务包住「查键→
// 统计→写键」，并发读取不会交叉覆盖窗口期内的用户 PUT。
func (s *Server) GetApiV1AuthorsSourceVocabulary(w http.ResponseWriter, r *http.Request) {
	tx, err := s.conn.BeginTx(r.Context(), nil)
	if err != nil {
		s.internalErr(w, "读取来源词表", err)
		return
	}
	defer func() { _ = tx.Rollback() }()
	list, err := authorattach.EnsureSourceVocabulary(r.Context(), s.q.WithTx(tx), s.now())
	if err != nil {
		s.internalErr(w, "读取来源词表", err)
		return
	}
	if err := tx.Commit(); err != nil {
		s.internalErr(w, "读取来源词表", err)
		return
	}
	writeJSON(w, http.StatusOK, gen.SourceVocabulary{Sources: list})
}

// PutApiV1AuthorsSourceVocabulary 整体替换通用来源词表（trim+去重+剔空后
// 整体生效；空数组=清空；200 回显存储后词表）。
func (s *Server) PutApiV1AuthorsSourceVocabulary(w http.ResponseWriter, r *http.Request) {
	var body gen.PutApiV1AuthorsSourceVocabularyJSONRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	list, ok := normalizeSourceWords(w, body.Sources)
	if !ok {
		return
	}
	if err := authorattach.SaveSourceVocabulary(r.Context(), s.q, s.now(), list); err != nil {
		s.internalErr(w, "保存来源词表", err)
		return
	}
	writeJSON(w, http.StatusOK, gen.SourceVocabulary{Sources: list})
}

// GetApiV1AuthorsAuthorIdSources 读取作者片段来源区（编辑页回显）：作者块
// 所在数组序第一个片段的来源区；无块=空数组。作者不存在 404、COS 作者 400。
func (s *Server) GetApiV1AuthorsAuthorIdSources(w http.ResponseWriter, r *http.Request, authorId string) {
	if _, err := s.attach.ResolveRegularAuthor(r.Context(), s.q, authorId); err != nil {
		s.writeAuthorEditErr(w, "读取作者来源区", err)
		return
	}
	sources, err := s.attach.AuthorSources(r.Context(), s.q, authorId)
	if err != nil {
		s.internalErr(w, "读取作者来源区", err)
		return
	}
	if sources == nil {
		sources = []string{}
	}
	writeJSON(w, http.StatusOK, gen.SourceVocabulary{Sources: sources})
}

// PutApiV1AuthorsAuthorIdSources 写入作者片段来源区：mode=replace（缺省，
// 编辑页保存语义）块命中→原地整体替换、无块→最近导入片段新建作者块、
// 库中无片段→自动创建「上传自动挂靠.txt」；mode=append（上传流程自动挂靠）
// 并入去重、永不覆盖既有来源（ADR-0023 原上传来源口径）。校验同通用来源
// 词表；片段内容变化后同步修剪上传条目元数据（防重导入 409 误报）。
// 200 回显写入后来源区（replace=提交值；append=并入结果）。
func (s *Server) PutApiV1AuthorsAuthorIdSources(w http.ResponseWriter, r *http.Request, authorId string) {
	var body gen.PutApiV1AuthorsAuthorIdSourcesJSONRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	sources, ok := normalizeSourceWords(w, body.Sources)
	if !ok {
		return
	}
	mode, ok := parseSourcesWriteMode(w, body.Mode)
	if !ok {
		return
	}
	tx, err := s.conn.BeginTx(r.Context(), nil)
	if err != nil {
		s.internalErr(w, "替换作者来源区", err)
		return
	}
	defer func() { _ = tx.Rollback() }()
	qtx := s.q.WithTx(tx)
	displayName, err := s.attach.ResolveRegularAuthor(r.Context(), qtx, authorId)
	if err != nil {
		s.writeAuthorEditErr(w, "替换作者来源区", err)
		return
	}
	saved, err := s.attach.ReplaceAuthorSources(r.Context(), qtx, s.now(), authorId, displayName, sources, mode)
	if err != nil {
		s.internalErr(w, "替换作者来源区", err)
		return
	}
	if err := tx.Commit(); err != nil {
		s.internalErr(w, "替换作者来源区", err)
		return
	}
	// 片段内容已变：同步刷新本地镜像（尽力而为，REQ §3.4——失败不影响
	// 编辑结果，事务已提交）。
	s.mirror.Refresh(r.Context(), s.q)
	writeJSON(w, http.StatusOK, gen.SourceVocabulary{Sources: saved})
}

// parseSourcesWriteMode 把协议 mode 字段映射为编排层写入语义：nil=缺省
// replace（协议 default，编辑页与既有客户端的兼容口径）。gen 侧的 mode 是
// plain string 别名且 decodeJSON 走 encoding/json、无枚举校验，未知值在此
// 兜底 400——放行会被静默当 replace，上传流程误用时将冲掉作者既有来源区
// （append 语义正是为此存在的，ADR-0023）。
func parseSourcesWriteMode(w http.ResponseWriter, raw *gen.AuthorSourcesWriteRequestMode) (authorattach.SourcesWriteMode, bool) {
	switch {
	case raw == nil || *raw == gen.Replace:
		return authorattach.SourcesModeReplace, true
	case *raw == gen.Append:
		return authorattach.SourcesModeAppend, true
	default:
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "mode 仅支持 replace 或 append")
		return "", false
	}
}

// PutApiV1AssetsAssetIdAuthors 整体替换资产作者关联（编辑页保存）：
// 提交的 authorIds 就是该资产的作者全集（空数组=解除全部常规作者关联）。
// 片段真相原地更新（新增=作品行并入作者块、移除=遍历全部片段删作品行）与
// 关联表 swap 同事务（ADR-0024）。响应复用详情端点的完整装配路径
// （authors=替换后全集）。
func (s *Server) PutApiV1AssetsAssetIdAuthors(w http.ResponseWriter, r *http.Request, assetId gen.AssetId) {
	var body gen.PutApiV1AssetsAssetIdAuthorsJSONRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	ids := dedupeAuthorIDs(body.AuthorIds)
	ctx := r.Context()
	row, err := s.q.GetAssetWithLibrary(ctx, assetId.String())
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, codeNotFound, "资产不存在")
		return
	}
	if err != nil {
		s.internalErr(w, "查询资产", err)
		return
	}
	lib, err := s.q.GetLibrary(ctx, row.LibraryID)
	if err != nil {
		s.internalErr(w, "查询资产所在库", err)
		return
	}
	if !scanner.SupportsAuthorAttach(lib.Kind) {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "该库类型不支持作者挂靠")
		return
	}
	tx, err := s.conn.BeginTx(ctx, nil)
	if err != nil {
		s.internalErr(w, "替换资产作者关联", err)
		return
	}
	defer func() { _ = tx.Rollback() }()
	qtx := s.q.WithTx(tx)
	names, err := s.attach.ResolveRegularAuthors(ctx, qtx, ids)
	if err != nil {
		s.writeAuthorEditErr(w, "替换资产作者关联", err)
		return
	}
	refs := make([]authorattach.AuthorRef, 0, len(ids))
	for _, id := range ids {
		refs = append(refs, authorattach.AuthorRef{ID: id, DisplayName: names[id]})
	}
	if err := s.attach.ReplaceAssetAuthors(ctx, qtx, s.now(), row.AssetID, row.FileName, refs); err != nil {
		s.internalErr(w, "替换资产作者关联", err)
		return
	}
	if err := tx.Commit(); err != nil {
		s.internalErr(w, "替换资产作者关联", err)
		return
	}
	// 作者-文件关联变了（推荐输入变化）与片段内容变了（镜像尽力而为刷新）
	// 各自独立：缓存失效是确定性动作，镜像失败只告警。
	s.invalidateRecommendCache()
	s.mirror.Refresh(ctx, s.q)
	// 200 回资产详情（authors=替换后全集）：直接复用详情端点装配路径，
	// 保证与 GET /assets/{assetId} 的字段口径永不漂移。
	s.GetApiV1AssetsAssetId(w, r, assetId)
}

// dedupeAuthorIDs 保序去重（trim；空项剔除）——重复 id 的整体替换语义与
// 单次出现一致，防御客户端重复提交。
func dedupeAuthorIDs(raw []string) []string {
	seen := make(map[string]bool, len(raw))
	out := make([]string, 0, len(raw))
	for _, id := range raw {
		id = strings.TrimSpace(id)
		if id == "" || seen[id] {
			continue
		}
		seen[id] = true
		out = append(out, id)
	}
	return out
}
