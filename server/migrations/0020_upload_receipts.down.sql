-- 仅隔离迁移回放使用；生产降级保留表，避免丢失完成凭据。
DROP TABLE upload_receipts;
