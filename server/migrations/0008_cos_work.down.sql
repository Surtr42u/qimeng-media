-- 0008_cos_work.down：回滚 COS 作品列。
-- cos_work 是纯派生列（值恒可由 rel_path 重新推导，见 up 文件注释），
-- 丢弃不产生不可恢复数据；回滚后重新执行 up 的回填即可完全还原。
-- 索引随列删除自动消失，但显式 DROP 保证 down/up 反复执行时的确定性
-- （golang-migrate 按版本号顺序执行，显式清理不依赖 SQLite 的隐式行为）。
DROP INDEX IF EXISTS idx_assets_cos_work;

ALTER TABLE assets DROP COLUMN cos_work;
