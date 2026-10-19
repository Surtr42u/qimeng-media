-- Read original identities; NULL-client legacy rows use the durable side table.
-- name: ExportPortableEvents :many
SELECT v.asset_id, CAST(COALESCE(v.client_event_id, p.stable_id) AS TEXT) AS event_key,
       v.kind, v.session_id, v.started_at, v.seconds, v.day
FROM view_events v LEFT JOIN portable_event_identity p ON p.event_id = v.id
ORDER BY v.id;

-- name: PortableEventExists :one
SELECT EXISTS (SELECT 1 FROM view_events WHERE client_event_id = sqlc.arg(event_key))
    OR EXISTS (SELECT 1 FROM portable_event_identity WHERE stable_id = sqlc.arg(event_key));

-- Indexed asset-local scan: restoration must not scan all site events.
-- name: ListAssetViewEvents :many
SELECT asset_id, kind, session_id, started_at, seconds FROM view_events
WHERE asset_id = ? ORDER BY id;

-- name: DeleteAssetDailyStats :exec
DELETE FROM asset_daily_stats WHERE asset_id = ?;
