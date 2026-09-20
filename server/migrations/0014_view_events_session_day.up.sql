-- 0014：view_events 会话去重数据库层兜底（审计 R10，2026-09-20）。
--
-- 背景：open/play 的「同资产+同类型+同会话+同日只计一次」（DOMAIN_RULES §5）
-- 原实现 = handler 先查 ExistsViewEventOnDay 再插入（先查后插），并发双击
-- 存在 TOCTOU 极窄窗口——两个请求都通过存在性检查后各插一行，view_count
-- 双计。本迁移加数据库层兜底：部分唯一索引让输家的 INSERT 静默 0 行，
-- handler 原有的「0 行 → 202 不计数」路径自动吸收，先查后插降级为快路径。
--
-- 为什么需要显式 day 列：去重窗口是「本地日历日」（DOMAIN_RULES §5），
-- 而 started_at 存 UTC 串——UTC 日期前缀 ≠ 本地日（本地零点不在 UTC 零点），
-- 无法用表达式索引从 started_at 派生；时区随部署机走，SQL 内硬编码偏移
-- 不可移植。day 由写入侧用与物化表同源的 store.FormatDay 填充，口径同源。
--
-- 为什么只对 open/play 生效（部分索引）：dwell 是累加量不去重（§5）；
-- 索引 WHERE 子句把 dwell 排除在外，秒数累加路径零影响。
--
-- 存量/导入行为：day 可空，存量行与 qimeng-backup 导入回放（DOMAIN_RULES
-- §10）置 NULL——SQLite 唯一索引对 NULL 不做唯一判定，旧行互不冲突也不
-- 拦截新写入（0010 client_event_id 同款先例）；导入回放的幂等本就由内容
-- 键 client_event_id 承担，且导入共用常量 session_id，若 day 非空会被本
-- 索引误吞同日增量事件，故导入侧显式写 NULL。
--
-- 只加不改（ADR-0011）：新增列 + 新增索引；down 先删索引再 DROP COLUMN
--（modernc SQLite 3.4x 支持，同 0004/0009/0010 先例）。
ALTER TABLE view_events ADD COLUMN day TEXT;
CREATE UNIQUE INDEX idx_view_events_session_day
    ON view_events (asset_id, kind, session_id, day)
    WHERE kind IN ('open', 'play');
