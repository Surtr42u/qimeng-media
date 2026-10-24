-- Completed upload replay uses one primary-key lookup; cleanup is bounded by retention.
-- name: GetUploadReceipt :one
SELECT * FROM upload_receipts WHERE upload_id = ?;

-- name: SaveUploadReceipt :exec
INSERT INTO upload_receipts (upload_id, request_hash, asset_id, size_bytes, completed_at)
VALUES (?, ?, ?, ?, ?);

-- name: DeleteExpiredUploadReceipts :exec
DELETE FROM upload_receipts WHERE completed_at < ?;
