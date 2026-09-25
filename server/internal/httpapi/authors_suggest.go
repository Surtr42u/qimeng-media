// authors_suggest.go：作者辅助端点（REQ §3.1 / §3.4）——作者联想（suggest）、
// 片段导出（export）、本地镜像配置（mirror）。来源词表（通用词表与单作者
// 片段来源区）随编辑端点拆到 author_edit.go（ADR-0024）；核心挂靠编排与
// 片段存取分别在 authorattach / authoring 包，本文件只做 HTTP 接线与参数
// 校验（ADR-0019：编排不堆 httpapi）。
package httpapi

import (
	"fmt"
	"net/http"
	"net/url"
	"path/filepath"
	"strings"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
)

// 联想端点 limit 缺省与上限。与 api/openapi.yaml GET /authors/suggest 的
// limit 参数（default=10、minimum=1、maximum=50）双写同步：协议侧改默认值
// 或上限必须同步这里，反之亦然（同步责任注释风格见 pagination.go）。
const (
	defaultSuggestLimit = 10
	maxSuggestLimit     = 50
)

// GetApiV1AuthorsSuggest 作者联想（上传挂靠输入框）：常规作者（type=regular，
// 挂靠是 TXT 常规作者体系能力，COS 作者由目录派生不参与），q 对 displayName
// 子串匹配、大小写不敏感（displayName 已含 " / " 连接的别名，别名片段命中
// 同一人）；q trim 后空 → 空列表；按 displayName 升序（ListAuthors 既有
// 排序）截断到 limit。全量 ListAuthors 在内存过滤——作者表量级 = 用户手工
// 清单（百级），不为联想开新查询。
func (s *Server) GetApiV1AuthorsSuggest(w http.ResponseWriter, r *http.Request, params gen.GetApiV1AuthorsSuggestParams) {
	limit := defaultSuggestLimit
	if params.Limit != nil {
		if *params.Limit < 1 || *params.Limit > maxSuggestLimit {
			writeErr(w, http.StatusBadRequest, codeInvalidParam,
				fmt.Sprintf("limit 取值范围 1..%d", maxSuggestLimit))
			return
		}
		limit = *params.Limit
	}
	q := strings.TrimSpace(params.Q)
	if q == "" {
		writeJSON(w, http.StatusOK, []gen.AuthorSuggest{})
		return
	}
	rows, err := s.q.ListAuthors(r.Context())
	if err != nil {
		s.internalErr(w, "查询作者联想", err)
		return
	}
	needle := strings.ToLower(q)
	out := make([]gen.AuthorSuggest, 0, len(rows))
	for _, row := range rows {
		if len(out) >= limit {
			break
		}
		if row.Type != authoring.AuthorTypeRegular ||
			!strings.Contains(strings.ToLower(row.DisplayName), needle) {
			continue
		}
		id, name, fileCount := row.ID, row.DisplayName, int(row.FileCount)
		out = append(out, gen.AuthorSuggest{Id: &id, DisplayName: &name, FileCount: &fileCount})
	}
	writeJSON(w, http.StatusOK, out)
}

// exportAnonymousName 是导出匿名片段（filename 空/缺省）时的下载文件名。
// 来源：api/openapi.yaml GET /authors/import-txt/export 的 200 description
// 约定（「匿名片段用『作者清单.txt』」）——协议侧改此名必须同步这里，反之亦然。
const exportAnonymousName = "作者清单.txt"

// GetApiV1AuthorsImportTxtExport 导出片段原文（text/plain 下载，REQ §3.4）：
// 内容 = 服务端当前片段原文逐字节（往返一致——上传条目都在导出内容里，
// 重导它不触发重导入保护，验收 #13）。filename 缺省/空串 = 匿名片段
// （匿名导入只保留最近一份，仍是全等匹配而非 MostRecent 兜底——与
// DELETE /import-txt 的 filename 语义一致）。
func (s *Server) GetApiV1AuthorsImportTxtExport(w http.ResponseWriter, r *http.Request, params gen.GetApiV1AuthorsImportTxtExportParams) {
	name := ""
	if params.Filename != nil {
		name = strings.TrimSpace(*params.Filename)
	}
	sources, err := authorattach.LoadSources(r.Context(), s.q)
	if err != nil {
		s.internalErr(w, "导出 TXT 片段", err)
		return
	}
	var content string
	found := false
	for _, src := range sources {
		if src.Filename == name {
			content, found = src.Content, true
			break
		}
	}
	if !found {
		writeErr(w, http.StatusNotFound, codeNotFound, "TXT 片段不存在")
		return
	}
	// 手写响应头（writeJSON 不适用）：text/plain + attachment 下载。
	// 片段名来自服务端存储而非用户输入，无需清洗文件名非法字符；但
	// Content-Disposition 的 filename* 必须 percent-encode（RFC 5987，
	// 中文名直接裸写会被各端按 latin-1 截断）。
	downloadName := name
	if downloadName == "" {
		downloadName = exportAnonymousName
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.Header().Set("Content-Disposition", "attachment; filename*=UTF-8''"+url.PathEscape(downloadName))
	w.WriteHeader(http.StatusOK)
	// 写失败只可能发生在客户端断开时（导出是终端响应），无补救动作。
	_, _ = w.Write([]byte(content))
}

// mirrorFieldMaxLen 镜像配置两字段的长度上限（服务端防御性上限，500 与
// openapi 上传 source 词上限同数量级；超长路径/片段名按 400 拒绝，防把
// 超长键写进 kv_settings）。
const mirrorFieldMaxLen = 500

// GetApiV1AuthorsMirror 读镜像配置（无记录 = 零值：path 空串=关闭，
// fragmentFilename 空串=镜像最近导入片段；两字段都显式回显，客户端不需要
// 区分「未配置」与「配了空值」）。
func (s *Server) GetApiV1AuthorsMirror(w http.ResponseWriter, r *http.Request) {
	cfg, err := authorattach.LoadMirrorConfig(r.Context(), s.q)
	if err != nil {
		s.internalErr(w, "读取作者镜像配置", err)
		return
	}
	writeJSON(w, http.StatusOK, gen.AuthorMirrorConfig{
		Path:             &cfg.Path,
		FragmentFilename: &cfg.FragmentFilename,
	})
}

// PutApiV1AuthorsMirror 保存镜像配置：path 非空时必须是绝对路径（相对路径
// 会让镜像悄悄跟随进程工作目录漂移，部署事故难排查——与库根 rootPath 的
// IsAbs 校验同口径）。保存成功即同步尝试一次镜像刷新（尽力而为，失败只
// 告警不影响 200——路径恢复后的下一次片段变更自动补写），响应回显存储后
// 的配置。
func (s *Server) PutApiV1AuthorsMirror(w http.ResponseWriter, r *http.Request) {
	var body gen.PutApiV1AuthorsMirrorJSONRequestBody
	if !decodeJSON(w, r, &body) {
		return
	}
	path, fragment := "", ""
	if body.Path != nil {
		path = strings.TrimSpace(*body.Path)
	}
	if body.FragmentFilename != nil {
		fragment = strings.TrimSpace(*body.FragmentFilename)
	}
	if path != "" {
		if !filepath.IsAbs(path) {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "镜像路径必须是绝对路径")
			return
		}
		if len(path) > mirrorFieldMaxLen {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "镜像路径超过长度上限")
			return
		}
	}
	if len(fragment) > mirrorFieldMaxLen {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "镜像目标片段名超过长度上限")
		return
	}
	cfg := authorattach.MirrorConfig{Path: path, FragmentFilename: fragment}
	if err := authorattach.SaveMirrorConfig(r.Context(), s.q, s.now(), cfg); err != nil {
		s.internalErr(w, "保存作者镜像配置", err)
		return
	}
	s.mirror.Refresh(r.Context(), s.q)
	stored := gen.AuthorMirrorConfig{Path: &path, FragmentFilename: &fragment}
	writeJSON(w, http.StatusOK, stored)
}
