-- 0002_search_fts.up：全文搜索索引（SQLite FTS5 trigram）。
--
-- 为什么 trigram 而非默认 unicode61：媒体库搜索词是中文文件名/标签/角色的
-- 任意子串（DOMAIN_RULES §3「contains 子串匹配」），unicode61 按空白拆分 token，
-- 中文整段文字成为单个 token、无法命中中间子串；trigram 按 3 连字符建 token，
-- 支持任意位置子串查询。配套查询侧统一用 LIKE（trigram 对 LIKE 自动走索引且
-- 支持 1-2 字符的短词；MATCH 短词会静默返回空集——2026-08-29 实测验证，
-- SQLite 3.53.3）。
--
-- 搜索文本是六维聚合（文件名/文件夹路径/标签/作者/角色/出处，旧项目
-- applyFilter 口径），只由触发器维护，业务代码零感知：assets 及其全部关联表
-- 的写路径都经 store 层 SQL（触发器覆盖），与 upsert/move/restore/tag CRUD
-- 全部自动同步。聚合计算收敛在一个 VIEW（asset_search_text）——触发器只
-- 重复三行 boilerplate，不会出现七处拼接公式漂移。
--
-- 本文件同时是 sqlc 的 schema 来源（SQL 解析容忍 VIRTUAL TABLE/TRIGGER/VIEW，
-- 2026-08-29 实测 sqlc v1.31.1）。

-- asset_search_text：一处定义全文聚合公式。视图内仅查询，不产生独立存储。
-- all_text 各维度以空格拼接：空格分隔保证跨维度不产生假命中
-- （旧项目口径是任一维度 contains，拼接串里的空格保留了该语义）。
-- 目录段 = rel_path 去掉文件名与末尾 '/'（根目录文件为空串）。
CREATE VIEW asset_search_text AS
SELECT
    a.asset_id,
    a.file_name || ' '
    || trim(substr(a.rel_path, 1, length(a.rel_path) - length(a.file_name)), '/') || ' '
    || COALESCE((SELECT group_concat(t.name, ' ')
                 FROM asset_tags at JOIN tags t ON t.id = at.tag_id
                 WHERE at.asset_id = a.asset_id), '') || ' '
    || COALESCE((SELECT group_concat(c.character_name, ' ')
                 FROM asset_characters c
                 WHERE c.asset_id = a.asset_id), '') || ' '
    || COALESCE((SELECT group_concat(au.display_name, ' ')
                 FROM asset_authors aa JOIN authors au ON au.id = aa.author_id
                 WHERE aa.asset_id = a.asset_id), '') || ' '
    || COALESCE(a.source, '')
    AS all_text
FROM assets a;

-- assets_fts：全文索引表。rowid 与 assets 的隐式 rowid 一一对应（JOIN 走 O(1) 点查）；
-- asset_id 列 UNINDEXED 仅作审计/重建锚点，不参与检索。
CREATE VIRTUAL TABLE assets_fts USING fts5(
    all_text,
    asset_id UNINDEXED,
    tokenize='trigram'
);

-- 重建某资产在 FTS 中的行：先删后插（FTS5 虚表没有 INSERT OR REPLACE）。
-- 各触发器共用的配方；本文件结尾的存量回填与 assets_fts_ai 同构，
-- 改公式必须同时改回填语句。

-- ---- 触发器：assets 主表 ----

CREATE TRIGGER assets_fts_ai AFTER INSERT ON assets BEGIN
    DELETE FROM assets_fts WHERE rowid = NEW.rowid;
    INSERT INTO assets_fts(rowid, all_text, asset_id)
    SELECT a.rowid, v.all_text, a.asset_id
    FROM assets a JOIN asset_search_text v ON v.asset_id = a.asset_id
    WHERE a.asset_id = NEW.asset_id;
END;

CREATE TRIGGER assets_fts_au AFTER UPDATE OF file_name, rel_path, source ON assets BEGIN
    DELETE FROM assets_fts WHERE rowid = OLD.rowid;
    INSERT INTO assets_fts(rowid, all_text, asset_id)
    SELECT a.rowid, v.all_text, a.asset_id
    FROM assets a JOIN asset_search_text v ON v.asset_id = a.asset_id
    WHERE a.asset_id = NEW.asset_id;
END;

CREATE TRIGGER assets_fts_ad AFTER DELETE ON assets BEGIN
    DELETE FROM assets_fts WHERE rowid = OLD.rowid;
END;

-- ---- 触发器：标签（赋值/改名/删除回调）----
-- asset_tags 挂标签/摘标签只影响该标签关联的单个资产行，按 NEW/OLD.asset_id 精确重建。

CREATE TRIGGER asset_tags_fts_ai AFTER INSERT ON asset_tags BEGIN
    DELETE FROM assets_fts WHERE rowid = (SELECT rowid FROM assets WHERE asset_id = NEW.asset_id);
    INSERT INTO assets_fts(rowid, all_text, asset_id)
    SELECT a.rowid, v.all_text, a.asset_id
    FROM assets a JOIN asset_search_text v ON v.asset_id = a.asset_id
    WHERE a.asset_id = NEW.asset_id;
END;

CREATE TRIGGER asset_tags_fts_ad AFTER DELETE ON asset_tags BEGIN
    DELETE FROM assets_fts WHERE rowid = (SELECT rowid FROM assets WHERE asset_id = OLD.asset_id);
    INSERT INTO assets_fts(rowid, all_text, asset_id)
    SELECT a.rowid, v.all_text, a.asset_id
    FROM assets a JOIN asset_search_text v ON v.asset_id = a.asset_id
    WHERE a.asset_id = OLD.asset_id;
END;

-- 标签池改名：影响所有引用该标签的资产（批量重建该集合）。

CREATE TRIGGER tags_fts_u AFTER UPDATE OF name ON tags BEGIN
    DELETE FROM assets_fts WHERE rowid IN (
        SELECT a.rowid FROM assets a JOIN asset_tags at ON at.asset_id = a.asset_id
        WHERE at.tag_id = OLD.id);
    INSERT INTO assets_fts(rowid, all_text, asset_id)
    SELECT a.rowid, v.all_text, a.asset_id
    FROM assets a
    JOIN asset_tags at ON at.asset_id = a.asset_id
    JOIN asset_search_text v ON v.asset_id = a.asset_id
    WHERE at.tag_id = OLD.id;
END;

-- ---- 触发器：角色（SourceMatcher 产出，M3 接入后自动同步）----

CREATE TRIGGER asset_characters_fts_ai AFTER INSERT ON asset_characters BEGIN
    DELETE FROM assets_fts WHERE rowid = (SELECT rowid FROM assets WHERE asset_id = NEW.asset_id);
    INSERT INTO assets_fts(rowid, all_text, asset_id)
    SELECT a.rowid, v.all_text, a.asset_id
    FROM assets a JOIN asset_search_text v ON v.asset_id = a.asset_id
    WHERE a.asset_id = NEW.asset_id;
END;

CREATE TRIGGER asset_characters_fts_ad AFTER DELETE ON asset_characters BEGIN
    DELETE FROM assets_fts WHERE rowid = (SELECT rowid FROM assets WHERE asset_id = OLD.asset_id);
    INSERT INTO assets_fts(rowid, all_text, asset_id)
    SELECT a.rowid, v.all_text, a.asset_id
    FROM assets a JOIN asset_search_text v ON v.asset_id = a.asset_id
    WHERE a.asset_id = OLD.asset_id;
END;

-- ---- 触发器：作者（常规/COS 双体系同此同步）----

CREATE TRIGGER asset_authors_fts_ai AFTER INSERT ON asset_authors BEGIN
    DELETE FROM assets_fts WHERE rowid = (SELECT rowid FROM assets WHERE asset_id = NEW.asset_id);
    INSERT INTO assets_fts(rowid, all_text, asset_id)
    SELECT a.rowid, v.all_text, a.asset_id
    FROM assets a JOIN asset_search_text v ON v.asset_id = a.asset_id
    WHERE a.asset_id = NEW.asset_id;
END;

CREATE TRIGGER asset_authors_fts_ad AFTER DELETE ON asset_authors BEGIN
    DELETE FROM assets_fts WHERE rowid = (SELECT rowid FROM assets WHERE asset_id = OLD.asset_id);
    INSERT INTO assets_fts(rowid, all_text, asset_id)
    SELECT a.rowid, v.all_text, a.asset_id
    FROM assets a JOIN asset_search_text v ON v.asset_id = a.asset_id
    WHERE a.asset_id = OLD.asset_id;
END;

-- 作者改名（display_name）：影响所有引用该作者的资产。

CREATE TRIGGER authors_fts_u AFTER UPDATE OF display_name ON authors BEGIN
    DELETE FROM assets_fts WHERE rowid IN (
        SELECT a.rowid FROM assets a JOIN asset_authors aa ON aa.asset_id = a.asset_id
        WHERE aa.author_id = OLD.id);
    INSERT INTO assets_fts(rowid, all_text, asset_id)
    SELECT a.rowid, v.all_text, a.asset_id
    FROM assets a
    JOIN asset_authors aa ON aa.asset_id = a.asset_id
    JOIN asset_search_text v ON v.asset_id = a.asset_id
    WHERE aa.author_id = OLD.id;
END;

-- 存量回填：0001 升级上来的库已有资产，建表后补索引行（公式与 assets_fts_ai 同构）。
INSERT INTO assets_fts(rowid, all_text, asset_id)
SELECT a.rowid, v.all_text, a.asset_id
FROM assets a JOIN asset_search_text v ON v.asset_id = a.asset_id;
