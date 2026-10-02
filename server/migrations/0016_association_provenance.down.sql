-- 0016_association_provenance.down：删除溯源双列（ADR-0032）。
--
-- down 仅测试与灾备链路使用（生产禁用）；SQLite 3.35+ DROP COLUMN 可回滚。
-- 删列即放弃行级溯源证据：origin='legacy' 与 NULL/epoch 时间哨兵随列消失，
-- 不可考回退为"永远不可考"——与升级前行为一致（迁移前本就无溯源）。
ALTER TABLE asset_authors DROP COLUMN origin;
ALTER TABLE asset_authors DROP COLUMN created_at;
ALTER TABLE asset_tags DROP COLUMN origin;
ALTER TABLE asset_characters DROP COLUMN origin;
ALTER TABLE asset_characters DROP COLUMN created_at;
ALTER TABLE authors DROP COLUMN origin;
