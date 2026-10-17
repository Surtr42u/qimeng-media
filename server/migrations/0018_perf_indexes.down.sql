-- 0018_perf_indexes.down：删除 0018 引入的四个性能索引。
--
-- down 仅测试与灾备链路使用（生产禁用）；本迁移是纯索引新增，回退即
-- DROP 索引，数据与表结构不变，可无损重复 up/down。
DROP INDEX IF EXISTS idx_assets_mtime;
DROP INDEX IF EXISTS idx_likes_day;
DROP INDEX IF EXISTS idx_asset_tags_tag;
DROP INDEX IF EXISTS idx_asset_authors_author;
