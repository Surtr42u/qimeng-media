// Package search 负责全文检索（SQLite FTS5）的索引维护与查询。
//
// 为什么用 FTS5 而非 LIKE：媒体库标题/标签/作者的模糊搜索是高频操作，
// FTS5 提供倒排索引与中文友好的分词配置，万级数据下性能远超全表扫描。
//
// 边界（见 docs/ARCHITECTURE.md §5）：
//   - 职责：FTS5 索引维护与查询
package search
