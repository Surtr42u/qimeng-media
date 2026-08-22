package events

import (
	"errors"
	"log/slog"
	"sync"
	"time"
)

// 主题常量与 api/openapi.yaml 的 /api/v1/events 事件定义对齐；
// 若增删主题必须先改 openapi 再改这里（协议先行，见 AI_README_FIRST.md）。
const (
	TopicScanProgress      = "scan.progress"      // 扫描进度（尽力而为，允许丢帧）
	TopicLibraryChanged    = "library.changed"    // 库内容变更（增删改后通知各端刷新）
	TopicThumbnailProgress = "thumbnail.progress" // 缩略图生成进度
	TopicUploadDone        = "upload.done"        // 上传入库完成
)

// DefaultBuffer 每订阅者的投递缓冲大小。
// 64 足以吸收一次突发进度事件，又不至于让不读的订阅者长期囤积内存。
const DefaultBuffer = 64

// ErrBusClosed 发布/订阅发生在总线 Close 之后，调用方用 errors.Is 区分。
var ErrBusClosed = errors.New("events: 总线已关闭")

// Event 进程内事件。Payload 约定为可 json 序列化的领域数据（SSE 直接序列化进 data:）。
type Event struct {
	Topic   string
	Payload any
	At      time.Time
}

// Subscription 一次订阅的句柄。C 只读；用完必须 Close，
// 否则总线持有其缓冲通道，订阅者自身也无法被回收。
type Subscription struct {
	C <-chan Event

	ch        chan Event
	id        uint64
	topics    map[string]struct{}
	bus       *Bus
	closeOnce sync.Once // 保证 chan 只被关闭一次（Bus.Close 与 Subscription.Close 可能并发到达）
}

// Close 结束订阅。幂等：重复调用、以及总线已 Close 之后再调用，均安全不 panic。
func (s *Subscription) Close() { s.bus.remove(s) }

// shutdown 真正关闭通道，全包唯一收口处。
func (s *Subscription) shutdown() { s.closeOnce.Do(func() { close(s.ch) }) }

// Bus 进程内发布/订阅总线。
//
// 并发设计目标：publish/close/subscribe 并发调用不 panic，慢订阅者不拖累发布者。
//   - mu 只保护 subs 映射与 closed 标志的短暂临界区：Publish 持读锁可多者并行，
//     投递是 select+default 非阻塞（O(1)），不会长时间持锁——不是大锁包一切；
//   - chan 的 send 只出现在 Publish（读锁内），close 只出现在 shutdown（所有调用点
//     均持写锁），读写锁互斥 ⇒ 不存在 "send on closed channel" panic；
//   - closeOnce 保证同一条订阅不会被 Bus.Close 与 Subscription.Close 关闭两次。
//
// 丢弃语义：订阅者缓冲满即丢弃该订阅者的本条事件并记 slog warn。
// 进度类事件是"尽力而为"——丢帧不丢系统：宁可慢客户端少看几帧进度，
// 也绝不让一个卡死的 SSE 连接反压阻塞发布者（发布方往往在扫描/上传热路径上）。
// 总线关闭后，订阅者缓冲中的残留事件仍可被读方读完（channel 标准语义）。
type Bus struct {
	mu     sync.RWMutex
	subs   map[uint64]*Subscription
	nextID uint64
	closed bool
	logger *slog.Logger
	buf    int
}

// NewBus 创建总线。logger 为 nil 时用 slog.Default()；bufSize<=0 时用 DefaultBuffer。
func NewBus(logger *slog.Logger, bufSize int) *Bus {
	if logger == nil {
		logger = slog.Default()
	}
	if bufSize <= 0 {
		bufSize = DefaultBuffer
	}
	return &Bus{
		subs:   make(map[uint64]*Subscription),
		logger: logger,
		buf:    bufSize,
	}
}

// Subscribe 订阅一组主题（投递按 topic 过滤）。
// 空 topics 报错：订阅范围必须显式声明，防止无意间收到全部事件。
func (b *Bus) Subscribe(topics ...string) (*Subscription, error) {
	if len(topics) == 0 {
		return nil, errors.New("events: 至少订阅一个 topic")
	}
	ts := make(map[string]struct{}, len(topics))
	for _, t := range topics {
		ts[t] = struct{}{}
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed {
		return nil, ErrBusClosed
	}
	b.nextID++
	s := &Subscription{
		ch:     make(chan Event, b.buf),
		id:     b.nextID,
		topics: ts,
		bus:    b,
	}
	s.C = s.ch
	b.subs[s.id] = s
	return s, nil
}

// Publish 向所有订阅了 e.Topic 的订阅者非阻塞投递。
// At 为零值时自动补当前时间，免去每个发布点重复 time.Now。
// 返回 ErrBusClosed 表示总线已关闭；慢订阅者导致的丢弃不是错误（尽力而为语义）。
func (b *Bus) Publish(e Event) error {
	if e.At.IsZero() {
		e.At = time.Now()
	}
	b.mu.RLock()
	if b.closed {
		b.mu.RUnlock()
		return ErrBusClosed
	}
	var dropped []uint64
	for _, s := range b.subs {
		if _, ok := s.topics[e.Topic]; !ok {
			continue
		}
		select {
		case s.ch <- e:
		default:
			dropped = append(dropped, s.id)
		}
	}
	b.mu.RUnlock()
	// 丢弃告警放锁外打：slog handler 可能做 IO，不拖住其他发布者。
	for _, id := range dropped {
		b.logger.Warn("events: 订阅者缓冲已满，丢弃事件（进度事件尽力而为：丢帧不丢系统）",
			"topic", e.Topic, "subscriber", id)
	}
	return nil
}

// Close 关闭总线：全部订阅通道立即关闭，之后的 Publish/Subscribe 返回 ErrBusClosed。
// 幂等，可与 Publish/Subscribe/Subscription.Close 并发调用。
func (b *Bus) Close() {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.closed {
		return
	}
	b.closed = true
	for id, s := range b.subs {
		s.shutdown()
		delete(b.subs, id)
	}
}

// remove 摘除并关闭一条订阅（Subscription.Close 的落地实现）。
func (b *Bus) remove(s *Subscription) {
	b.mu.Lock()
	defer b.mu.Unlock()
	// 若已被 Bus.Close 清掉，delete 是无害空操作；shutdown 由 once 保证只关一次。
	delete(b.subs, s.id)
	s.shutdown()
}
