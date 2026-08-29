// Package search 负责全文检索（SQLite FTS5）的查询语义与索引重建。
//
// 为什么用 FTS5 而非 LIKE：媒体库标题/标签/作者的模糊搜索是高频操作，
// 万级数据下全表扫描的代价不可控（对照扫描的其它聚合筛选，见
// docs/DOMAIN_RULES §3）。trigram 分词（migrations/0002_search_fts）使
// 中文任意子串可检索，这一选择 2026-08-29 在 SQLite 3.53.3 实测通过
// （MATCH 不支持 2 字短词，查询侧因此统一用 instr 子串语义，见
// internal/store/queries/browse.sql 的 q_json 谓词注释）。
//
// 边界（见 docs/ARCHITECTURE.md §5）：
//   - 职责：FTS5 查询语义（关键词解析）与索引重建
//   - 索引的增量维护由迁移 0002 的数据库触发器承担（assets 及其关联表的
//     写路径全部经 store 层 SQL，无需业务代码显式调索引）——本包不重复
//     维护逻辑，只保留【重建】这个运维兜底入口（索引损坏/升级回填后自检用）。
package search
