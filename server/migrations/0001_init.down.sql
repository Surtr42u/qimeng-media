-- 0001_init.down：回滚 0001_init。按创建逆序 DROP（有 FK 依赖时逆序最稳）。
-- 与 up 一一对应；INDEX 随所属表一起删除，无需单独 DROP。
DROP TABLE IF EXISTS daily_shown;
DROP TABLE IF EXISTS trash_items;
DROP TABLE IF EXISTS timeline_tags;
DROP TABLE IF EXISTS favorites;
DROP TABLE IF EXISTS likes;
DROP TABLE IF EXISTS view_events;
DROP TABLE IF EXISTS asset_authors;
DROP TABLE IF EXISTS authors;
DROP TABLE IF EXISTS asset_tags;
DROP TABLE IF EXISTS tags;
DROP TABLE IF EXISTS asset_characters;
DROP TABLE IF EXISTS assets;
DROP TABLE IF EXISTS users;
DROP TABLE IF EXISTS libraries;
