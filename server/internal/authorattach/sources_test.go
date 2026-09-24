package authorattach

// 片段存取测试 + 本包共享的测试基建（真 SQLite：临时目录 + store.Open +
// Migrate，照抄 httpapi browse_test.go 模式；本包测试直接构造 db.Queries，
// 不依赖 httpapi——ADR-0019：多步流可脱离 HTTP 单测）。

import (
	"context"
	"path/filepath"
	"reflect"
	"testing"
	"time"

	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// testNow 是全包共用的固定时钟（包内禁 time.Now，可测性纪律的测试侧对偶）。
var testNow = time.Date(2026, 9, 25, 10, 0, 0, 0, time.UTC)

// newTestDB 组装已迁移的测试库连接（t.TempDir 随用例清理）。
func newTestDB(t *testing.T) *db.Queries {
	t.Helper()
	conn, err := store.Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatalf("打开测试库失败: %v", err)
	}
	t.Cleanup(func() {
		if err := conn.Close(); err != nil {
			t.Logf("关闭测试库失败: %v", err)
		}
	})
	if err := store.Migrate(conn); err != nil {
		t.Fatalf("迁移失败: %v", err)
	}
	return db.New(conn)
}

func TestLoadSourcesNoRecord(t *testing.T) {
	q := newTestDB(t)
	sources, err := LoadSources(context.Background(), q)
	if err != nil {
		t.Fatalf("读取片段失败: %v", err)
	}
	if sources != nil {
		t.Errorf("无记录 sources=%v, want nil", sources)
	}
}

func TestPersistLoadSourcesRoundtrip(t *testing.T) {
	q := newTestDB(t)
	ctx := context.Background()
	want := []Source{
		{Filename: "a.txt", Content: "1  aaa\n", ImportedAt: store.FormatTimestamp(testNow)},
		{Filename: "b.txt", Content: "1  bbb\n来源\nkemono\n"},
	}
	if err := PersistSources(ctx, q, testNow, want); err != nil {
		t.Fatalf("写片段失败: %v", err)
	}
	got, err := LoadSources(ctx, q)
	if err != nil {
		t.Fatalf("读片段失败: %v", err)
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("roundtrip=%+v, want %+v", got, want)
	}
}

func TestMostRecent(t *testing.T) {
	if _, ok := MostRecent(nil); ok {
		t.Error("空输入应返回 false")
	}
	if _, ok := MostRecent([]Source{}); ok {
		t.Error("空切片应返回 false")
	}
	older := store.FormatTimestamp(testNow)
	newer := store.FormatTimestamp(testNow.Add(time.Hour))
	// 字典序最大者胜。
	got, ok := MostRecent([]Source{
		{Filename: "a.txt", ImportedAt: older},
		{Filename: "b.txt", ImportedAt: newer},
		{Filename: "c.txt", ImportedAt: store.FormatTimestamp(testNow.Add(time.Minute))},
	})
	if !ok || got.Filename != "b.txt" {
		t.Errorf("got=%+v ok=%v, want b.txt（importedAt 最大）", got, ok)
	}
	// 平局取数组靠后。
	got, ok = MostRecent([]Source{
		{Filename: "a.txt", ImportedAt: older, Content: "a"},
		{Filename: "b.txt", ImportedAt: older, Content: "b"},
	})
	if !ok || got.Filename != "b.txt" {
		t.Errorf("got=%+v ok=%v, want b.txt（平局取靠后）", got, ok)
	}
	// 旧片段零值（无 importedAt 字段的历史数据）= 最旧，输给任何时间戳。
	got, ok = MostRecent([]Source{
		{Filename: "old.txt", ImportedAt: "", Content: "old"},
		{Filename: "new.txt", ImportedAt: older, Content: "new"},
	})
	if !ok || got.Filename != "new.txt" {
		t.Errorf("got=%+v ok=%v, want new.txt（零值=最旧）", got, ok)
	}
}
