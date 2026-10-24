package store

import (
	"database/sql"
	"errors"
	"fmt"
	"time"

	"github.com/golang-migrate/migrate/v4"
	gmsqlite "github.com/golang-migrate/migrate/v4/database/sqlite"
	"github.com/golang-migrate/migrate/v4/source/iofs"
	// 纯 Go SQLite 驱动（无 CGO，交叉编译无痛，adr/0003）。blank import：
	// 向 database/sql 注册 "sqlite" 驱动名。
	_ "modernc.org/sqlite"

	"qimeng-media/server/migrations"
)

// TimestampLayout 是全库统一的时间戳格式（详见 migrations/0001_init.up.sql 文件头）：
// UTC + RFC3339 固定毫秒。统一格式的根本原因：同格式 TEXT 的字典序 == 时间序，
// keyset 分页 (created_at, asset_id) 复合排序的正确性直接依赖这一性质。
// 所有写库代码（本包之外的 scanner/httpapi 等）都必须经由这两个函数生成
// 时间字符串，禁止各处手拼格式。
const (
	TimestampLayout = "2006-01-02T15:04:05.000Z07:00"
	DayLayout       = "2006-01-02" // 「日」字段格式，服务器本地时区日界
)

// TimestampEpoch 是 asset_tags.created_at 的「不可考→视为最旧」哨兵
// （migration 0004 的列默认值；ADR-0032 记档其与 NULL 哨兵的语义等价性）。
// 唯一消费点：导入补证裁决（legacy_import.sql 的 epoch→透传值 heal）——
// 字符串必须与 0004 DEFAULT 逐字节一致，迁移侧改动须同步此处，反之亦然。
const TimestampEpoch = "1970-01-01T00:00:00.000Z"

// FormatTimestamp 把时间格式化为全库统一时间戳（UTC + 毫秒）。
func FormatTimestamp(t time.Time) string {
	return t.UTC().Format(TimestampLayout)
}

// NullTimestamp 把时间戳字符串包成可空 TEXT 参数（ADR-0032 溯源列专用）：
// 业务写入恒传非空值（Valid=true）；空串只应出现在存量行/哨兵语义场景
// （NULL = 不可考，migration 0016），禁止用它携带业务时间。
func NullTimestamp(s string) sql.NullString {
	return sql.NullString{String: s, Valid: s != ""}
}

// FormatDay 把时间格式化为「日」字段（YYYY-MM-DD，本地时区）。
// 点赞每日重置、每日展示计数都以用户所在日历日为界（DOMAIN_RULES §5/§1.4）。
func FormatDay(t time.Time) string {
	return t.Local().Format(DayLayout)
}

// Open 打开（必要时创建）SQLite 数据库并应用连接级 PRAGMA 与事务模式。
//
// 二者都通过 DSN 参数下发——它们是「每个连接」生效的，database/sql 连接池
// 新建连接时靠 DSN 自动带上，比在 Open 后手写 "PRAGMA ..." 语句可靠
// （后者只作用于恰好执行它的那一条连接）：
//
//   - journal_mode(WAL)：读写不互斥（adr/0003 选 SQLite 的前提——单机
//     数万 QPS 读的前提就是 WAL）；WAL 本身持久化在库文件里，但每个新连接
//     重复声明无害且幂等。
//   - synchronous(FULL)：每事务提交同步 WAL，优先保留已提交的收藏、事件和
//     手工编辑（ADR-0048）。旧 NORMAL 在断电/系统崩溃时可能丢已提交事务；
//     这些用户数据不能靠重扫原件补回。FULL 增加提交 IO，扫描/导入仍使用
//     原有批次事务；存储设备必须正确兑现同步，不能据此宣称原件与 DB 跨系统
//     原子提交或已通过真实断电验收。官方语义与本机合成成本见 ADR。
//   - busy_timeout(15000)：写锁被占时等待 15 秒再返回 SQLITE_BUSY，而不是
//     立刻失败——单进程多协程（扫描器+API）写碰撞靠它吸收。15 秒口径：
//     TXT 重建/备份事件回放是大事务，持锁可达秒级，旧值 5 秒在手机闪存+
//     全量扫描并发下不够（2026-09-15 单机形态首扫 6341 文件期间 scanner
//     入库连续 SQLITE_BUSY(5)，靠轮询补扫才救回）。
//   - _txlock=immediate：事务起步即取写锁。deferred 下「先读后写」的大事务
//     （TXT 重建、备份事件回放）升级写锁时可能撞 SQLITE_BUSY_SNAPSHOT(517)
//     ——读期间 WAL 已被其他写者推进，busy_timeout 对该错误不生效、立即
//     失败（同日实证：「重放 TXT 重建关联失败 database is locked (517)」
//     三连，作者关联全数丢失）。immediate 把写竞态变成排队等待，根治该
//     失败模式；单用户场景写并发本就有限，排队代价可忽略。
//   - foreign_keys(1)：SQLite 默认关闭外键约束，必须逐连接显式开启，
//     否则 migration 里精心设计的 CASCADE 全部形同虚设。
//   - cache_size(-32000)：页缓存 32MiB（负值=KiB）。SQLite 默认 ~2MiB，
//     而单机库体量是十几 MiB 起步——默认值放不下整库，推荐流聚合这类
//     「数百行 × 关联子查询」的访问形态每次 B 树下探都可能冷读闪存，
//     手机上实测单个新 seed 冷算秒级（2026-09-29 用户反馈「刷新半天才
//     加载新内容」的成因之一）。放整库进缓存后，热路径 seek 全走内存。
//     database/sql 默认 MaxIdleConns=2，实际增量 ≤ 2×32MiB 且按需惰性
//     分配，单机形态可接受。
//
// busyTimeoutMS SQLite busy_timeout（毫秒）：写锁被占时的等待上限，
// 与上方 Open 注释「等待 15 秒」联动——调整须两处同步。
const busyTimeoutMS = 15000

// pageCacheKiB SQLite 页缓存上限（KiB，负值=KiB 口径），语义见 Open 注释
// cache_size 条目。与库体量联动——库显著增长（>256MiB）时再评估加大或改
// 页缓存淘汰策略。
const pageCacheKiB = 32000

// 连接池上限（2026-10-17 加固）。为什么需要：DSN 的 cache_size(-32000) 是
// **每连接** 32MiB 页缓存，而 database/sql 默认 MaxOpenConns 不限——facets
// 六路并行聚合、推荐冷算、扫描富化等并发路径可能同时撑开多条连接，手机
// 单机形态（ADR-0015）内存最紧时会与 App 争内存。上限 6 覆盖单用户并发读
// + 写路径（超出时 database/sql 排队；SQLite 本身单写多读，排队不改变
// 正确性，只增加等待）；MaxIdleConns=2 与 Open 注释里既有的「≤2×32MiB」
// 稳态估算对齐。
const (
	maxOpenConns = 6
	maxIdleConns = 2
)

func Open(path string) (*sql.DB, error) {
	dsn := fmt.Sprintf("file:%s?_pragma=busy_timeout(%d)&_pragma=journal_mode(WAL)&_pragma=synchronous(FULL)&_pragma=foreign_keys(1)&_pragma=cache_size(-%d)&_txlock=immediate",
		path, busyTimeoutMS, pageCacheKiB)
	db, err := sql.Open(driverName, dsn)
	if err != nil {
		return nil, fmt.Errorf("store: 打开数据库 %s: %w", path, err)
	}
	// 连接池上限（见上方常量注释，2026-10-17 加固）。
	db.SetMaxOpenConns(maxOpenConns)
	db.SetMaxIdleConns(maxIdleConns)
	// sql.Open 是惰性的：立即 Ping 触发真实连接，把 DSN/路径错误暴露在此处
	// 而不是推迟到第一次查询（那时定位困难）。
	if err := db.Ping(); err != nil {
		// 已携主错误（Ping 失败）上抛；刚 Open 的空连接池 Close 不会产生
		// 需要处理的次生错误，不覆盖主错误（Q6 忽略错误清理批登记豁免）。
		_ = db.Close()
		return nil, fmt.Errorf("store: 连接数据库 %s: %w", path, err)
	}
	return db, nil
}

// Migrate 把嵌入的 migration 全部应用到当前库（幂等，已迁移的版本自动跳过）。
// migration 文件由 server/migrations 包 embed 进二进制（单文件部署，Docker
// 镜像无需 COPY SQL 文件）；演进只能新增 migration 文件（AI_README_FIRST「迁移唯一」）。
func Migrate(db *sql.DB) error {
	m, err := newMigrator(db)
	if err != nil {
		return err
	}
	if err := m.Up(); err != nil && !errors.Is(err, migrate.ErrNoChange) {
		return fmt.Errorf("store: 迁移到最新版本: %w", err)
	}
	return nil
}

// MigrateDown 回退最近 steps 个 migration。仅供测试与灾备使用——
// 生产库禁止回退（down 会 DROP 表）。steps=1 即回退一个版本。
func MigrateDown(db *sql.DB, steps int) error {
	m, err := newMigrator(db)
	if err != nil {
		return err
	}
	if err := m.Steps(-steps); err != nil && !errors.Is(err, migrate.ErrNoChange) {
		return fmt.Errorf("store: 回退 %d 个迁移: %w", steps, err)
	}
	return nil
}

// newMigrator 组装 golang-migrate 实例：
//   - source = iofs（embed FS）；数据库驱动复用 Open 打开的 *sql.DB
//     （WithInstance 模式，连接上已带 WAL/foreign_keys 等 PRAGMA），
//     避免第二次独立开连接造成 PRAGMA 状态不一致。
func newMigrator(db *sql.DB) (*migrate.Migrate, error) {
	src, err := iofs.New(migrations.FS, ".")
	if err != nil {
		return nil, fmt.Errorf("store: 读取内嵌 migrations: %w", err)
	}
	driver, err := gmsqlite.WithInstance(db, &gmsqlite.Config{})
	if err != nil {
		return nil, fmt.Errorf("store: 初始化迁移驱动: %w", err)
	}
	m, err := migrate.NewWithInstance("iofs", src, "sqlite", driver)
	if err != nil {
		return nil, fmt.Errorf("store: 组装迁移器: %w", err)
	}
	return m, nil
}
