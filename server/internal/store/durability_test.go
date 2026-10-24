package store

import (
	"context"
	"database/sql"
	"path/filepath"
	"testing"
)

// 测量临时合成库单事务提交成本，不用耗时断言，也不代替真实断电验收。
func BenchmarkCommitDurability(b *testing.B) {
	for _, mode := range []string{"NORMAL", "FULL"} {
		b.Run(mode, func(b *testing.B) {
			conn, _ := openTestDB(b)
			conn.SetMaxOpenConns(1)
			if _, err := conn.Exec("PRAGMA synchronous=" + mode); err != nil {
				b.Fatal(err)
			}
			if _, err := conn.Exec("INSERT INTO kv_settings(key,value,updated_at) VALUES('synthetic-durability','0','2026-10-21T00:00:00.000Z')"); err != nil {
				b.Fatal(err)
			}
			b.ResetTimer()
			for n := 0; n < b.N; n++ {
				tx, err := conn.Begin()
				if err != nil {
					b.Fatal(err)
				}
				if _, err := tx.Exec("UPDATE kv_settings SET value=? WHERE key='synthetic-durability'", n); err != nil {
					if rollbackErr := tx.Rollback(); rollbackErr != nil {
						b.Fatal(rollbackErr)
					}
					b.Fatal(err)
				}
				if err := tx.Commit(); err != nil {
					b.Fatal(err)
				}
			}
		})
	}
}

func TestDurabilityOnEveryConnectionAndReopen(t *testing.T) {
	path := filepath.Join(t.TempDir(), "durability.db")
	conn, err := Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := conn.Close(); err != nil {
			t.Error(err)
		}
	}()
	if err := Migrate(conn); err != nil {
		t.Fatal(err)
	}
	ctx := context.Background()
	// 同时持有两个连接，证明不是只在 Open 后偶然配置了第一条连接。
	var held []*sql.Conn
	defer func() {
		for _, c := range held {
			if err := c.Close(); err != nil {
				t.Error(err)
			}
		}
	}()
	for range 2 {
		c, err := conn.Conn(ctx)
		if err != nil {
			t.Fatal(err)
		}
		held = append(held, c)
		var mode int
		if err := c.QueryRowContext(ctx, "PRAGMA synchronous").Scan(&mode); err != nil {
			t.Fatal(err)
		}
		if mode != 2 { // SQLite 官方 FULL 的 PRAGMA 整数值。
			t.Fatalf("新连接 synchronous=%d，期望 FULL(2)", mode)
		}
	}
	for _, c := range held {
		if err := c.Close(); err != nil {
			t.Fatal(err)
		}
	}
	held = nil
	conn.SetMaxIdleConns(0)
	// 清空空闲池后必重新建立连接，防止新连接/重连回退到默认值。
	var mode int
	if err := conn.QueryRow("PRAGMA synchronous").Scan(&mode); err != nil || mode != 2 {
		t.Fatalf("重连没有保持 FULL: %d, %v", mode, err)
	}
}
