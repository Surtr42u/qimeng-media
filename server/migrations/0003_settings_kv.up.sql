-- 0003_settings_kv.up：服务端级 KV 设置表（M3 起，首装字段：推荐偏好）。
--
-- 为什么建一张通用 KV 表而不是给推荐偏好单独建表：
--   1) 偏好类设置是键值语义（某功能点的一份 JSON），后续设置项（界面偏好、
--      搜索偏好等）只会继续增多；表结构演进按"加一行键"即可，不用为每个
--      新设置走一次 migration；
--   2) 单用户 NAS 场景（M1 设计前提）没有并发写竞争，KV 查探一次主键索引
--      足够，不需要为某类设置造关系型结构；
--   3) 与旧项目 appPrefs.recommendationPrefs 的迁移映射天然对位
--      （DOMAIN_RULES §10：旧数据迁移时该段原样进本表）。
-- 值语义约定：value 字段存 JSON 文本，键的具体解释权在读取侧（本表不感知）；
-- 键名命名规则为 <功能点>_<设置名>（如 recommend_prefs）。
--
-- 时间戳约定同 0001 文件头：TEXT NOT NULL，UTC + RFC3339 毫秒。
-- updated_at 保留而非省略：设置变更可回溯（排障时"最后一次改动是什么时候"）。
CREATE TABLE kv_settings (
    key         TEXT PRIMARY KEY,       -- 设置键（如 recommend_prefs）
    value       TEXT NOT NULL,          -- 设置值（JSON 文本）
    updated_at  TEXT NOT NULL           -- 最近写入时间，格式见文件头
);
