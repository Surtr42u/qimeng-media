package uploadsess

import (
	"bytes"
	"io"
	"log/slog"
	"os"
	"testing"
	"time"
)

func newCompletedSession(t *testing.T) (*Manager, Snapshot) {
	t.Helper()
	m := New(t.TempDir(), nil, slog.New(slog.NewTextHandler(io.Discard, nil)))
	snap, err := m.Create("synthetic-library", "test.jpg", "", 4)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.Append(snap.ID, 0, bytes.NewBufferString("data")); err != nil {
		t.Fatal(err)
	}
	return m, snap
}

func TestSealedSessionRejectsFurtherWrites(t *testing.T) {
	m, snap := newCompletedSession(t)
	seal, err := m.Seal(snap.ID)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.Append(snap.ID, 4, bytes.NewBufferString("bad")); err != ErrSizeOverflow {
		t.Fatal(err)
	}
	if content, err := os.ReadFile(seal.Path); err != nil || string(content) != "data" {
		t.Fatalf("sealed source changed: %q %v", content, err)
	}
	// 重复Seal不得截断已发布源；外部篡改必须显式失败。
	if err := os.WriteFile(seal.Path, []byte("longer content"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Seal(snap.ID); err != ErrSizeOverflow {
		t.Fatal(err)
	}
	if content, err := os.ReadFile(seal.Path); err != nil || string(content) != "longer content" {
		t.Fatal("repeated Seal truncated published data")
	}
}

func TestDropAndCloseWaitForCompletion(t *testing.T) {
	for _, closeManager := range []bool{false, true} {
		t.Run(map[bool]string{false: "Drop", true: "Close"}[closeManager], func(t *testing.T) {
			m, snap := newCompletedSession(t)
			seal, err := m.Seal(snap.ID)
			if err != nil {
				t.Fatal(err)
			}
			unlock, err := m.LockCompletion(snap.ID)
			if err != nil {
				t.Fatal(err)
			}
			done := make(chan error, 1)
			go func() {
				if closeManager {
					done <- m.Close()
				} else {
					done <- m.Drop(snap.ID)
				}
			}()
			select {
			case err := <-done:
				unlock()
				t.Fatalf("cleanup bypassed completion: %v", err)
			case <-time.After(30 * time.Millisecond):
			}
			if _, err := os.Stat(seal.Path); err != nil {
				unlock()
				t.Fatal("source removed while completing", err)
			}
			unlock()
			select {
			case err := <-done:
				if err != nil {
					t.Fatal(err)
				}
			case <-time.After(time.Second):
				t.Fatal("cleanup remained blocked")
			}
		})
	}
}
