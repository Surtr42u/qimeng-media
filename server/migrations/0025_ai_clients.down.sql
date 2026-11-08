-- 0025_ai_clients.down: remove the AI client credentials and audit tables.
-- ai_audit intentionally outlives ai_clients in normal operation; the down
-- migration removes both (development rollback only), indexes drop with tables.
DROP TABLE ai_audit;
DROP TABLE ai_clients;
