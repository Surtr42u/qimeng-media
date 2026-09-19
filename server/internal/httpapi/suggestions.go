// suggestions.go：搜索框输入补全端点（GET /api/v1/search/suggestions）。
//
// 语义唯一权威：api/openapi.yaml 该端点 description（五维候选、子串匹配
// ASCII 大小写不敏感、同维去重跨维保留、长度升序再名称字典序、作者维只
// 显有文件——LEGACY §C + 用户 2026-09-05 拍板 3B）。两种形态：
//   - 补全（q 非空，recommend 被忽略）：单 UNION 子串命中，SQL 全权排序
//     去重截断，实现见 store/queries/suggestions.sql；
//   - 随机推荐（recommend=true 且 q 为空，空态「推荐搜索」数据源，
//     LEGACY 随机语义）：五个名字池 SQL 端各随机取样，handler 轮转交错
//     合并，实现见 suggestions.sql 随机池节。
package httpapi

import (
	"context"
	"database/sql"
	"fmt"
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

// GetApiV1SearchSuggestions 搜索框补全：q 非空走子串匹配补全；q 为空且
// recommend=true 走随机推荐模式，否则空列表（协议口径）。两种形态都无
// nextCursor——条数直接取钳后值，不做「多取 1 行探测下一页」。
func (s *Server) GetApiV1SearchSuggestions(w http.ResponseWriter, r *http.Request, params gen.GetApiV1SearchSuggestionsParams) {
	limit := defaultSuggestionLimit
	if params.Limit != nil {
		if *params.Limit < 1 || *params.Limit > maxSuggestionLimit {
			writeErr(w, http.StatusBadRequest, codeInvalidParam,
				fmt.Sprintf("limit 取值范围 1..%d", maxSuggestionLimit))
			return
		}
		limit = *params.Limit
	}
	// q 必填但允许空串（协议口径）：trim 后为空 = 无输入。默认形态直接空
	// 列表不查库（空串会让 instr 对一切候选命中，语义上等价于「全量建议」，
	// 不是补全想要的形态）；recommend=true 时切换为随机推荐模式。
	q := strings.TrimSpace(params.Q)
	if q == "" {
		if params.Recommend != nil && *params.Recommend {
			s.writeRandomSuggestions(w, r, limit)
			return
		}
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

// suggestionPool 单个随机名字池：suggestionType 即协议 type 字段（前端
// 类型徽标语义），names 已是同维去重后的候选（SQL DISTINCT，见随机池节）。
type suggestionPool struct {
	suggestionType gen.SearchSuggestionType
	names          []string
}

// writeRandomSuggestions 随机推荐模式：五个名字池各随机取样（SQL 端
// ORDER BY random()，每池上限 = 请求 limit，取舍理由见 suggestions.sql
// 随机池节注释），handler 按池轮转交错合并——五维有货即混合出现，任一
// 维缺货时其余维可填满 limit；同维同名已在 SQL 去重，跨维同名按协议各
// 保留一条（type 字段区分），(type, name) 对天然唯一，直接截断到 limit。
func (s *Server) writeRandomSuggestions(w http.ResponseWriter, r *http.Request, limit int) {
	pools, err := s.fetchSuggestionPools(r.Context(), int64(limit))
	if err != nil {
		s.internalErr(w, "随机搜索建议", err)
		return
	}
	items := make([]gen.SearchSuggestion, 0, limit)
	for cursor := 0; len(items) < limit; cursor++ {
		progressed := false
		for _, pool := range pools {
			if cursor >= len(pool.names) {
				continue
			}
			items = append(items, gen.SearchSuggestion{
				Type: pool.suggestionType,
				Name: pool.names[cursor],
			})
			progressed = true
			if len(items) >= limit {
				break
			}
		}
		if !progressed {
			break // 五池全部取尽
		}
	}
	writeJSON(w, http.StatusOK, gen.SearchSuggestions{Items: items})
}

// fetchSuggestionPools 拉取五个随机池（候选口径与 ListSearchSuggestions
// 各分支逐一对应，见 suggestions.sql 随机池节；SQLite 单连接串行执行，
// 并发无收益）。池顺序 = 协议维度顺序，也即交错合并的轮转顺序。
func (s *Server) fetchSuggestionPools(ctx context.Context, poolCap int64) ([]suggestionPool, error) {
	sources, err := s.q.RandomSourceSuggestionPool(ctx, poolCap)
	if err != nil {
		return nil, err
	}
	characters, err := s.q.RandomCharacterSuggestionPool(ctx, poolCap)
	if err != nil {
		return nil, err
	}
	cosAuthors, err := s.q.RandomCosAuthorSuggestionPool(ctx, poolCap)
	if err != nil {
		return nil, err
	}
	cosWorks, err := s.q.RandomCosWorkSuggestionPool(ctx, poolCap)
	if err != nil {
		return nil, err
	}
	authors, err := s.q.RandomAuthorSuggestionPool(ctx, poolCap)
	if err != nil {
		return nil, err
	}
	return []suggestionPool{
		// source/cosWork 源列可空但带 IS NOT NULL 守卫，Valid 恒真（同本文件
		// 补全分支的 sqlc 保守定型口径）。
		{suggestionType: gen.SearchSuggestionTypeSource, names: validNames(sources)},
		{suggestionType: gen.SearchSuggestionTypeCharacter, names: characters},
		{suggestionType: gen.SearchSuggestionTypeCosAuthor, names: cosAuthors},
		{suggestionType: gen.SearchSuggestionTypeCosWork, names: validNames(cosWorks)},
		{suggestionType: gen.SearchSuggestionTypeAuthor, names: authors},
	}, nil
}

// validNames 摊平 NullString 池（守卫保证 Valid 恒真，见 fetchSuggestionPools 注释）。
func validNames(nulls []sql.NullString) []string {
	names := make([]string, 0, len(nulls))
	for _, n := range nulls {
		names = append(names, n.String)
	}
	return names
}
