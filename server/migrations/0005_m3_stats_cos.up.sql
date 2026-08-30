-- 0005_m3_stats_cos.up：M3 统计模块落地 + COS 库标记预留（只加不改，ADR-0011）。
--
-- 本文件一文件三节（同批小改动合并一个迁移，编号不跳号）：
--   1) authors.followed 关注标记（DOMAIN_RULES §6 关注作者）
--   2) libraries.kind 库类型标记（COS 库预留，后续作者体系任务使用）
--   3) asset_daily_stats 按天聚合物化表（DOMAIN_RULES §5 统计唯一真相源的物化视图）
--
-- ── 「日」字段约定 ──
-- asset_daily_stats.day 为 TEXT，格式 YYYY-MM-DD，按服务器本地时区取日界
-- （与 likes.day / daily_shown.day 同格式同口径，见 0001 文件头「日」字段约定）。

-- ── 第 1 节：authors.followed ──
-- 关注是作者维度的布尔标记（DOMAIN_RULES §6「关注作者」/ §5 收藏口径：
-- 无次数、取消即清除）；SQLite 无布尔类型，统一用 INTEGER 0/1
-- （与全库 CHECK 约束风格一致）。DEFAULT 0 = 存量作者默认未关注，
-- 取消关注不删除作者及其文件，仅清标记。
ALTER TABLE authors
ADD COLUMN followed INTEGER NOT NULL DEFAULT 0;

-- ── 第 2 节：libraries.kind ──
-- 库类型标记：normal 常规媒体库 / cos COS 目录库（DOMAIN_RULES §6 COS 目录
-- 结构扫描）。本任务只建列（M3 统计先行落地），COS 库的扫描与独立入口由
-- 后续作者体系任务接线；存量库 DEFAULT 'normal' 行为不变。
ALTER TABLE libraries
ADD COLUMN kind TEXT NOT NULL DEFAULT 'normal' CHECK (kind IN ('normal', 'cos'));

-- ── 第 3 节：asset_daily_stats ──
-- 「文件×天」按天聚合表（DOMAIN_RULES §5：唯一真相源 = ViewEvent 事件流，
-- 本表是物化视图）。数字卡、趋势、窗口排行全部同源同口径。
-- ★ 可随时由 view_events 全量重建（重建入口 httpapi.RebuildAssetDailyStatsFromEvents）：
--   Down 时丢弃本表不丢真相——事件流才是唯一权威数据。
-- ★ 增量累加语义：view/play/dwell 事件写入时按 delta upsert 累加
--   （与事件流逐条 INSERT 保持最终一致，见 store/queries/daily_stats.sql）。
CREATE TABLE asset_daily_stats (
    -- 与 view_events.asset_id 同语义（adr/0004：引用 asset_id 而非路径）；
    -- 资产行删除时级联清理聚合行（事件流仍保留，重建可回填）
    asset_id       TEXT NOT NULL REFERENCES assets(asset_id) ON DELETE CASCADE,
    day            TEXT NOT NULL,          -- 本地日历日 YYYY-MM-DD，见文件头约定
    view_count     INTEGER NOT NULL DEFAULT 0, -- open 事件累计（同会话当日去重后）
    play_count     INTEGER NOT NULL DEFAULT 0, -- play 事件累计（同会话当日去重后）
    browse_seconds INTEGER NOT NULL DEFAULT 0, -- dwell 停留秒数累计（不去重）
    PRIMARY KEY (asset_id, day)            -- 每资产每日恰好一行，upsert 累加
);
-- 趋势按天扫描（GROUP BY day）专用索引；主键 (asset_id, day) 服务单资产窗口查询
CREATE INDEX idx_asset_daily_stats_day ON asset_daily_stats (day);
