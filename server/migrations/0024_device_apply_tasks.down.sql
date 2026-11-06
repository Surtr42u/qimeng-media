-- 0024_device_apply_tasks.down: remove the device apply ledger tables.
-- Child first because device_apply_segments references device_apply_tasks;
-- SQLite drops the child indexes with the tables.
DROP TABLE device_apply_segments;
DROP TABLE device_apply_tasks;
