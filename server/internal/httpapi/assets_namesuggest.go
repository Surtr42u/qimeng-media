// assets_namesuggest.go：作品名序号联想端点（GET /assets/name-suggestions，
// ADR-0024 修订）。核心匹配与序号推进是 filing 包纯函数
// （SuggestSeriesNames，表驱动测试锁定），本文件只做 HTTP 接线、参数校验
// 与文件名取数（ADR-0019：编排不堆 httpapi 的同款分工）。
package httpapi

import (
	"database/sql"
	"errors"
	"net/http"
	"strings"

	"qimeng-media/server/internal/filing"
	"qimeng-media/server/internal/httpapi/gen"
)

// GetApiV1AssetsNameSuggestions 作品名序号联想（上传暂存作品名输入框）：
// 该库现存文件名按规范化前缀命中命名族，各族按既有编号风格返回「最大序号
// +1」的建议基名（不含扩展名，客户端拼接锁定扩展名）。libraryId 必填
// （空 400、库不存在 404）；q 规范化后为空或无命中 → 200 空列表。cos 库
// 同样可用：文件名联想服务的是上传通道，与作者挂靠的 normal-only 能力边界
// 无关。
//
// 取数口径：ListAssetsByLibrary 全量载入该库资产行、只取 file_name 列。
// 不做 SQL 粗过滤的理由：联想匹配是「去扩展名 + 空白折叠 + 移除空白前缀 +
// ASCII 大小写不敏感」，LIKE 无法表达（空白折叠与「少空格吸附」让任何
// LIKE 模式既不可靠也无收益，粗滤后仍须在 Go 全量判定）；而单用户家庭库
// 的规模（万级行 × ~200B，ListAssetsByLibrary 的 scanner 对账同款论证）
// 全量载入毫秒级完成，不为联想新增 sqlc 查询。
func (s *Server) GetApiV1AssetsNameSuggestions(w http.ResponseWriter, r *http.Request, params gen.GetApiV1AssetsNameSuggestionsParams) {
	if strings.TrimSpace(params.LibraryId) == "" {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "libraryId 必填")
		return
	}
	lib, err := s.q.GetLibrary(r.Context(), params.LibraryId)
	if errors.Is(err, sql.ErrNoRows) {
		writeErr(w, http.StatusNotFound, codeNotFound, "库不存在")
		return
	}
	if err != nil {
		s.internalErr(w, "查询库", err)
		return
	}
	rows, err := s.q.ListAssetsByLibrary(r.Context(), lib.ID)
	if err != nil {
		s.internalErr(w, "载入库文件名", err)
		return
	}
	names := make([]string, 0, len(rows))
	for _, row := range rows {
		names = append(names, row.FileName)
	}
	writeJSON(w, http.StatusOK, gen.NameSuggestions{
		Suggestions: filing.SuggestSeriesNames(names, params.Q),
	})
}
