-- 0015 回滚：删覆盖索引（只加不改，ADR-0011；索引可再生，无数据影响）。
DROP INDEX IF EXISTS idx_view_events_asset_kind_started;
