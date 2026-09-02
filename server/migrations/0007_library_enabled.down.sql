-- 0007_library_enabled.down：回滚开关列（纯展示层开关，无不可恢复数据）。
ALTER TABLE libraries DROP COLUMN enabled;
