-- 0007_library_enabled.up：库启用/停用开关（用户 2026-09-03 拍板：关闭但不删记录）。
--
-- enabled = 0 的库从"用户面查询"中隐藏（browse 列表/计数、推荐/排行输入池；
-- 搜索谓词在 browse 主查询内，自动一并隐藏），但一切记录保留：assets 及全部
-- 关联、view_events 事件流、统计物化、回收站条目均不动，重开即恢复。
-- 边界（有意为之）：
--   - 详情/签名直链不过滤：已获取的 assetId 与链接保持稳定；
--   - 磁盘文件与扫描不受影响：开关只作用于展示面；
--   - 管理面（库列表/文件计数）不过滤：文件管理页需要看到并重新启用停用库。
-- 与库类型（kind）正交：kind 管"如何识别与展示"，enabled 管"是否展示"
-- （kind 体系见 docs/adr/0012）。
ALTER TABLE libraries
ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1;
