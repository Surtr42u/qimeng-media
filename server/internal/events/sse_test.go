package events

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"runtime"
	"testing"
	"time"
)

// sseGet 发起一次 SSE 请求；resp.Body 由 t.Cleanup 统一关闭。
func sseGet(t *testing.T, srv *httptest.Server, ctx context.Context) (*http.Response, *bufio.Reader) {
	t.Helper()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, srv.URL+"/api/v1/events", nil)
	if err != nil {
		t.Fatal(err)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { resp.Body.Close() })
	return resp, bufio.NewReader(resp.Body)
}

// readFrame 读一帧（至空行结束），返回原始文本供逐字节断言。
// ctx 超时后 ReadString 返回错误，测试以 Fatal 报出"卡住"，不会无限挂起。
func readFrame(t *testing.T, r *bufio.Reader) string {
	t.Helper()
	var sb bytes.Buffer
	for {
		line, err := r.ReadString('\n')
		sb.WriteString(line)
		if err != nil {
			t.Fatalf("读 SSE 帧意外结束: %v（已读 %q）", err, sb.String())
		}
		if line == "\n" {
			return sb.String()
		}
	}
}

// waitFor 收敛轮询：条件满足立即返回，仅用于 goroutine/计数回落这类
// runtime 不提供通知的"最终收敛"检测，不是时序竞态等待。
func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(5 * time.Millisecond)
	}
	t.Fatalf("等待超时: %s", what)
}

func TestSSEStreamFrameFormat(t *testing.T) {
	bus := NewBus(nil, 0)
	defer bus.Close()
	h := NewHandler(bus, WithVersion("t-1.0"), WithHeartbeat(time.Hour))
	srv := httptest.NewServer(h)
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	resp, r := sseGet(t, srv, ctx)

	// 响应头
	if ct := resp.Header.Get("Content-Type"); ct != "text/event-stream; charset=utf-8" {
		t.Fatalf("Content-Type = %q", ct)
	}
	if cc := resp.Header.Get("Cache-Control"); cc != "no-cache" {
		t.Fatalf("Cache-Control = %q", cc)
	}
	if ab := resp.Header.Get("X-Accel-Buffering"); ab != "no" {
		t.Fatalf("X-Accel-Buffering = %q", ab)
	}

	// 首帧：独立 retry 帧（客户端断线重连等待）
	if got, want := readFrame(t, r), "retry: 3000\n\n"; got != want {
		t.Fatalf("retry 帧逐字节不符:\n got  %q\n want %q", got, want)
	}
	// 首帧：hello 事件（id 从 0 起）
	if got, want := readFrame(t, r), "event: hello\ndata: {\"version\":\"t-1.0\"}\nid: 0\n\n"; got != want {
		t.Fatalf("hello 帧逐字节不符:\n got  %q\n want %q", got, want)
	}

	// 业务帧：payload JSON 进 data:，id 递增
	type scanPayload struct {
		LibraryID int64 `json:"libraryId"`
		Done      int   `json:"done"`
	}
	if err := bus.Publish(Event{Topic: TopicScanProgress, Payload: scanPayload{LibraryID: 7, Done: 42}}); err != nil {
		t.Fatal(err)
	}
	if got, want := readFrame(t, r),
		"event: scan.progress\ndata: {\"libraryId\":7,\"done\":42}\nid: 1\n\n"; got != want {
		t.Fatalf("业务帧逐字节不符:\n got  %q\n want %q", got, want)
	}

	if err := bus.Publish(Event{Topic: TopicLibraryChanged, Payload: "refresh"}); err != nil {
		t.Fatal(err)
	}
	if got, want := readFrame(t, r),
		"event: library.changed\ndata: \"refresh\"\nid: 2\n\n"; got != want {
		t.Fatalf("第二条业务帧（id 递增）逐字节不符:\n got  %q\n want %q", got, want)
	}
}

// TestSSEEngagementEventsPassthrough（ADR-0029）：favorite.changed /
// like.changed 经总线到 SSE 帧原样透传——新事件名对既有帧拼装零特判，
// 与 library.changed 走同一条 writeEvent 路径，载荷 JSON 形态
// {"assetId":"..."} 一并锁住（客户端批次按此实现解析）。
func TestSSEEngagementEventsPassthrough(t *testing.T) {
	bus := NewBus(nil, 0)
	defer bus.Close()
	h := NewHandler(bus, WithHeartbeat(time.Hour))
	srv := httptest.NewServer(h)
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, r := sseGet(t, srv, ctx)
	readFrame(t, r) // retry
	readFrame(t, r) // hello

	if err := bus.Publish(Event{Topic: TopicFavoriteChanged, Payload: EngagementChangedEvent{AssetID: "018f0000-0000-7000-8000-000000000001"}}); err != nil {
		t.Fatal(err)
	}
	if got, want := readFrame(t, r),
		"event: favorite.changed\ndata: {\"assetId\":\"018f0000-0000-7000-8000-000000000001\"}\nid: 1\n\n"; got != want {
		t.Fatalf("favorite.changed 帧逐字节不符:\n got  %q\n want %q", got, want)
	}

	if err := bus.Publish(Event{Topic: TopicLikeChanged, Payload: EngagementChangedEvent{AssetID: "018f0000-0000-7000-8000-000000000002"}}); err != nil {
		t.Fatal(err)
	}
	if got, want := readFrame(t, r),
		"event: like.changed\ndata: {\"assetId\":\"018f0000-0000-7000-8000-000000000002\"}\nid: 2\n\n"; got != want {
		t.Fatalf("like.changed 帧逐字节不符:\n got  %q\n want %q", got, want)
	}
}

func TestFrameSplitsMultilineData(t *testing.T) {
	// 直接测帧拼装函数：多行 data 必须拆成多条 data: 行（WHATWG SSE 规范）。
	// 经 json.Marshal 的真实 payload 不会含裸换行，此用例锁住防御性拆分逻辑本身。
	var buf bytes.Buffer
	frame(&buf, 3, "x.event", []byte("a\nb\nc"))
	want := "event: x.event\ndata: a\ndata: b\ndata: c\nid: 3\n\n"
	if got := buf.String(); got != want {
		t.Fatalf("多行 data 拆分不符:\n got  %q\n want %q", got, want)
	}
}

func TestSSEHeartbeatCommentLine(t *testing.T) {
	bus := NewBus(nil, 0)
	defer bus.Close()
	h := NewHandler(bus, WithHeartbeat(20*time.Millisecond)) // 测试调短心跳
	srv := httptest.NewServer(h)
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, r := sseGet(t, srv, ctx)

	readFrame(t, r) // retry
	readFrame(t, r) // hello

	// 循环读直到心跳注释帧出现（": " 开头的注释行 + 空行）
	deadline := time.Now().Add(3 * time.Second)
	for {
		if time.Now().After(deadline) {
			t.Fatal("3s 内未收到心跳注释帧 : ping")
		}
		if got := readFrame(t, r); got == ": ping\n\n" {
			return
		}
		// 其他帧（本用例不应出现）跳过继续等
	}
}

func TestSSEMaxConnsReturns503(t *testing.T) {
	bus := NewBus(nil, 0)
	defer bus.Close()
	h := NewHandler(bus, WithMaxConns(1), WithHeartbeat(time.Hour))
	srv := httptest.NewServer(h)
	defer srv.Close()

	// 第一个连接：读到 retry 帧说明 ServeHTTP 已过占坑点并进入流循环
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	_, r := sseGet(t, srv, ctx)
	if got := readFrame(t, r); got != "retry: 3000\n\n" {
		t.Fatalf("首帧不符: %q", got)
	}

	// 第二个连接：超限返回 503 + openapi Error 风格 JSON（code/message）
	resp2, err := http.Get(srv.URL + "/api/v1/events")
	if err != nil {
		t.Fatal(err)
	}
	defer resp2.Body.Close()
	if resp2.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("StatusCode = %d, want 503", resp2.StatusCode)
	}
	if ct := resp2.Header.Get("Content-Type"); ct != "application/json; charset=utf-8" {
		t.Fatalf("Content-Type = %q", ct)
	}
	var body struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	}
	if err := json.NewDecoder(resp2.Body).Decode(&body); err != nil {
		t.Fatal(err)
	}
	if body.Code != "TOO_MANY_CONNECTIONS" {
		t.Fatalf("code = %q, want TOO_MANY_CONNECTIONS", body.Code)
	}
	if body.Message == "" {
		t.Fatal("message 不应为空")
	}
}

func TestSSEDisconnectCleanupNoLeak(t *testing.T) {
	bus := NewBus(nil, 0)
	defer bus.Close()
	h := NewHandler(bus, WithHeartbeat(time.Hour))
	srv := httptest.NewServer(h)
	defer srv.Close()

	runtime.GC()
	before := runtime.NumGoroutine()

	ctx, cancel := context.WithCancel(context.Background())
	_, r := sseGet(t, srv, ctx)
	readFrame(t, r) // retry
	readFrame(t, r) // hello

	// 客户端断开 → r.Context().Done() → handler 的 defer 链清理（关订阅+退连接计数）
	cancel()
	waitFor(t, "SSE 连接计数归零", func() bool { return h.conns.Load() == 0 })

	// goroutine 泄漏检测：NumGoroutine 受 httptest server、http client 传输层、
	// GC 延迟影响存在抖动，容忍 +2；waitFor 是收敛轮询（条件满足即返回），
	// 而非固定 sleep 的竞态等待。
	waitFor(t, "goroutine 数回落到容忍区间", func() bool {
		return runtime.NumGoroutine() <= before+2
	})
}

func TestSSEBusCloseEndsStream(t *testing.T) {
	bus := NewBus(nil, 0)
	h := NewHandler(bus, WithHeartbeat(time.Hour))
	srv := httptest.NewServer(h)
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, r := sseGet(t, srv, ctx)
	readFrame(t, r) // retry
	readFrame(t, r) // hello

	// 总线关闭 → 订阅通道关闭 → handler 结束流（连接正常收尾，客户端读到 EOF）
	bus.Close()
	if _, err := r.ReadString('\n'); err != io.EOF {
		t.Fatalf("总线关闭后流应结束(EOF)，得到 err = %v", err)
	}
}
