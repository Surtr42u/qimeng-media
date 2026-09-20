-- 0012_asset_tag_set_time.up：资产标签组改动时间（DOMAIN_RULES §10 标签组同步
-- 语义，2026-09-20 用户拍板）。
-- 为什么加列：导入备份要按「谁新听谁」对标签组整体替换——没有每资产的标签组
-- 改动时刻，"最新"就无从判定，导入只能并集只增不删（被移除的标签同步后复活）。
-- '' 哨兵 = 未知（存量行 + 并集导入路径）：任一侧未知一律回退并集合并，宁可少
-- 替换也不造假版本。随动更新点 = store/queries/tags.sql 的 TouchAssetTagSet
-- 调用方（替换式 PUT / 单关联解绑 / 删标签级联清关联 / 导入改写）。
ALTER TABLE assets ADD COLUMN tag_set_updated_at TEXT NOT NULL DEFAULT '';
