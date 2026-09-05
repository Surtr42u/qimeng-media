package thumbnail

import (
	"context"
	"errors"
	"log/slog"
	"runtime"
	"sync"

	"qimeng-media/server/internal/sysmon"
)

// defaultQueueSize 是带界队列的默认容量。为什么默认 256：足以吸收扫描入库的
// 提交突发，又不至于让排队任务无界累积（每个任务只是结构体，真正的大头是执行中
// 的 ffmpeg 进程）；满载时的行为由 Submit 的丢弃语义兜住。
const defaultQueueSize = 256

// 工作池错误。
var (
	// ErrQueueFull 队列已满，任务被丢弃（为什么丢弃可接受，见 Submit 注释）。
	ErrQueueFull = errors.New("thumbnail: 工作池队列已满，任务被丢弃")
	// ErrPoolClosed 池已关闭，不再接收任务。
	ErrPoolClosed = errors.New("thumbnail: 工作池已关闭")
)

// Task 一个缩略图生成任务：对某资产生成一组尺寸的缩略图。
// 入库触发/懒生成端点（后续接线任务）负责构造并提交。
type Task struct {
	AssetID    string
	SourcePath string
	Kind       Kind
	Sizes      []Size
}

// WorkerPool 固定 worker + 带界队列的缩略图工作池。
// 为什么需要池：ffmpeg 是 CPU 密集的外部进程，无上限并发会把 NAS 打满；
// 为什么队列必须有界且 Submit 非阻塞【架构红线，ARCHITECTURE §5 禁止阻塞
// API 请求】：懒生成端点（HTTP 路径）也会提交任务，若提交方阻塞等待队列空位，
// 用户请求就被离线管线的背压绑架。
type WorkerPool struct {
	ctx    context.Context
	tasks  chan Task
	handle func(ctx context.Context, t Task) error
	logger *slog.Logger
	wg     sync.WaitGroup

	// mu 保护 closed 与向 tasks 的发送：close(tasks) 与向已关闭 channel
	// 发送并发发生会 panic，锁内"检查 closed + 发送"与锁内"置位 + close"
	// 互斥，从根上排除竞态。
	mu     sync.Mutex
	closed bool
}

// NewWorkerPool 启动 worker 池。workers<=0 时按 CPU 核数（把并发上限交给
// 运行环境决定，避免在未知 NAS 硬件上写死并发数导致过载）；queueSize<=0 用
// defaultQueueSize；logger 为 nil 用 slog.Default()。
// handle 在 worker 内串行执行，返回错误只记日志不中断池：单个坏文件
// 不该拖垮整批扫描（是否重试由 handle 内部决定，池只负责调度）。
func NewWorkerPool(ctx context.Context, workers, queueSize int, handle func(context.Context, Task) error, logger *slog.Logger) *WorkerPool {
	if workers <= 0 {
		workers = runtime.NumCPU()
	}
	if queueSize <= 0 {
		queueSize = defaultQueueSize
	}
	if logger == nil {
		logger = slog.Default()
	}
	p := &WorkerPool{
		ctx:    ctx,
		tasks:  make(chan Task, queueSize),
		handle: handle,
		logger: logger,
	}
	for i := 0; i < workers; i++ {
		p.wg.Add(1)
		go p.work()
	}
	return p
}

func (p *WorkerPool) work() {
	defer p.wg.Done()
	for task := range p.tasks {
		// 取走一个任务后刷新队列深度 gauge（len 对 channel 是并发安全的
		// 近似读，gauge 语义允许）。M3 预热接入前无生产者，恒 0 属预期。
		sysmon.Default.SetThumbQueueDepth(int64(len(p.tasks)))
		if err := p.handle(p.ctx, task); err != nil {
			p.logger.Error("缩略图任务失败",
				"assetId", task.AssetID, "source", task.SourcePath, "err", err)
		}
	}
}

// Submit 非阻塞提交任务：队列满时立即丢弃并返回 ErrQueueFull，绝不阻塞调用方
// 【架构红线：禁止阻塞 API 请求】。
// 丢弃为什么可接受：缩略图是幂等的缓存资产——被丢弃的任务只是让对应资产
// 暂时没有缩略图，下次全量扫描对账发现缺失会重新入队补齐，最坏结果
// 是"晚一拍出图"而非丢数据；反过来，阻塞提交会把离线管线的背压转嫁给
// HTTP 请求路径，违背"列表页秒开"的产品底线。
func (p *WorkerPool) Submit(t Task) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return ErrPoolClosed
	}
	select {
	case p.tasks <- t:
		// 入队成功刷新队列深度 gauge（队列满丢弃不刷新——队列长度未变）。
		sysmon.Default.SetThumbQueueDepth(int64(len(p.tasks)))
		return nil
	default:
		return ErrQueueFull
	}
}

// Close 优雅关闭：停止接收新任务，等已入队任务全部执行完再返回（应用停机路径
// 调用；重复调用安全）。进程被强杀的场景由原子落盘兜底——最多留下垃圾临时
// 文件，绝不会出现半成品缓存。
func (p *WorkerPool) Close() {
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		p.wg.Wait()
		return
	}
	p.closed = true
	close(p.tasks)
	p.mu.Unlock()
	p.wg.Wait()
}
