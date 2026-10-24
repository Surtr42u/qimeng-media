// safego_test.go：panic 兜底行为测试。锁死两件事：①fn 的 panic 被恢复
// （不会让测试进程崩溃）；②恢复日志带 task 名与 panic 值（可检索、可告警）。
package safego

import (
	"errors"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestRunPreservesErrorAndReportsPanic(t *testing.T) {
	var buf syncBuffer
	logger := slog.New(slog.NewJSONHandler(&buf, nil))
	want := errors.New("ordinary failure")
	if err := Run(logger, "普通错误", func() error { return want }); !errors.Is(err, want) {
		t.Fatalf("普通错误须原样返回: %v", err)
	}
	err := Run(logger, "监听处理", func() error { panic("synthetic panic") })
	if err == nil || !strings.Contains(err.Error(), "synthetic panic") {
		t.Fatalf("panic 必须成为可处理错误: %v", err)
	}
	if out := buf.String(); !strings.Contains(out, `"task":"监听处理"`) || !strings.Contains(out, `"stack"`) {
		t.Fatalf("panic 日志缺任务/堆栈: %s", out)
	}
}

// syncBuffer 是并发安全的日志缓冲：safego 的 recover 日志写在 goroutine 内，
// 测试主 goroutine 同时轮询读取内容——裸 bytes.Buffer 跨 goroutine 读写会被
// race detector 判数据竞争（2026-10-17 CI 实测）。这里用互斥锁包一层。
type syncBuffer struct {
	mu  sync.Mutex
	buf strings.Builder
}

func (b *syncBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.Write(p)
}

func (b *syncBuffer) String() string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.buf.String()
}

// TestGoRecoversPanic 验证 panic 被恢复并记录结构化日志。
func TestGoRecoversPanic(t *testing.T) {
	var buf syncBuffer
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
	// done 在 panic 展开期关闭（早于 safego 的 recover 日志），故轮询等待日志落齐。
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
