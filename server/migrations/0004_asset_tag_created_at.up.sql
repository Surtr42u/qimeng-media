-- 0004_asset_tag_created_at.up：asset_tags 补充关联时间戳。
--
-- 需求来源：docs/LEGACY_REQUIREMENTS.md §A —— 资产详情页标签弹窗按
-- "最近添加置顶"排序（即按关联时间倒序），筛选面板等其他场景保持名字序；
-- PUT /assets/{id}/tags 为整体替换语义（先清空后重挂），关联时间随每行重插
-- 自然刷新（重添加即置顶，无需额外逻辑）。
--
-- 只加不改（ADR-0011）：追加可空列（默认值 epoch）+ 存量回填，不动既有列。
-- 为什么默认值是 epoch 而非当前时间：历史关联时间不可考（表结构从未记录），
-- 统一视为"最旧"既诚实又保证新关联永远排在前面；服务端业务代码写入时
-- 显式传当前时间（AddAssetTag 的 created_at 参数），DEFAULT 只是
-- "漏写路径"的最后防线。epoch 串符合全库时间戳格式（0001 文件头：
-- UTC + RFC3339 固定毫秒），字典序性质不受影响。
--
-- SQLite 3.35+ 支持 DROP COLUMN（down 可回滚）；ALTER TABLE 行内注释不适用，
-- 全部说明见上。
ALTER TABLE asset_tags
ADD COLUMN created_at TEXT NOT NULL DEFAULT '1970-01-01T00:00:00.000Z';
