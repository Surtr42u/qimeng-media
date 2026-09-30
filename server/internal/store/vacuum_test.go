// vacuum_test.go：快照写入与完整性校验单测（真 SQLite 文件驱动，不走替身
// ——quick_check 的判定行为只能对真库文件锁定）。
// 覆盖：VACUUM INTO 全链（写+校验一次通过）、完好好快照校验通过、截断
// 损坏的快照校验报错且文件被删、快照缺失报错（只读打开的防空库误判防线）。
package store

import (
	"database/sql"
	"os"
	"path/filepath"
	"testing"
)

// newTestConn 开一个真实 SQLite 库（迁移不必上——VACUUM INTO 只关心库文件
// 本身，与表结构无关）。
func newTestConn(t *testing.T) *sql.DB {
	t.Helper()
	conn, err := Open(filepath.Join(t.TempDir(), "src.db"))
	if err != nil {
		t.Fatalf("打开测试库失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	return conn
}

// TestVacuumIntoSnapshotVerified 全链：VACUUM INTO 写完即内联校验（健康库
// 的 quick_check 恰好一行 ok），快照在盘且独立复检同样通过。
func TestVacuumIntoSnapshotVerified(t *testing.T) {
	conn := newTestConn(t)
	dest := filepath.Join(t.TempDir(), "snap.db")
	if err := VacuumInto(conn, dest); err != nil {
		t.Fatalf("VacuumInto 失败: %v", err)
	}
	st, err := os.Stat(dest)
	if err != nil {
		t.Fatalf("快照未落盘: %v", err)
	}
	if st.Size() == 0 {
		t.Fatal("快照文件不应为空")
	}
	if err := verifySnapshotIntegrity(dest); err != nil {
		t.Fatalf("完好快照校验不应报错: %v", err)
	}
}

// TestVerifySnapshotRejectsCorrupt 损坏快照（模拟落盘后位腐/半途拷贝）：
// 截断到文件头都不完整的极小尺寸（确定性损坏，不依赖截断点落进哪个 B 树页
// 的偶然性），校验必须报错且损坏文件已被删除——不允许坏快照静默留存。
func TestVerifySnapshotRejectsCorrupt(t *testing.T) {
	conn := newTestConn(t)
	dest := filepath.Join(t.TempDir(), "snap.db")
	if err := VacuumInto(conn, dest); err != nil {
		t.Fatalf("前置 VacuumInto 失败: %v", err)
	}
	// 16 字节 < SQLite 文件头 100 字节，quick_check 必然报损。
	if err := os.Truncate(dest, 16); err != nil {
		t.Fatalf("截断快照失败: %v", err)
	}
	if err := verifySnapshotIntegrity(dest); err == nil {
		t.Fatal("损坏快照校验必须报错")
	}
	if _, err := os.Stat(dest); !os.IsNotExist(err) {
		t.Fatalf("损坏快照应已删除, stat err = %v", err)
	}
}

// TestVerifySnapshotMissingFile 快照文件缺失必须报错：只读打开（mode=ro）
// 下缺失文件不会被他建空库，空库的 quick_check 恰好是 "ok"——此用例锁定
// 「缺失 ≠ 完好」的防线。
func TestVerifySnapshotMissingFile(t *testing.T) {
	if err := verifySnapshotIntegrity(filepath.Join(t.TempDir(), "gone.db")); err == nil {
		t.Fatal("缺失快照校验必须报错（防空库误判）")
	}
}
