-- 0001_init.up：M1 初始库结构（全库第一张 migration，只增不改，见 AI_README_FIRST「迁移唯一」）
--
-- ── 全库时间戳统一约定（所有表必须遵守）──
-- created_at / updated_at / deleted_at / expires_at / started_at / mtime 一律为
--   TEXT NOT NULL（可空语义字段除外），内容格式固定：
--   UTC + RFC3339 毫秒，形如 2026-08-22T12:34:56.789Z
-- 为什么选 TEXT 而不是 INTEGER Unix 时间戳：
--   1) 同格式下字典序 == 时间序，keyset 分页按 (created_at, asset_id) 复合排序的
--      正确性直接依赖这一性质，无需额外转换；
--   2) SQLite 内置 date()/strftime() 原生可解析，便于排查与聚合调试；
--   3) 与 openapi.yaml 的 date-time（RFC3339）序列化零转换。
-- 写入方约定：全部由服务端 Go 代码经 store 包的 FormatTimestamp 生成，禁止各处手拼。
--
-- ── 「日」字段统一约定 ──
-- likes.day / daily_shown.day 为 TEXT，格式 YYYY-MM-DD，按服务器本地时区取日界
-- （单用户 NAS 场景，"每日点赞重置/每日展示计数"以用户所在日历日为准）。

-- libraries：媒体库注册表。一个库 = 一个根目录（NAS 挂载卷内的绝对路径）。
CREATE TABLE libraries (
    -- 库标识，服务端生成 UUID，M1 通常只有一行（默认库）
    id          TEXT PRIMARY KEY,
    -- 显示名（用户可见）
    name        TEXT NOT NULL,
    -- 库根目录绝对路径；扫描/路径安全校验（SECURITY.md 红线 1/6）都以此为根
    -- UNIQUE：同一根路径注册两个库会导致两份记录指向同一批文件，扫描互相踩踏
    root_path   TEXT NOT NULL UNIQUE,
    created_at  TEXT NOT NULL           -- 格式见文件头约定
);

-- users：用户表。M1 单用户只写一行，但结构第一天就按多用户设计
-- （SECURITY.md「鉴权设计」：多用户并发写入不在 M1 范围，改表结构却要走 migration）。
CREATE TABLE users (
    id            TEXT PRIMARY KEY,     -- 用户标识，服务端生成 UUID
    -- 登录名。UNIQUE：多用户下天然唯一标识；M1 单行约束同样成立
    name          TEXT NOT NULL UNIQUE,
    -- argon2id 哈希（SECURITY.md），永不存明文
    password_hash TEXT NOT NULL,
    -- 角色：M1 仅 admin；预留 viewer（访客只读）等扩展
    role          TEXT NOT NULL,
    -- Bearer token 的哈希（只存哈希，泄露应急时整列重置即可吊销，SECURITY.md）
    token_hash    TEXT NOT NULL,
    created_at    TEXT NOT NULL
);

-- assets：媒体资产主表——本项目身份机制核心（adr/0004）。
-- 主键 asset_id 终身不变；路径只是属性，改名/移动只 UPDATE 路径，全部关联数据无感保留。
CREATE TABLE assets (
    -- UUIDv7（服务端生成，时间有序）。身份字段：upsert 冲突时永不覆盖（见 UpsertAsset 查询）
    asset_id    TEXT PRIMARY KEY,
    library_id  TEXT NOT NULL REFERENCES libraries(id) ON DELETE CASCADE,
                                        -- 库删除 = 该库资产全部移除（含文件外关联，回收站兜底）
    -- 规范化相对路径：'/' 分隔、库根内、无 ../ 逃逸。与 library_id 组合唯一
    rel_path    TEXT NOT NULL,
    file_name   TEXT NOT NULL,          -- 展示用文件名（含扩展名）
    -- 三类媒体（DOMAIN_RULES §11）；CHECK 拦截非法类型入库
    media_type  TEXT NOT NULL CHECK (media_type IN ('image', 'animated_image', 'video')),
    size_bytes  INTEGER NOT NULL,       -- 变更检测字段 1/2（与 mtime 联合判断是否重探元数据）
    mtime       TEXT NOT NULL,          -- 变更检测字段 2/2，格式见文件头
    duration_ms INTEGER,                -- 视频/动图时长；图片为 NULL
    width       INTEGER,                -- 探测元数据，未探明为 NULL
    height      INTEGER,
    -- SourceMatcher 产出的出处规范名（DOMAIN_RULES §4）；未命中为 NULL（归「其他」）
    source      TEXT,
    created_at  TEXT NOT NULL,          -- 首次入库时间（upsert 不更新，保冷启动 freshness 口径）
    updated_at  TEXT NOT NULL,
    -- 路径唯一（adr/0004）：路径是属性但同一库内不允许两条记录指向同一文件；
    -- 同路径重复扫描由 UpsertAsset 走 DO UPDATE 分支合并，身份不变
    UNIQUE (library_id, rel_path)
);
-- keyset 分页与按时间排序的复合索引：(created_at DESC, asset_id DESC)
-- 与 ListAssetsFirstPage/AfterCursor 的 ORDER BY 完全匹配，翻页免全表排序
CREATE INDEX idx_assets_created ON assets (created_at DESC, asset_id DESC);

-- asset_characters：资产 ↔ 出场角色（SourceMatcher 扫描时产出，DOMAIN_RULES §4）。
-- 角色唯一标识是「出处+角色名」，此处按资产存角色规范名，跨出处同名角色天然不冲突
-- （同一资产的 source 唯一）。
CREATE TABLE asset_characters (
    asset_id        TEXT NOT NULL REFERENCES assets(asset_id) ON DELETE CASCADE,
    character_name  TEXT NOT NULL,      -- 角色规范名；多角色由扫描器拆成多行
    PRIMARY KEY (asset_id, character_name)   -- 复合主键 = 同资产角色不重复
);

-- tags：全局标签池（DOMAIN_RULES §7：浏览中新标签自动入池；标签不写入媒体文件）。
CREATE TABLE tags (
    id          TEXT PRIMARY KEY,       -- 服务端生成 UUID
    -- 全局唯一：标签池是平铺命名空间，重名会让筛选语义混乱
    name        TEXT NOT NULL UNIQUE,
    created_at  TEXT NOT NULL
);

-- asset_tags：资产 ↔ 标签多对多。
CREATE TABLE asset_tags (
    asset_id  TEXT NOT NULL REFERENCES assets(asset_id) ON DELETE CASCADE,
    tag_id    TEXT NOT NULL REFERENCES tags(id)      ON DELETE CASCADE,
                                        -- 级联清理：DOMAIN_RULES §7「删除标签清理全部关联」
    PRIMARY KEY (asset_id, tag_id)      -- 复合主键 = 同资产同标签只挂一次
);

-- authors：作者表（DOMAIN_RULES §6：常规作者与 COS 作者两套隔离来源）。
CREATE TABLE authors (
    -- generateAuthorId 规则（保留中日文 Unicode/去特殊符号/空格转下划线/小写）；
    -- COS 作者同规则加 cos_ 前缀——前缀隔离使两套来源同名不冲突，id 即唯一身份
    id            TEXT PRIMARY KEY,
    display_name  TEXT NOT NULL,
    type          TEXT NOT NULL CHECK (type IN ('regular', 'cos')),
    created_at    TEXT NOT NULL
);

-- asset_authors：资产 ↔ 作者多对多（DOMAIN_RULES §6：文件与作者多对多；
-- 删除作者保留文件（清关联行），删除文件清理关联——两侧都是 CASCADE 清关联行）。
CREATE TABLE asset_authors (
    asset_id   TEXT NOT NULL REFERENCES assets(asset_id)  ON DELETE CASCADE,
    author_id  TEXT NOT NULL REFERENCES authors(id)       ON DELETE CASCADE,
    PRIMARY KEY (asset_id, author_id)
);

-- view_events：行为事件流（adr/0005，本项目统计体系的唯一底座）。
-- ★ 只追加（INSERT ONLY）：任何代码禁止对本表执行 UPDATE / DELETE——
--   统计口径变更要能全量重算历史，改一行历史就永久失去可信度（旧项目教训）。
-- ★ 无唯一约束：同会话是否重复计数属于服务端写入时的判定规则（口径集中一处），
--   不该由表结构隐式决定。
CREATE TABLE view_events (
    -- 单调递增整数 id：天然按写入序排列，AUTOINCREMENT 保证 id 永不复用（审计语义）
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    -- 故意不设外键：事件流是不可变的历史数据，资产删除（回收站→物理清除）后事件必须
    -- 保留供历史统计；若设 FK CASCADE 会连带删事件（违反只追加），NO ACTION 又会阻塞
    -- 资产删除。仍引用 asset_id 值而非路径（adr/0004「永不引用路径」）。
    asset_id    TEXT NOT NULL,
    -- open=进入详情（图片 viewCount）/ play=点击播放（视频 playCount）/ dwell=停留时长
    kind        TEXT NOT NULL CHECK (kind IN ('open', 'play', 'dwell')),
    -- 会话标识：同会话 open/play 只计一次的判定依据（DOMAIN_RULES §5，写入侧判定）
    session_id  TEXT NOT NULL,
    started_at  TEXT NOT NULL,          -- 事件发生时间，格式见文件头
    seconds     INTEGER                 -- dwell 停留秒数；open/play 为 NULL
);
-- 单资产计数聚合（CountAssetEvents 类查询）与排行榜按周期过滤各需一条索引
CREATE INDEX idx_view_events_asset   ON view_events (asset_id, kind);
CREATE INDEX idx_view_events_started ON view_events (started_at);

-- likes：点赞记录（DOMAIN_RULES §5：每资产每日可赞一次，次日重置；累计永久保留）。
-- 行本身即事件式记录：当日是否已赞查本表；累计 likeCount = 该资产全部行数（聚合）。
CREATE TABLE likes (
    asset_id    TEXT NOT NULL REFERENCES assets(asset_id) ON DELETE CASCADE,
    -- YYYY-MM-DD（服务器本地时区日界，见文件头「日」字段约定）
    day         TEXT NOT NULL,
    created_at  TEXT NOT NULL,
    -- 唯一约束 = 「每资产每日一次」的数据库层兜底（并发/重试双击也不会翻倍）
    PRIMARY KEY (asset_id, day)
);

-- favorites：收藏（DOMAIN_RULES §7：布尔标记集合，无次数）。asset_id 即主键。
CREATE TABLE favorites (
    asset_id    TEXT PRIMARY KEY REFERENCES assets(asset_id) ON DELETE CASCADE,
    created_at  TEXT NOT NULL
);

-- timeline_tags：视频内时间轴标签（DOMAIN_RULES §7：与文件标签完全独立的体系，
-- 点击跳转回看；仅视频有意义，不做类型 CHECK——约束放服务端写入侧）。
CREATE TABLE timeline_tags (
    id            TEXT PRIMARY KEY,     -- 服务端生成 UUID
    asset_id      TEXT NOT NULL REFERENCES assets(asset_id) ON DELETE CASCADE,
    time_millis   INTEGER NOT NULL,     -- 视频内毫秒时间点
    name          TEXT NOT NULL,
    created_at    TEXT NOT NULL
);

-- trash_items：回收站元数据（SECURITY.md「回收站」：删除=移入回收站，物理删除仅限
-- 回收站内的显式操作；文件本体在 /data/trash/ 下按原相对路径存放，本表是可恢复索引）。
CREATE TABLE trash_items (
    id              TEXT PRIMARY KEY,   -- 回收站条目 ID，服务端生成 UUID
    original_path   TEXT NOT NULL,      -- 删除前的库内相对路径（恢复目标）
    file_name       TEXT NOT NULL,
    size_bytes      INTEGER NOT NULL,
    deleted_at      TEXT NOT NULL,
    expires_at      TEXT NOT NULL,      -- 到期后台物理清除（默认 30 天，可配置）
    -- 删除时点资产元数据快照（asset_id/宽高/媒体类型等），恢复时零损重建 assets 行
    metadata_json   TEXT NOT NULL
);

-- daily_shown：每日展示计数（DOMAIN_RULES §1.4：dailyPenalty 输入；推荐展示即 +1）。
-- 「当日零点重置」由查询侧处理：查询永远 WHERE day = 当天，新的一天自然是空集，
-- 历史行不清除（可回溯当日实际展示分布）。
CREATE TABLE daily_shown (
    asset_id  TEXT NOT NULL REFERENCES assets(asset_id) ON DELETE CASCADE,
    day       TEXT NOT NULL,            -- YYYY-MM-DD，见文件头「日」字段约定
    count     INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (asset_id, day)         -- 每资产每日恰好一行，upsert 自增（缓存性质，可丢弃重建）
);
