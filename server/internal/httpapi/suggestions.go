// suggestions.go：搜索框输入补全端点（GET /api/v1/search/suggestions）。
//
// 语义唯一权威：api/openapi.yaml 该端点 description（五维候选、子串匹配
// ASCII 大小写不敏感、同维去重跨维保留、长度升序再名称字典序、作者维只
// 显有文件——LEGACY §C + 用户 2026-09-05 拍板 3B）；SQL 实现与取舍见
// store/queries/suggestions.sql 文件头（单 UNION，排序去重截断全在 SQL）。
package httpapi

import (
	"net/http"
	"strings"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store/db"
)

// 补全端点的 limit 语义（openapi limit default=10 / maximum=50）：与分页
// limit（pagination.go 的 60/200）不同套，独立常量——协议侧改默认值或上限
// 必须同步这里，反之亦然（AI_README_FIRST「代码卫生约束」3）。
const (
	defaultSuggestionLimit = 10
	maxSuggestionLimit     = 50
)

// GetApiV1SearchSuggestions 搜索框补全候选（q 子串命中五维，合并去重排序）。
// 无 nextCursor——LIMIT 直接取钳后值，不做「多取 1 行探测下一页」。
func (s *Server) GetApiV1SearchSuggestions(w http.ResponseWriter, r *http.Request, params gen.GetApiV1SearchSuggestionsParams) {
	limit := defaultSuggestionLimit
	if params.Limit != nil {
		if *params.Limit < 1 || *params.Limit > maxSuggestionLimit {
			writeErr(w, http.StatusBadRequest, "INVALID_PARAM",
				"limit 取值范围 1..50")
			return
		}
		limit = *params.Limit
	}
	// q 必填但允许空串（协议口径）：trim 后为空 = 无输入，直接空列表，
	// 不查库（空串会让 instr 对一切候选命中，语义上等价于「全量建议」，
	// 不是补全想要的形态）。
	q := strings.TrimSpace(params.Q)
	if q == "" {
		items := []gen.SearchSuggestion{}
		writeJSON(w, http.StatusOK, gen.SearchSuggestions{Items: items})
		return
	}
	rows, err := s.q.ListSearchSuggestions(r.Context(), db.ListSearchSuggestionsParams{
		Q:        q,
		RowLimit: int64(limit),
	})
	if err != nil {
		s.internalErr(w, "聚合搜索建议", err)
		return
	}
	items := make([]gen.SearchSuggestion, 0, len(rows))
	for _, row := range rows {
		// Name 各分支源列均 NOT NULL（IS NOT NULL 守卫/表约束），Valid 恒真；
		// NullString 是 sqlc 对 UNION 非首分支字面量的保守定型。
		items = append(items, gen.SearchSuggestion{
			Type: gen.SearchSuggestionType(row.SuggestionType),
			Name: row.Name.String,
		})
	}
	writeJSON(w, http.StatusOK, gen.SearchSuggestions{Items: items})
}
