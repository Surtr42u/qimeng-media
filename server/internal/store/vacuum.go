// vacuum.go：库文件在线快照的 SQL 执行（VACUUM INTO）与快照完整性校验。
//
// 为什么放在 store 包而不是 backup 包：SQL 属 store 边界（ARCHITECTURE §5，
// store 是唯一数据访问层）；backup 包只负责调度/轮转/目录管理，快照执行器
// 由组装方（main）以函数注入，两层解耦各自可测。完整性校验要经 SQLite 驱动
// 只读打开快照文件，同属 SQL 边界，故与 VACUUM INTO 同文件。
//
// VACUUM INTO 语义（SQLite ≥3.27）：把当前数据库的一致性快照写入目标文件，
// 主库继续可读可写（WAL 模式下不停服）；目标文件若已存在则报错（本项目的
// 快照文件名带秒级时间戳，正常流程不会撞名）。与 sqlc 的关系：VACUUM 是
// 非常规语句，sqlc 不支持解析，故此处为 store 包内唯一手写 SQL——文件名
// 走参数绑定（?），不存在字符串拼接注入面。
package store

import (
	"database/sql"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
)

// driverName 是 database/sql 注册的 SQLite 驱动名（store.go blank import
// modernc.org/sqlite 完成注册；单一来源，Open 与快照校验共用）。
const driverName = "sqlite"

// quickCheckSQL 快照完整性校验语句：PRAGMA quick_check 做全库 B 树一致性
// 快扫（比 full_check 少索引间交叉核对，够发现截断/页损坏——快照的损坏
// 形态是落盘位腐与半途拷贝，不是索引与表数据的轻度不一致）。
const quickCheckSQL = "PRAGMA quick_check"

// quickCheckOK 是 quick_check 在健康库上返回的唯一一行结果；出现任何其他
// 行即视为损坏。
const quickCheckOK = "ok"

// quickCheckReportMaxLines 校验失败记日志的异常行上限：损坏库可能返回海量
// 行，日志只取头部（够定性损坏即可——日志纪律「循环内禁止逐条日志」见
// docs/OBSERVABILITY.md）。
const quickCheckReportMaxLines = 5

// snapshotReadOnlyDSN 快照校验的只读连接 DSN：mode=ro 经 SQLITE_OPEN_URI
// 由 SQLite 核心转为只读打开（缺文件直接报错，绝不静默建库——空库的
// quick_check 恰好也是 "ok"，不锁只读会把「快照丢了」误判成「快照完好」），
// 叠加 _query_only=1（连接级 PRAGMA，modernc 驱动原生支持）双保险杜绝任何
// 写路径。路径以 file: 前缀 + ToSlash 拼 DSN，与 Open() 的 file: DSN 同款
// 写法（仓库既有先例）；快照路径由服务端从 DataDir 拼接而来，不含 ?/# 等
// URI 保留字符。
const snapshotReadOnlyDSN = "file:%s?mode=ro&_query_only=1"

// VacuumInto 把 db 的一致性快照写入 destPath（VACUUM INTO，在线不停服），
// 写完随即对快照文件做完整性校验（quick_check）。校验不过会删除损坏文件
// 并返回错误——备份是唯一灾备手段，「快照存在但坏」比「没有快照」更危险
// （要等到恢复日才发现），故默认强制校验、不做配置开关（主流备份工具同样
// 默认校验）；性能口径：quick_check 是全量读扫，与 VACUUM INTO 的全量写
// 同量级，个人库规模（数十 MB 级）下单次快照耗时约翻倍，可接受。
// 调用方（backup 包）负责目标目录存在性与轮转清理；destPath 必须指向
// 一个不存在的文件（SQLite 语义：已存在报错，防误覆盖）。
func VacuumInto(db *sql.DB, destPath string) error {
	if _, err := db.Exec("VACUUM INTO ?", destPath); err != nil {
		return fmt.Errorf("store: VACUUM INTO 快照 %s: %w", destPath, err)
	}
	if err := verifySnapshotIntegrity(destPath); err != nil {
		return fmt.Errorf("store: 快照完整性校验未通过 %s: %w", destPath, err)
	}
	return nil
}

// verifySnapshotIntegrity 对写盘完成的快照文件做 quick_check 校验：健康
// 返回 nil；损坏（打不开/查询报错/结果非 "ok"）则 slog.Warn 记录异常行、
// 删除损坏文件并返回错误——不允许「快照存在但坏」静默留存（轮转会把它
// 当可信副本保留），删除后由调用方（backup.Manager.Create）的既有错误
// 路径自然上抛，本轮快照按失败处理。
func verifySnapshotIntegrity(destPath string) error {
	if _, err := os.Stat(destPath); err != nil {
		// 快照刚写完就 stat 不到 = 落盘本身不可信，与损坏同口径处理
		//（此时无文件可删，直接报错）。
		return fmt.Errorf("store: 快照文件不可访问 %s: %w", destPath, err)
	}
	// 连接在删文件前必须关干净：损坏路径要物理删除快照，Windows 下句柄
	// 未释放时 Remove 撞 sharing violation（连接池归还≠句柄已关）。
	problems, qerr := func() ([]string, error) {
		check, err := sql.Open(driverName, fmt.Sprintf(snapshotReadOnlyDSN, filepath.ToSlash(destPath)))
		if err != nil {
			return nil, fmt.Errorf("打开快照校验连接失败: %w", err)
		}
		defer func() { _ = check.Close() }()
		return collectQuickCheck(check)
	}()
	if qerr != nil {
		return dropCorruptSnapshot(destPath, qerr, nil)
	}
	if len(problems) > 0 {
		return dropCorruptSnapshot(destPath, errQuickCheckFailed, problems)
	}
	return nil
}

// errQuickCheckFailed 是 quick_check 可执行但结果非 "ok" 的哨兵（区别于
// 打不开/查询失败——日志里可区分「文件坏了」与「校验器跑不了」）。
var errQuickCheckFailed = errors.New("store: quick_check 结果异常")

// collectQuickCheck 执行 quick_check 并收集异常行；problems 为空且 err 为
// nil 表示健康（结果恰为一行 "ok"）。
func collectQuickCheck(db *sql.DB) ([]string, error) {
	rows, err := db.Query(quickCheckSQL)
	if err != nil {
		return nil, fmt.Errorf("执行 quick_check 失败: %w", err)
	}
	defer func() { _ = rows.Close() }()
	var problems []string
	for rows.Next() {
		var line string
		if err := rows.Scan(&line); err != nil {
			return nil, fmt.Errorf("读取 quick_check 结果失败: %w", err)
		}
		if line == quickCheckOK {
			continue
		}
		problems = append(problems, line)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("遍历 quick_check 结果失败: %w", err)
	}
	return problems, nil
}

// dropCorruptSnapshot 记 Warn（附 quick_check 异常行头部与根因）并删除
// 损坏快照，把根因错误返回给调用方。删除失败不吞——坏快照删不掉就必须
// 显性失败（否则轮转把它转正留存），两个错误 join 一起上抛。
func dropCorruptSnapshot(destPath string, cause error, problems []string) error {
	report := problems
	if len(report) > quickCheckReportMaxLines {
		report = report[:quickCheckReportMaxLines]
	}
	slog.Warn("备份快照完整性校验失败，已删除损坏快照", "path", destPath, "problems", report, "err", cause)
	if rmErr := os.Remove(destPath); rmErr != nil && !errors.Is(rmErr, fs.ErrNotExist) {
		return errors.Join(cause, fmt.Errorf("删除损坏快照 %s 失败: %w", destPath, rmErr))
	}
	return cause
}
