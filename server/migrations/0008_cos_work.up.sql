-- 0008_cos_work.up：COS 作品列（相册「角色」胶囊在 COS 分区下的数据源）。
--
-- 背景：COS 库目录结构为 `作者/作品/文件`（DOMAIN_RULES §6 三种形态），旧版
-- 手机端把第二段「作品目录名」当作 COS 的角色维度用（GUIDE_ALGORITHM
-- 「COS 分区分组：出处=作者名、角色=作品名」，workName==authorName 时归「其他」）。
-- 新版 0001 只落地了首段作者（enrich.go ingestCosFile 建 cos_ 作者），
-- 作品名一直留在 rel_path 里没落库，导致相册角色维度在 COS 分区下空白。
--
-- 列语义：
--   - 仅 COS 库资产有值；normal 库恒为 NULL（作者/作品隔离口径，§6）。
--   - 值 = rel_path 去掉首段作者目录后的第一段，即 `作者/作品/...` 的作品名。
--   - NULL 的三种情形：非 COS 库、`作者/文件.jpg` 无作品子目录、库根直放文件。
--     NULL 对应旧版「folderName == authorName → 归其他」，前端兜底「其他」。
--
-- 写入方：scanner（ingestCosFile / recomputeCosAuthor，随 COS 作者关联一起算）。
-- 读取方：browse.sql 的 work 筛选谓词与 facets.sql 的角色维聚合。
-- 存量回填：本文件内的 UPDATE 从 rel_path 一次性推导，**无需重扫库**
-- （5558 行 COS 资产实测 5557 命中、152 个去重作品）。
ALTER TABLE assets
ADD COLUMN cos_work TEXT;

-- 存量回填：仅 COS 库、且 rel_path 至少含一段目录的文件。
-- instr(rel_path,'/') 定位首段作者目录；substr(rel_path, p+1) 得到
-- `作品/...` 剩余串；该剩余串再含 '/' 才存在作品子目录，取其首段。
UPDATE assets
SET cos_work = CASE
    WHEN instr(substr(rel_path, instr(rel_path, '/') + 1), '/') > 0
    THEN substr(substr(rel_path, instr(rel_path, '/') + 1),
                1,
                instr(substr(rel_path, instr(rel_path, '/') + 1), '/') - 1)
    ELSE NULL
    END
WHERE library_id IN (SELECT id FROM libraries WHERE kind = 'cos')
  AND instr(rel_path, '/') > 0;

-- 部分索引：cos_work 只在 COS 库非空（占比极小），NULL 行不进索引，
-- 让「按作品筛选」与「角色维聚合」都走得上索引而索引体积最小。
CREATE INDEX idx_assets_cos_work ON assets (cos_work) WHERE cos_work IS NOT NULL;
