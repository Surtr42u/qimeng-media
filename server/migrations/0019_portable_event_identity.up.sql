-- Preserve append-only view_events: legacy NULL client IDs get a durable
-- identity in a side table, never an UPDATE of historical events.
-- No index changes on existing tables; existing query plans are unchanged.
CREATE TABLE portable_event_identity (
    event_id INTEGER PRIMARY KEY REFERENCES view_events(id),
    stable_id TEXT NOT NULL UNIQUE
);
INSERT INTO portable_event_identity (event_id, stable_id)
SELECT id, 'portable:' || lower(hex(randomblob(16)))
FROM view_events WHERE client_event_id IS NULL;
CREATE TRIGGER portable_event_identity_insert AFTER INSERT ON view_events
WHEN NEW.client_event_id IS NULL
BEGIN
    INSERT INTO portable_event_identity (event_id, stable_id)
    VALUES (NEW.id, 'portable:' || lower(hex(randomblob(16))));
END;
