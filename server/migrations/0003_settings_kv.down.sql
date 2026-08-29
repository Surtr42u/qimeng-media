-- 0003_settings_kv.down：回滚 0003_settings_kv（单表无外键，直接 DROP）。
DROP TABLE IF EXISTS kv_settings;
