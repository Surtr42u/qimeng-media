package events

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"reflect"
	"sync"
	"testing"
	"time"
)

// 注：本机无 gcc 跑不了 -race，数据竞争靠上述读写锁 + closeOnce 设计保证；
// 本文件用并发烟雾测试兜底抓死锁与 panic（send on closed channel 等时序缺陷）。

// captureHandler 捕获 slog 记录，供测试断言丢弃告警（仅测试用，带锁支持并发发布）。
type captureHandler struct {
	mu      sync.Mutex
	records []slog.Record
}

func (c *captureHandler) Enabled(_ context.Context, _ slog.Level) bool { return true }

func (c *captureHandler) Handle(_ context.Context, r slog.Record) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.records = append(c.records, r)
	return nil
}

func (c *captureHandler) WithAttrs(_ []slog.Attr) slog.Handler { return c }

func (c *captureHandler) WithGroup(_ string) slog.Handler { return c }

// discardLogger 静默日志器：压测类测试避免刷屏。
func discardLogger() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

// mustRecv 带 2s 超时收一条事件：超时即测试失败（channel 同步断言，非 sleep 等待）。
func mustRecv(t *testing.T, ch <-chan Event) Event {
	t.Helper()
	select {
	case e, ok := <-ch:
		if !ok {
			t.Fatal("期望收到事件，通道却已关闭")
		}
		return e
	case <-time.After(2 * time.Second):
		t.Fatal("等待事件超时（2s）")
		return Event{} // 不会执行到
	}
}

// mustNotRecv 负向断言：时间窗内不应有事件到达。
// 负向断言必须给窗口证明"确实没来"：同进程 channel 传递是微秒级，100ms 裕量充分。
func mustNotRecv(t *testing.T, ch <-chan Event, what string) {
	t.Helper()
	select {
	case e, ok := <-ch:
		if ok {
			t.Fatalf("%s：不应收到事件，却收到 %+v", what, e)
		} else {
			t.Fatalf("%s：通道意外关闭", what)
		}
	case <-time.After(100 * time.Millisecond):
	}
}

func TestSubscribeTopicFilter(t *testing.T) {
	bus := NewBus(nil, 0)
	defer bus.Close()

	sub, err := bus.Subscribe(TopicScanProgress)
	if err != nil {
		t.Fatal(err)
	}
	defer sub.Close()

	// 未订阅的主题：不投递
	if err := bus.Publish(Event{Topic: TopicThumbnailProgress, Payload: "x"}); err != nil {
		t.Fatal(err)
	}
	mustNotRecv(t, sub.C, "未订阅的 topic")

	// 订阅的主题：收到且字段完整；At 零值自动补当前时间
	want := Event{Topic: TopicScanProgress, Payload: map[string]int{"done": 3}}
	if err := bus.Publish(want); err != nil {
		t.Fatal(err)
	}
	got := mustRecv(t, sub.C)
	if got.Topic != want.Topic {
		t.Fatalf("Topic = %q, want %q", got.Topic, want.Topic)
	}
	if got.At.IsZero() {
		t.Fatal("At 零值未被自动填充")
	}
	if !reflect.DeepEqual(got.Payload, want.Payload) {
		t.Fatalf("Payload = %+v, want %+v", got.Payload, want.Payload)
	}
}

func TestSubscribeNoTopicsIsError(t *testing.T) {
	bus := NewBus(nil, 0)
	defer bus.Close()
	if _, err := bus.Subscribe(); err == nil {
		t.Fatal("空 topics 必须报错（订阅范围必须显式声明）")
	}
}

func TestMultipleSubscribersBroadcast(t *testing.T) {
	bus := NewBus(nil, 0)
	defer bus.Close()

	subA, err := bus.Subscribe(TopicScanProgress, TopicLibraryChanged)
	if err != nil {
		t.Fatal(err)
	}
	defer subA.Close()
	subB, err := bus.Subscribe(TopicScanProgress)
	if err != nil {
		t.Fatal(err)
	}
	defer subB.Close()
	subC, err := bus.Subscribe(TopicUploadDone)
	if err != nil {
		t.Fatal(err)
	}
	defer subC.Close()

	// 同一条事件广播到所有匹配订阅者，内容一致
	if err := bus.Publish(Event{Topic: TopicScanProgress, Payload: "e1"}); err != nil {
		t.Fatal(err)
	}
	for name, s := range map[string]*Subscription{"A": subA, "B": subB} {
		if e := mustRecv(t, s.C); e.Payload != "e1" {
			t.Fatalf("订阅者%s 收到 %+v，want payload e1", name, e)
		}
	}
	mustNotRecv(t, subC.C, "只订阅 upload.done 的订阅者")

	// 不同 topic 各走各的订阅者
	if err := bus.Publish(Event{Topic: TopicUploadDone, Payload: 7}); err != nil {
		t.Fatal(err)
	}
	if e := mustRecv(t, subC.C); e.Payload != 7 {
		t.Fatalf("订阅者C 收到 %+v，want payload 7", e)
	}
	mustNotRecv(t, subA.C, "未订阅 upload.done 的订阅者A")
	mustNotRecv(t, subB.C, "未订阅 upload.done 的订阅者B")
}

func TestSlowSubscriberDoesNotBlockPublish(t *testing.T) {
	// 缓冲设 2，模拟一个卡死/不读的订阅者
	bus := NewBus(discardLogger(), 2)
	slow, err := bus.Subscribe(TopicScanProgress)
	if err != nil {
		t.Fatal(err)
	}
	defer slow.Close()

	const n = 200
	done := make(chan struct{})
	go func() {
		defer close(done)
		for i := 0; i < n; i++ {
			if err := bus.Publish(Event{Topic: TopicScanProgress, Payload: i}); err != nil {
				t.Errorf("Publish 不应报错: %v", err)
				return
			}
		}
	}()
	select {
	case <-done: // 200 条全部发布完成
	case <-time.After(5 * time.Second):
		t.Fatal("Publish 被慢订阅者阻塞：缓冲满应丢弃该订阅者的事件，而不是反压发布者")
	}

	// 慢订阅者只保有缓冲内的前 2 条，其余被丢弃（尽力而为语义）
	if e := mustRecv(t, slow.C); e.Payload.(int) != 0 {
		t.Fatalf("慢订阅者第 1 条 = %v, want 0", e.Payload)
	}
	if e := mustRecv(t, slow.C); e.Payload.(int) != 1 {
		t.Fatalf("慢订阅者第 2 条 = %v, want 1", e.Payload)
	}
	mustNotRecv(t, slow.C, "慢订阅者缓冲之外的事件")

	// 丢弃按订阅者隔离：slow 缓冲满被丢弃过，不影响其他订阅者正常投递。
	// （不做"持续读的快订阅者收满全部"断言——那依赖 goroutine 调度时序，
	// 在"有界缓冲+满即丢"语义下本就不是保证，这里用确定性序列验证隔离性。）
	fresh, err := bus.Subscribe(TopicScanProgress)
	if err != nil {
		t.Fatal(err)
	}
	defer fresh.Close()
	if err := bus.Publish(Event{Topic: TopicScanProgress, Payload: "after"}); err != nil {
		t.Fatal(err)
	}
	if e := mustRecv(t, fresh.C); e.Payload != "after" {
		t.Fatalf("slow 曾被丢弃，新订阅者仍应正常收到事件，got %+v", e)
	}
}

func TestDropLogsWarn(t *testing.T) {
	cap := &captureHandler{}
	bus := NewBus(slog.New(cap), 1)
	sub, err := bus.Subscribe(TopicScanProgress)
	if err != nil {
		t.Fatal(err)
	}
	defer sub.Close()

	if err := bus.Publish(Event{Topic: TopicScanProgress}); err != nil {
		t.Fatal(err)
	}
	if err := bus.Publish(Event{Topic: TopicScanProgress}); err != nil {
		t.Fatal(err) // 丢弃不是错误，Publish 仍返回 nil
	}

	cap.mu.Lock()
	defer cap.mu.Unlock()
	found := false
	for _, rec := range cap.records {
		if rec.Level != slog.LevelWarn {
			continue
		}
		rec.Attrs(func(a slog.Attr) bool {
			if a.Key == "topic" && a.Value.String() == TopicScanProgress {
				found = true
			}
			return true
		})
	}
	if !found {
		t.Fatal("缓冲满丢弃事件时应记 slog warn，且带 topic 属性")
	}
}

func TestBusCloseClosesSubscriptionsAndErrors(t *testing.T) {
	bus := NewBus(nil, 0)
	sub, err := bus.Subscribe(TopicScanProgress, TopicLibraryChanged)
	if err != nil {
		t.Fatal(err)
	}

	bus.Close()

	// 订阅通道自动关闭
	if _, ok := <-sub.C; ok {
		t.Fatal("总线 Close 后订阅通道应已关闭")
	}
	// 发布到已关闭总线：可区分错误
	if err := bus.Publish(Event{Topic: TopicScanProgress}); !errors.Is(err, ErrBusClosed) {
		t.Fatalf("Publish 错误 = %v, want ErrBusClosed", err)
	}
	// 关闭后订阅：同样报错
	if _, err := bus.Subscribe(TopicScanProgress); !errors.Is(err, ErrBusClosed) {
		t.Fatalf("Subscribe 错误 = %v, want ErrBusClosed", err)
	}
	// 幂等：重复 Close / 重复 Subscription.Close 不 panic
	bus.Close()
	sub.Close()
}

func TestConcurrentSmoke(t *testing.T) {
	// 并发烟雾测试：并发 Publish / Subscribe / Subscription.Close / 最终 Close，
	// 验证不死锁、不 panic（send on closed channel 等时序缺陷会在此暴露）。
	bus := NewBus(discardLogger(), 8)

	var pubWG, rotWG, readerWG sync.WaitGroup
	stop := make(chan struct{})

	// 读者：持续读直到总线关闭（订阅在发布开始前同步建立）
	for i := 0; i < 3; i++ {
		sub, err := bus.Subscribe(TopicScanProgress, TopicLibraryChanged)
		if err != nil {
			t.Fatal(err)
		}
		readerWG.Add(1)
		go func(s *Subscription) {
			defer readerWG.Done()
			defer s.Close()
			for {
				select {
				case _, ok := <-s.C:
					if !ok {
						return
					}
				case <-stop:
					return
				}
			}
		}(sub)
	}

	// 发布者：并发发布
	for p := 0; p < 4; p++ {
		pubWG.Add(1)
		go func() {
			defer pubWG.Done()
			for i := 0; i < 300; i++ {
				if err := bus.Publish(Event{Topic: TopicScanProgress}); err != nil {
					t.Errorf("并发 Publish 报错: %v", err)
					return
				}
			}
		}()
	}

	// 订阅轮换者：不断订阅/退订，与发布、关闭并发交错
	for i := 0; i < 2; i++ {
		rotWG.Add(1)
		go func() {
			defer rotWG.Done()
			for j := 0; j < 100; j++ {
				s, err := bus.Subscribe(TopicLibraryChanged)
				if err != nil {
					return // 总线已关闭，正常退出
				}
				s.Close()
			}
		}()
	}

	pubWG.Wait()
	rotWG.Wait()
	close(stop)
	bus.Close()
	readerWG.Wait() // 读者因订阅通道关闭而退出，不挂死
}
