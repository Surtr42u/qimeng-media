-- 0010_client_event_id.down：回滚客户端幂等键。
-- 幂等键只服务上报写入去重（任务L L5），无统计/查询下游依赖，先索引后列直接删。
DROP INDEX idx_view_events_client_event_id;
ALTER TABLE view_events DROP COLUMN client_event_id;
