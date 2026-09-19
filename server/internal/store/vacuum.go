// vacuum.go：库文件在线快照的 SQL 执行（VACUUM INTO）。
//
// 为什么放在 store 包而不是 backup 包：SQL 属 store 边界（ARCHITECTURE §5，
// store 是唯一数据访问层）；backup 包只负责调度/轮转/目录管理，快照执行器
// 由组装方（main）以函数注入，两层解耦各自可测。
//
// VACUUM INTO 语义（SQLite ≥3.27）：把当前数据库的一致性快照写入目标文件，
// 主库继续可读可写（WAL 模式下不停服）；目标文件若已存在则报错（本项目的
// 快照文件名带秒级时间戳，正常流程不会撞名）。与 sqlc 的关系：VACUUM 是
// 非常规语句，sqlc 不支持解析，故此处为 store 包内唯一手写 SQL——文件名
// 走参数绑定（?），不存在字符串拼接注入面。
package store

import (
	"database/sql"
	"fmt"
)

// VacuumInto 把 db 的一致性快照写入 destPath（VACUUM INTO，在线不停服）。
// 调用方（backup 包）负责目标目录存在性与轮转清理；destPath 必须指向
// 一个不存在的文件（SQLite 语义：已存在报错，防误覆盖）。
func VacuumInto(db *sql.DB, destPath string) error {
	if _, err := db.Exec("VACUUM INTO ?", destPath); err != nil {
		return fmt.Errorf("store: VACUUM INTO 快照 %s: %w", destPath, err)
	}
	return nil
}
