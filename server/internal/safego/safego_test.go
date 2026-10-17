// safego_test.go：panic 兜底行为测试。锁死两件事：①fn 的 panic 被恢复
// （不会让测试进程崩溃）；②恢复日志带 task 名与 panic 值（可检索、可告警）。
package safego

import (
	"bytes"
	"log/slog"
	"strings"
	"testing"
	"time"
)

// TestGoRecoversPanic 验证 panic 被恢复并记录结构化日志。
func TestGoRecoversPanic(t *testing.T) {
	var buf bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&buf, nil))
	done := make(chan struct{})

	Go(logger, "测试任务", func() {
		defer close(done) // defer 先注册后 panic：panic 被 recover 后才会执行
		panic("boom")
	})

	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("panic goroutine 未结束（超时）")
	}
	// 日志写入在 goroutine 内完成，done 关闭后可能有极小延迟；轮询等待。
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if strings.Contains(buf.String(), "boom") {
			break
		}
		time.Sleep(5 * time.Millisecond)
	}
	out := buf.String()
	if !strings.Contains(out, `"task":"测试任务"`) {
		t.Errorf("恢复日志缺 task 字段: %s", out)
	}
	if !strings.Contains(out, "boom") {
		t.Errorf("恢复日志缺 panic 值: %s", out)
	}
	if !strings.Contains(out, "stack") {
		t.Errorf("恢复日志缺堆栈字段: %s", out)
	}
}

// TestGoNilLoggerFallsBackToDefault：logger 为 nil 时回落 slog.Default，
// 不 panic（避免兜底代码自己成为崩溃源）。
func TestGoNilLoggerFallsBackToDefault(t *testing.T) {
	done := make(chan struct{})
	Go(nil, "nil-logger", func() {
		defer close(done)
		panic("x")
	})
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("goroutine 未结束")
	}
}
