package uploadsess

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestCapacityCountsDeclaredBytesOrphansAndReleasesFinishedSession(t *testing.T) {
	m := New(t.TempDir(), nil, nil)
	t.Cleanup(func() {
		if err := m.Close(); err != nil {
			t.Error(err)
		}
	})
	m.SetLimits(Limits{MaxSessions: 1, MaxReservedBytes: 8})
	first, err := m.Create("library", "one.jpg", "", 4)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.Create("library", "two.jpg", "", 1); !errors.Is(err, ErrCapacity) {
		t.Fatalf("会话数量上限未生效: %v", err)
	}
	if err := m.Drop(first.ID); err != nil {
		t.Fatal(err)
	}
	orphan := filepath.Join(m.tempRoot(), tempPrefix+"orphan"+tempSuffix)
	if err := os.WriteFile(orphan, []byte("123456"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Create("library", "two.jpg", "", 3); !errors.Is(err, ErrCapacity) {
		t.Fatalf("孤儿分片未计入预算: %v", err)
	}
	if err := os.Remove(orphan); err != nil {
		t.Fatal(err)
	}
	second, err := m.Create("library", "two.jpg", "", 8)
	if err != nil {
		t.Fatal(err)
	}
	if err := m.Finish(second.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Create("library", "three.jpg", "", 8); err != nil {
		t.Fatal("完成会话未释放预算", err)
	}
}
