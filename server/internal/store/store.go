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

// FormatTimestamp 把时间格式化为全库统一时间戳（UTC + 毫秒）。
func FormatTimestamp(t time.Time) string {
	return t.UTC().Format(TimestampLayout)
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
//
// busyTimeoutMS SQLite busy_timeout（毫秒）：写锁被占时的等待上限，
// 与上方 Open 注释「等待 15 秒」联动——调整须两处同步。
const busyTimeoutMS = 15000

func Open(path string) (*sql.DB, error) {
	dsn := fmt.Sprintf("file:%s?_pragma=busy_timeout(%d)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)&_txlock=immediate",
		path, busyTimeoutMS)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("store: 打开数据库 %s: %w", path, err)
	}
	// sql.Open 是惰性的：立即 Ping 触发真实连接，把 DSN/路径错误暴露在此处
	// 而不是推迟到第一次查询（那时定位困难）。
	if err := db.Ping(); err != nil {
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
