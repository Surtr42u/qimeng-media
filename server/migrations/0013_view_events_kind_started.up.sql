-- 0013：view_events 补 (kind, started_at) 复合索引（审计 R4，2026-09-20）。
-- stats 族查询（CountOpenEventsAll/CountOpenEventsOnDay/SummarizeOpenWindow/
-- Top* 榜单）谓词 = kind 等值 + started_at 窗口范围；既有
-- idx_view_events_asset(asset_id, kind) 与 idx_view_events_started(started_at)
-- 的前导列均不能服务 kind 过滤，CountOpenEventsAll 只能全表扫。本索引把
-- 该族全表扫变索引范围扫，查询零改动。只加不改（ADR-0011）。
-- 物化表方案（overview 读每日聚合）本批明确不做——口径逐位等价无法离线
-- 验证（审计 R4 结论）；触发条件再议：overview P95 > 500ms。
CREATE INDEX idx_view_events_kind_started ON view_events (kind, started_at);
