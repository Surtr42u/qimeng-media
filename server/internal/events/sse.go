package events

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

const (
	// DefaultMaxConns SSE 并发连接上限默认值。家庭 NAS 场景端数有限，16 足够；
	// 每条连接占用一个 goroutine + 一条订阅缓冲，设上限防止异常客户端耗尽资源。
	DefaultMaxConns = 16
	// DefaultHeartbeat 心跳间隔。要低于常见中间层（如 nginx 默认 60s）的空闲超时，
	// 用 SSE 注释行保活，避免链路被中间设备掐断。
	DefaultHeartbeat = 15 * time.Second
	// DefaultRetryMS 首帧下发的重连等待（毫秒）：客户端意外断线后按此间隔自动重连。
	DefaultRetryMS = 3000
	// sseNoCache SSE 流的缓存策略：事件帧是一次性推送，中间缓存只会把
	// "实时"变"重放"（HTTP 惯例值，跨包不与 httpapi 共享常量——边界所限
	// 各自具名即可）。
	sseNoCache = "no-cache"

	// SSE 端点错误码（writeError 的 code 参数具名来源）。与 openapi
	// components.Error 的 code 语义同源；模块边界（ADR-0010：业务包禁止
	// 依赖 httpapi）使然不能复用 httpapi/errors.go 的常量。同步责任：
	// 协议侧 /events 错误响应或 httpapi 错误码体系改动时须同步此处，
	// 反之亦然（代码卫生约束 3）。
	codeTooManyConnections = "TOO_MANY_CONNECTIONS"
	codeNoStreamSupport    = "NO_STREAM_SUPPORT"
	codeEventsShutdown     = "EVENTS_SHUTDOWN"
)

// allTopics SSE 端点固定开放全部四类事件（与 openapi /api/v1/events 定义一致）。
var allTopics = []string{
	TopicScanProgress,
	TopicLibraryChanged,
	TopicThumbnailProgress,
	TopicUploadDone,
}

// helloPayload 首帧 hello 事件的负载：携带服务端版本（占位 "dev"，接线时传构建版本）。
type helloPayload struct {
	Version string `json:"version"`
}

// Handler 把总线事件以 SSE（text/event-stream）推给客户端。
// 方法签名即 http.HandlerFunc，路由接线时可直接
// r.Get("/api/v1/events", h.ServeHTTP)——http.HandlerFunc 天然兼容 chi，无需引依赖。
type Handler struct {
	bus       *Bus
	logger    *slog.Logger
	version   string
	maxConns  int64
	heartbeat time.Duration
	conns     atomic.Int64
	// connGauge 活跃连接数回调（WithConnectionGauge 注入；nil = 不回调）。
	// events 包不感知指标实现，装配层经它单点接线。
	connGauge func(int64)
}

// Option Handler 配置项。
type Option func(*Handler)

// WithVersion 覆盖 hello 事件携带的服务端版本。
func WithVersion(v string) Option { return func(h *Handler) { h.version = v } }

// WithConnectionGauge 注册活跃连接数回调（OBSERVABILITY sse_connections 的
// 装配钩子）：占坑成功后与连接释放后各回调一次，参数为当前连接数绝对值
// （Set 语义，多次回调不漂移）；503 拒绝（占坑失败即回退）不回调——被拒
// 连接从未活跃过。回调在连接建立/断开路径上同步执行，必须快速非阻塞。
// 传 nil 保持无回调（默认，测试零接线）。
func WithConnectionGauge(f func(int64)) Option { return func(h *Handler) { h.connGauge = f } }

// WithMaxConns 覆盖并发连接上限（<=0 保持默认）。
func WithMaxConns(n int) Option {
	return func(h *Handler) {
		if n > 0 {
			h.maxConns = int64(n)
		}
	}
}

// WithHeartbeat 覆盖心跳间隔（<=0 保持默认）。测试用它调短以验证心跳帧。
func WithHeartbeat(d time.Duration) Option {
	return func(h *Handler) {
		if d > 0 {
			h.heartbeat = d
		}
	}
}

// WithLogger 覆盖日志器（nil 保持默认 slog.Default）。
func WithLogger(l *slog.Logger) Option {
	return func(h *Handler) {
		if l != nil {
			h.logger = l
		}
	}
}

// NewHandler 创建 SSE handler。bus 为事件来源，opts 可覆盖版本/上限/心跳/日志。
func NewHandler(bus *Bus, opts ...Option) *Handler {
	h := &Handler{
		bus:       bus,
		logger:    slog.Default(),
		version:   "dev",
		maxConns:  DefaultMaxConns,
		heartbeat: DefaultHeartbeat,
	}
	for _, opt := range opts {
		opt(h)
	}
	return h
}

// notifyConns 把当前活跃连接数推给装配期注册的 gauge 回调（绝对值 Set，
// 并发下取 conns 的某一时刻快照，gauge 语义允许近似）。
func (h *Handler) notifyConns() {
	if h.connGauge != nil {
		h.connGauge(h.conns.Load())
	}
}

// ServeHTTP 处理 GET /api/v1/events（SSE 流）。
// 生命周期：占坑（并发计数）→ 校验 → 订阅 → 首帧（retry+hello）→ 事件循环 → defer 清理。
func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	// 先占坑再校验：占坑-检查-回退三步保证并发下计数不超卖也不漏减。
	if n := h.conns.Add(1); n > h.maxConns {
		h.conns.Add(-1)
		h.writeError(w, http.StatusServiceUnavailable,
			codeTooManyConnections, "SSE 并发连接数已达上限，请关闭其他页面后重试")
		return // 503 拒绝不回调 gauge：连接从未活跃（WithConnectionGauge 契约）
	}
	h.notifyConns()
	defer func() {
		h.conns.Add(-1)
		h.notifyConns()
	}()

	flusher, ok := w.(http.Flusher)
	if !ok {
		h.writeError(w, http.StatusInternalServerError,
			codeNoStreamSupport, "当前响应不支持流式写入")
		return
	}

	sub, err := h.bus.Subscribe(allTopics...)
	if err != nil {
		// 总线已关闭（通常处于进程优雅退出阶段），按服务不可用返回
		h.writeError(w, http.StatusServiceUnavailable,
			codeEventsShutdown, "事件服务已关闭")
		return
	}
	defer sub.Close()

	// 响应头必须在 WriteHeader 之前设置完毕。
	hdr := w.Header()
	hdr.Set("Content-Type", "text/event-stream; charset=utf-8")
	hdr.Set("Cache-Control", sseNoCache)
	// 反向代理（nginx 等）默认缓冲响应，会把 SSE 帧攒成批、"实时"变"批量"，显式禁用。
	hdr.Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)

	// 首帧立即下发：retry 告知客户端断线重连等待；hello 让前端无需等业务事件
	// 即知流已建立，并携带服务端版本便于排查端云不一致。
	if _, err := fmt.Fprintf(w, "retry: %d\n\n", DefaultRetryMS); err != nil {
		return // 写失败=对端已断开，交给 defer 清理
	}
	hello, err := json.Marshal(helloPayload{Version: h.version})
	if err != nil {
		// 纯字符串结构体，理论不可达；记录但不断流（hello 缺失不影响事件推送）
		h.logger.Error("events: hello 负载序列化失败", "err", err)
	} else if err := h.writeFrame(w, 0, "hello", hello); err != nil {
		return
	}
	flusher.Flush()

	ticker := time.NewTicker(h.heartbeat)
	defer ticker.Stop()
	// 事件序号仅用于客户端观测/去重：总线不存历史事件，
	// Last-Event-ID 续传不适用（错过的进度由前端重新拉取兜底）。
	var id uint64
	for {
		select {
		case <-r.Context().Done():
			// 客户端断开：defer 链完成清理（关订阅、退连接计数）
			return
		case <-ticker.C:
			// SSE 注释行（冒号开头）：客户端与中间层都会忽略内容，仅用于保活
			if _, err := fmt.Fprint(w, ": ping\n\n"); err != nil {
				return
			}
			flusher.Flush()
		case e, ok := <-sub.C:
			if !ok {
				return // 总线关闭，流自然结束
			}
			id++
			if err := h.writeEvent(w, id, e); err != nil {
				return
			}
			flusher.Flush()
		}
	}
}

// writeEvent 序列化并输出一条业务事件帧。单个 payload 序列化失败只丢该条并记日志，
// 不中断整条流（一条坏数据不该断掉客户端的实时通道）。
func (h *Handler) writeEvent(w io.Writer, id uint64, e Event) error {
	data, err := json.Marshal(e.Payload)
	if err != nil {
		h.logger.Warn("events: SSE payload 序列化失败，丢弃该事件", "topic", e.Topic, "err", err)
		return nil
	}
	return h.writeFrame(w, id, e.Topic, data)
}

// writeFrame 按 WHATWG SSE 语法输出一帧（event/data/id 字段行 + 空行结束）。
func (h *Handler) writeFrame(w io.Writer, id uint64, event string, data []byte) error {
	var buf bytes.Buffer
	frame(&buf, id, event, data)
	_, err := w.Write(buf.Bytes())
	return err
}

// frame 是全包唯一的 SSE 帧拼装入口（hello 与业务事件共用，防止两处格式漂移）。
// 语法依据 WHATWG HTML 规范的 server-sent events：字段行 "field: value"，
// 帧以空行结束；": " 开头为注释行。
func frame(buf *bytes.Buffer, id uint64, event string, data []byte) {
	buf.WriteString("event: ")
	buf.WriteString(event)
	buf.WriteByte('\n')
	// WHATWG 规范：多行 data 必须拆成多条 data: 行，客户端以 \n 拼接还原。
	// json.Marshal 的输出不含裸换行（字符串内换行已被转义），这里按 \n 拆分
	// 是防御性实现——将来即使换序列化路径，输出也仍然符合规范。
	for _, line := range strings.Split(string(data), "\n") {
		buf.WriteString("data: ")
		buf.WriteString(line)
		buf.WriteByte('\n')
	}
	buf.WriteString("id: ")
	buf.WriteString(strconv.FormatUint(id, 10))
	buf.WriteByte('\n')
	buf.WriteByte('\n')
}

// contentTypeJSON 与 httpapi/errors.go、auth/middleware.go 的同名值三方一致（包边界
// 禁反向依赖 httpapi，各自持有副本但值漂移=响应头分裂，改动须三方同步——U11 清偿批
// 记档：跨包共享需引公共 HTTP 常量包，当前三处成本低于新包）。
const contentTypeJSON = "application/json; charset=utf-8"

// writeError 输出与 openapi components.Error 模型（code/message 字段）一致的 JSON 错误。
func (h *Handler) writeError(w http.ResponseWriter, status int, code, message string) {
	w.Header().Set("Content-Type", contentTypeJSON)
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(map[string]string{"code": code, "message": message}); err != nil {
		// 响应已开始写，此处失败只可能是对端断开，记录后无补救动作
		h.logger.Warn("events: 写错误响应失败", "code", code, "err", err)
	}
}
