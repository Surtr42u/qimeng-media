-- 0004_asset_tag_created_at.down：回滚关联时间列。
-- 该列只用于详情页标签排序（LEGACY_REQUIREMENTS §A），无下游依赖，直接 DROP。
ALTER TABLE asset_tags DROP COLUMN created_at;
