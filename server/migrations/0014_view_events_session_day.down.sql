-- 0014_view_events_session_day.down：回滚会话去重兜底列与索引。
-- 回滚后并发双击退回「先查后插」的 TOCTOU 窗口（审计 R10 原状）；
-- 列/索引可重建，事件数据零损失。
DROP INDEX IF EXISTS idx_view_events_session_day;
ALTER TABLE view_events DROP COLUMN day;
