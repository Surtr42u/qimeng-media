-- 0009_timeline_tag_color.down：回滚颜色列。
-- 该列只服务时间轴标签颜色展示（协议批 P2 #32），无下游依赖，直接 DROP。
ALTER TABLE timeline_tags DROP COLUMN color;
