-- 0023_maintenance_jobs.down: remove the three ledger tables.
-- Child tables first because maintenance_steps/maintenance_artifacts reference
-- maintenance_jobs; SQLite drops their indexes with the tables.
DROP TABLE maintenance_artifacts;
DROP TABLE maintenance_steps;
DROP TABLE maintenance_jobs;
