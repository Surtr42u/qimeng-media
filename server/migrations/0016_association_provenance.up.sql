-- 0016_association_provenance.up：关联/作者类记录溯源体系（ADR-0032）。
--
-- 需求来源：2026-10-02 三方数据取证——asset_authors/asset_tags 等关联表
-- 无时间戳无来源，PC 端多出的 152 条关联"无时间戳可考"，跨端合并裁决只能
-- 靠猜。给关联类记录建立「何时（created_at）+ 经何通道（origin）」的行级
-- 溯源，且该证据随备份载荷跨设备透传（导入裁决语义见 DOMAIN_RULES §10
-- 「关联溯源与行级合并裁决」、docs/adr/0032）。
--
-- 列设计（每表两列，origin 词表常量单源 server/internal/store/provenance.go）：
--   asset_authors    + created_at TEXT（可空）+ origin TEXT NOT NULL DEFAULT 'legacy'
--   asset_tags       + origin（created_at 已由 0004 建立，NOT NULL DEFAULT epoch，
--                      只加不改（ADR-0011）不动既有列；epoch=既有"不可考→最旧"哨兵）
--   asset_characters + created_at TEXT（可空）+ origin
--   authors          + origin（created_at 已有，不动）
--
-- 存量行策略（ADR-0032 决策 1）：
--   - origin DEFAULT 'legacy'：ALTER TABLE ADD COLUMN 时 SQLite 对全部既有行
--     按默认值呈现——'legacy' 如实记录"0016 之前的行不可考"，无需回填 UPDATE；
--     写入端永不主动写 'legacy'（词表注释锁死），它是迁移回填专用哨兵。
--   - 新列 created_at 可空、不给 DEFAULT：存量行 NULL = 时间不可考，禁止伪造
--     （有意区别于 0004 的 epoch 哨兵——asset_tags.created_at 是既有 NOT NULL
--     列只能沿用 epoch，且它驱动"最近添加置顶"排序需要全序；本迁移新列无排序
--     职责，NULL 更诚实。两种哨兵语义等价：都表示不可考）。业务代码写入路径
--     恒显式传当前时刻或备份透传值，NULL 只应出现在存量行。
--   - asset_characters.created_at 语义 = 当前重算发生时刻（扫描器先删后插，
--     重算即刷新——派生数据的"真相更新时刻"，不是首次出现时刻）。
--
-- origin 为什么是 TEXT 而非 CHECK 约束：词表扩枚举（未来协议级客户端标识
-- 区分 app/web 等）不需要迁移；合法性由写入端常量 + 导入端词表校验保证。
--
-- 纯加列零索引：origin/created_at 不建索引——溯源列只服务行级读取与导入
-- 裁决（逐行 upsert 走既有主键），无新查询族，ADR-0011 修订5 的计划横向
-- 复查不适用（同表查询族计划不受加列影响，EXPLAIN 无涉）。
ALTER TABLE asset_authors ADD COLUMN created_at TEXT;
ALTER TABLE asset_authors ADD COLUMN origin TEXT NOT NULL DEFAULT 'legacy';
ALTER TABLE asset_tags ADD COLUMN origin TEXT NOT NULL DEFAULT 'legacy';
ALTER TABLE asset_characters ADD COLUMN created_at TEXT;
ALTER TABLE asset_characters ADD COLUMN origin TEXT NOT NULL DEFAULT 'legacy';
ALTER TABLE authors ADD COLUMN origin TEXT NOT NULL DEFAULT 'legacy';
