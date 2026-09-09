-- 0009_timeline_tag_color.up：时间轴标签颜色列（协议批 P2 #32）。
--
-- 需求来源：待拍板 #32 b)——旧版时间轴标签有颜色/预设类型，此前客户端只能
-- 按 name 前缀约定兜底，跨端一致性存疑。协议化后标签对象带可选 color
-- （hex 6 位，如 "#d6336c"），创建/更新/响应三处透传；预设类型不入协议
-- （客户端按 color 自持，DOMAIN_RULES §7）。
--
-- 只加不改（ADR-0011）：追加 NOT NULL DEFAULT '' 列，不动既有列。
-- 为什么 NOT NULL 空串哨兵而不是可空列：空串 = "未设置颜色"，与 Go 侧
-- 生成协议 pointer 字段（空串 → 省略 JSON 字段）一一对应，查询与扫描
-- 代码不需要 NULL 分支；SQLite modernc v1.57（引擎 3.4x）支持 DROP COLUMN，
-- down 直接回滚（同 0004 先例）。
ALTER TABLE timeline_tags
ADD COLUMN color TEXT NOT NULL DEFAULT '';
