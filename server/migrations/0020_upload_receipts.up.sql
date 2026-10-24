-- 上传完成凭据与资产行同事务提交，响应丢失/客户端或服务端重启可重放结果。
-- 只新增旁表，不改历史资产、事件或现有索引。主键仅影响本表查询计划。
-- 不设资产外键：删除资产后仍保留短期墓碑，拒绝重试把已删除文件重新上传。
CREATE TABLE upload_receipts (
    upload_id TEXT PRIMARY KEY NOT NULL,
    request_hash TEXT NOT NULL,
    asset_id TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    completed_at INTEGER NOT NULL
);
