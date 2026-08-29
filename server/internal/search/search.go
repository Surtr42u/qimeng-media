package search

import (
	"context"
	"fmt"
	"strings"

	"qimeng-media/server/internal/store/db"
)

// RebuildIndex 全量重建 assets_fts（清空后按 asset_search_text 重灌）。
//
// 触发器的增量维护已覆盖全部写路径，此函数只在索引与主数据失配时使用
// （例如手工修库后、迁移器故障后自检）；数据量级为库内资产行数，重建
// 在批次事务中执行以防插入中途失败留下半套索引。
func RebuildIndex(ctx context.Context, q *db.Queries) error {
	if _, err := q.RebuildAssetsFtsClear(ctx); err != nil {
		return fmt.Errorf("search: 清空 FTS 索引: %w", err)
	}
	if _, err := q.RebuildAssetsFtsFill(ctx); err != nil {
		return fmt.Errorf("search: 重灌 FTS 索引: %w", err)
	}
	return nil
}

// ParseQuery 把搜索框原文 q 解析为关键词列表。
//
// 语义（DOMAIN_RULES §3，旧项目 applyFilter 口径）：按空格拆分、每个
// 非空词为一个关键词；多词为 AND 关系（每个词必须命中搜索维度的至少
// 一个）。关键词不做大小写归一——SQL 侧用 lower() 统一折叠（browse.sql
// 的 q_json 谓词），Go 侧转写小写会与该谓词语义双写漂移。
// 返回子串为空时返回 nil（调用方以 NULL 语义跳过搜索条件）。
func ParseQuery(q string) []string {
	var out []string
	for _, tok := range strings.Fields(q) {
		if tok != "" {
			out = append(out, tok)
		}
	}
	if len(out) == 0 {
		return nil
	}
	return out
}
