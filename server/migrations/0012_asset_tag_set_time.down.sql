-- 0012_asset_tag_set_time.down：回滚标签组改动时间列。
-- 回滚后导入端退回纯并集合并语义（时间判定字段消失 → 恒走并集路径），
-- 已按备份时间替换过的标签组不被恢复（schema 可逆 ≠ 行为无损）。
ALTER TABLE assets DROP COLUMN tag_set_updated_at;
