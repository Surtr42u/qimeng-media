// author_edit.go：资产编辑端点（ADR-0024）——通用来源词表（GET/PUT
// /authors/source-vocabulary）、单作者片段来源区（GET/PUT
// /authors/{authorId}/sources）、资产作者关联整体替换（PUT
// /assets/{assetId}/authors）。多步事务与片段/关联编排收敛在 authoredit
// 用例包（Q1 事务下沉；ADR-0019：HTTP 不持有多步事务），本文件只做 HTTP
// 接线、纯协议校验与哨兵错误映射。核心语义：TXT 片段本体是唯一真相，
// 编辑写进片段原地更新。
package httpapi

import (
	"errors"
	"fmt"
	"net/http"
	"strings"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authoredit"
	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
)

// maxSourceVocabularyItems 是来源词表项数上限。与 api/openapi.yaml
// SourceVocabulary 的 sources 约束（maxItems: 32）双写同步：协议侧改动须
// 同步这里，反之亦然（同步责任注释风格见 pagination.go）。
const maxSourceVocabularyItems = 32

// maxNewAuthorNames 是资产作者替换端点 newAuthorNames 的项数上限。与
// api/openapi.yaml AssetAuthorsReplaceRequest.newAuthorNames 的
// maxItems: 20 双写同步：协议侧改动须同步这里，反之亦然（同步责任注释
// 风格见 pagination.go）。
const maxNewAuthorNames = 20

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

// validateNewAuthorNames 校验 newAuthorNames：项数上限（maxItems 双写）与
// 逐名合法性（authoring.NewAuthorNameRejectReason，与上传挂靠同一份规则，
// 消息带具体原因）。不合法时 400 已写完，返回 false。项数按原始数组判
// （maxItems 约束数组本身，空项不豁免——沿用 normalizeSourceWords 同款
// 口径，且名字为空本身也是非法名）。
func validateNewAuthorNames(w http.ResponseWriter, names []string) bool {
	if len(names) > maxNewAuthorNames {
		writeErr(w, http.StatusBadRequest, codeInvalidParam,
			fmt.Sprintf("newAuthorNames 超过 %d 项上限", maxNewAuthorNames))
		return false
	}
	for i, name := range names {
		if reason := authoring.NewAuthorNameRejectReason(name); reason != "" {
			writeErr(w, http.StatusBadRequest, codeInvalidParam,
				fmt.Sprintf("newAuthorNames 第 %d 项 %q 非法：%s", i+1, name, reason))
			return false
		}
	}
	return true
}

// writeAuthorEditErr 作者编辑端点的哨兵错误映射：作者不存在 404、COS 作者
// 400、资产不存在 404、不支持挂靠的库类型 400（后两者由 authoredit 用例在
// 事务内判出）、其余内部错误。
func (s *Server) writeAuthorEditErr(w http.ResponseWriter, what string, err error) {
	switch {
	case errors.Is(err, authorattach.ErrAuthorNotFound):
		writeErr(w, http.StatusNotFound, codeNotFound, msgAuthorNotFound)
	case errors.Is(err, authorattach.ErrAuthorNotRegular):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "COS 作者不支持来源编辑")
	case errors.Is(err, authoredit.ErrAssetNotFound):
		writeErr(w, http.StatusNotFound, codeNotFound, "资产不存在")
	case errors.Is(err, authoredit.ErrUnsupportedLibraryKind):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "该库类型不支持作者挂靠")
	default:
		s.internalErr(w, what, err)
	}
}

// GetApiV1AuthorsSourceVocabulary 通用来源词表（来源建议唯一数据源；无记录
// = 空数组）。kv 键不存在（出厂态：从未预填且用户从未 PUT——PUT 恒写键，
// 清空也写空数组）时执行一次性自动预填：从全部已导入片段统计被多位作者
// 共用的通用平台名（authorattach.PrefillSourceVocabulary 口径），有结果才
// 写键并返回预填结果；键存在即用户已表态，永不再预填。「查键→统计→写键」
// 的单事务与并发安全由 authoredit 用例持有。
func (s *Server) GetApiV1AuthorsSourceVocabulary(w http.ResponseWriter, r *http.Request) {
	list, err := s.authorEdit.EnsureSourceVocabulary(r.Context())
	if err != nil {
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
// 词表。「解析作者 → 写片段 → 修剪上传条目」的单事务与提交后镜像刷新由
// authoredit 用例持有（失败不刷新）。200 回显写入后来源区（replace=提交值；
// append=并入结果）。
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
	saved, err := s.authorEdit.ReplaceAuthorSources(r.Context(), authorId, sources, mode)
	if err != nil {
		s.writeAuthorEditErr(w, "替换作者来源区", err)
		return
	}
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

// PutApiV1AssetsAssetIdAuthors 整体替换资产作者关联（编辑页保存）：最终作者
// 全集 = 提交的 authorIds ∪ newAuthorNames 按名解析出的作者（同 id 去重；
// 空 authorIds + 无新名 = 解除全部常规作者关联）。newAuthorNames 是编辑页
// 「新建：xxx」通道：规范身份命中已有常规作者复用、未命中才新建（解析口径
// 见 authorattach.ResolveOrCreateAuthors）；nil/空时行为与旧协议完全一致
// （老客户端零感知）。资产行、所在库类型与当前文件名都在用例事务内读取；
// 片段真相原地更新（新增=作品行并入作者块、移除=遍历全部片段删作品行）
// 与关联表 swap 同事务，提交成功后镜像刷新——以上编排由 authoredit 用例
// 持有（ADR-0024、Q1 事务下沉）。响应复用详情端点的完整装配路径
// （authors=替换后全集）。
func (s *Server) PutApiV1AssetsAssetIdAuthors(w http.ResponseWriter, r *http.Request, assetId gen.AssetId) {
	var body gen.PutApiV1AssetsAssetIdAuthorsJSONRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	ids := dedupeAuthorIDs(body.AuthorIds)
	var newNames []string
	if body.NewAuthorNames != nil {
		newNames = *body.NewAuthorNames
	}
	// 名字校验是纯函数（不触库），先于用例执行：非法输入不产生任何副作用
	// （也避免为必败请求开事务）。
	if !validateNewAuthorNames(w, newNames) {
		return
	}
	if err := s.authorEdit.ReplaceAssetAuthors(r.Context(), assetId.String(), ids, newNames); err != nil {
		s.writeAuthorEditErr(w, "替换资产作者关联", err)
		return
	}
	// 作者-文件关联变了（推荐输入变化）：缓存失效是确定性动作；镜像已由
	// 用例在提交成功后刷新，这里不重复刷（失败只告警、不影响结果）。
	s.invalidateRecommendCache()
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
