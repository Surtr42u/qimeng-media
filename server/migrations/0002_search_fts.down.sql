-- 0002_search_fts.down：回滚全文搜索索引。按创建逆序：触发器 → 虚表 → 视图。
DROP TRIGGER IF EXISTS authors_fts_u;
DROP TRIGGER IF EXISTS asset_authors_fts_ad;
DROP TRIGGER IF EXISTS asset_authors_fts_ai;
DROP TRIGGER IF EXISTS asset_characters_fts_ad;
DROP TRIGGER IF EXISTS asset_characters_fts_ai;
DROP TRIGGER IF EXISTS tags_fts_u;
DROP TRIGGER IF EXISTS asset_tags_fts_ad;
DROP TRIGGER IF EXISTS asset_tags_fts_ai;
DROP TRIGGER IF EXISTS assets_fts_ad;
DROP TRIGGER IF EXISTS assets_fts_au;
DROP TRIGGER IF EXISTS assets_fts_ai;
DROP TABLE IF EXISTS assets_fts;
DROP VIEW IF EXISTS asset_search_text;
