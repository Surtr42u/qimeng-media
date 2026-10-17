-- 0018_perf_indexes.up：补齐四类高频查询的缺失索引（2026-10-17 审计，
-- 只增不改不删，ADR-0011）。
--
-- 为什么需要（均为静态可证的索引缺口，查询文件对应注释见括号）：
--   1. asset_authors(author_id)：复合主键是 (asset_id, author_id)，按作者
--      反查（GET /authors 的 LEFT JOIN、TXT 统一重建的 DELETE、扫描收尾
--      孤儿清理、作者改名 UPDATE）全部只能全表扫/临时自动索引
--      （queries/authors.sql:28/74/137/177）。
--   2. asset_tags(tag_id)：同一形态；GET /tags 列表与删标签前取受影响资产
--      按 tag_id 反查（queries/tags.sql:12/101）。
--   3. likes(day, asset_id)：复合主键是 (asset_id, day)，day 非首列；周期
--      榜单/推荐冷算按 day 窗口聚合是全表扫（queries/recommend.sql:102-107）。
--      覆盖索引让窗口聚合免回表。
--   4. assets(mtime DESC, asset_id DESC)：默认浏览排序自 2026-09-17 起为
--      fileDate=mtime，而既有唯一排序索引 idx_assets_created(created_at…)
--      已不匹配默认列表（queries/browse.sql:45-48/76-86/244-264）。该索引
--      同时服务 mtime 日期窗口过滤；排序表达式为 CASE 时是否命中需以
--      EXPLAIN QUERY PLAN 实测为准（不承诺单独加索引即消除 temp sort）。
--
-- 代价：写入侧每行多一至两次 B-tree 插入（扫描/导入批量写），相对入库本身
-- 的 IO 可忽略；SQLite 单用户场景的读收益远大于写开销。
CREATE INDEX idx_asset_authors_author ON asset_authors (author_id);
CREATE INDEX idx_asset_tags_tag       ON asset_tags (tag_id);
CREATE INDEX idx_likes_day            ON likes (day, asset_id);
CREATE INDEX idx_assets_mtime         ON assets (mtime DESC, asset_id DESC);
