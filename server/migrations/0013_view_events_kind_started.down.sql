-- 0013_view_events_kind_started.down：回滚 stats 族复合索引。
-- 索引可再生（只影响查询计划，不触数据），回滚零数据损失。
DROP INDEX IF EXISTS idx_view_events_kind_started;
