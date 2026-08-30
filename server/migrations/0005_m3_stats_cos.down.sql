-- 0005_m3_stats_cos.down：回滚 0005。与 up 三节一一对应，逆序执行。
-- asset_daily_stats 是物化视图（唯一真相源 = view_events 事件流），
-- DROP 本表不丢任何统计数据，可随时重建（见 up 文件第 3 节注释）。
DROP INDEX IF EXISTS idx_asset_daily_stats_day;
DROP TABLE IF EXISTS asset_daily_stats;
ALTER TABLE libraries DROP COLUMN kind;
ALTER TABLE authors DROP COLUMN followed;
