-- 0017_prune_legacy_alias_authors.up：清理旧项目迁移遗留的重复别名空壳作者行。
--
-- 背景（2026-10-11 手机端作者管理排查）：旧项目数据迁移（2026-09-15 批
-- qimeng-backup 导入）在部分设备上，对「规范身份已存在、但名字写法不同」的
-- 作者另落了一行独立作者——身份 id 取自别名单词（'ryan_reos'、'skrevenant'…），
-- 与规范身份（'ryanreos'、'skrev'…）同一人却零关联零文件；作者管理页因此
-- 多出 6 行「0 个文件」空壳（实测手机端 153 位 vs 规范 147 位）。
-- 别名写法此后按解析器口径（authoring.ParseAuthorBlocks：编号行全部别名以
-- " / " 连接为 displayName，GUIDE_AUTHOR 格式规范）并入规范行（如
-- 'skrev / KassioppiaVA / SKRevenant'，2026-10-11 经 POST /authors/import-txt
-- 重导作者总表落地；该显示名 2026-10-12 按用户要求剔除 KassioppiaVA、收敛为
-- 'skrev / SKRevenant'，见 CHANGELOG 第五百三十一笔），空壳行只剩冗余。
--
-- 边界（只删历史空壳，不动任何有效作者行）：
--   - 白名单：仅这 6 个已知迁移遗留 id；白名单外的零关联行可能是用户按名新建
--     的作者（ADR-0024 修订 4）或 TXT 格式 C「只创建作者不关联文件」的产物，
--     属设计内形态，一律保留（与 DELETE /authors/import-txt 的「作者行保留」
--     重建语义一致）；
--   - origin='import'：用户按名新建的行是 'client'，双保险不误伤；
--   - 零关联：仍被任何文件引用的行不删；
--   - 幂等：无匹配行时为空操作，可重复执行（历史导出含这 6 行的旧包再导入会
--     重新落行——已知边界记档于 HANDOVER §5，届时重跑本迁移即清）。
DELETE FROM authors
WHERE id IN ('dude017', 'fgnilin', 'kassioppiava', 'ryan_reos', 'skrevenant', 'therealzoh')
  AND origin = 'import'
  AND id NOT IN (SELECT author_id FROM asset_authors);
