package thumbnail

import (
	"context"
	"fmt"
	"log/slog"
	"runtime/debug"
	"sync"

	"qimeng-media/server/internal/sysmon"
)

// detachedGroup 懒生成的「单飞 + 后台续生」编排（2026-09-15 批，手机单机形态
// 缩略图慢一拍/卡顿的根治）。背景：懒生成端点原样直调 Ensure(r.Context(),…)——
// 生成挂在 HTTP 请求 context 上，客户端（Coil 滑出视口/快速滚动）一取消，
// 进行中的 ffmpeg 当场被杀、缩略图永远落不了盘，下次浏览再从头生成——真机
// 日志实证 3 分钟内 765 次「缩略图生成失败: context canceled」全部白干，
// 6341 资产仅 256 张缩略图落盘。
//
// 语义（三权利）：
//   - 去重：同 key 的并发/重复请求合并为一次生成（消灭重复 ffmpeg 风暴，
//     genSlots 闸位不再被同键任务反复重占）；
//   - 续生：生成体挂服务生命周期 context（genSlots 仍限并发、frameTimeout
//     仍兜底单尺寸时长），调用方取消只结束「等待」，不杀生成——本次请求
//     拿到 ctx.Err() 退出，但缩略图终将落盘，下次请求命中缓存；
//   - 先查后做：进入单飞前先查缓存存在性，命中零成本返回（命中率高时
//     常规路径零开销）。
//
// 行为由 detached_test.go 锁定（等待者取消不杀生成/同 key 合并/不同 key 不合并）。
type detachedGroup struct {
	mu      sync.Mutex
	terms   map[string]*detachedCall
	closed  bool
	changed chan struct{}
	wg      sync.WaitGroup
}

// detachedCall 一次进行中的生成：done 关闭即完成（err 为最终结果）。
// err 先写后 close——close 建立 happen-before，等待者收到关闭即可安全读 err。
type detachedCall struct {
	done     chan struct{}
	err      error
	priority chan struct{}
	promote  sync.Once
}

func newDetachedGroup() *detachedGroup {
	return &detachedGroup{terms: map[string]*detachedCall{}, changed: make(chan struct{})}
}

// do 对 key 执行 fn：已有进行中的同 key 调用则直接等待其结果（等待可被
// ctx 取消中断，但那不影响 fn 继续跑完）；否则立即注册并起独立 goroutine
// 执行 fn（fn 内部自行管并发与超时，本层限制集合数，表示预算由 Generator 统一持有）。返回值：fn 的结果，或
// ctx 取消时的 ctx.Err()。
// 去重只覆盖「进行中」——fn 完成即回收条目（close(done) 后 delete），迟到者
// 重新发起，与 x/sync/singleflight 同款语义；真链路无重跑成本——EnsureDetached
// 先查磁盘缓存，落盘后的请求走快路径根本不进 do。
func (dg *detachedGroup) do(key string, ctx context.Context, fn func() error) error {
	return dg.doPriority(key, ctx, false, func(<-chan struct{}) error { return fn() })
}

// 前台加入已有后台调用时提升同一生成体，不另建转码任务或旁路并发预算。
func (dg *detachedGroup) doPriority(key string, ctx context.Context, foreground bool, fn func(<-chan struct{}) error) error {
	return dg.doOwned(key, ctx, foreground, nil, fn)
}

// lease 的所有权交给新生成体；同键加入或准入失败立即释放暂借引用。
func (dg *detachedGroup) doOwned(key string, ctx context.Context, foreground bool, lease *representationLease, fn func(<-chan struct{}) error) error {
	for {
		dg.mu.Lock()
		if dg.closed {
			dg.mu.Unlock()
			lease.release()
			return ErrPoolClosed
		}
		if call, ok := dg.terms[key]; ok {
			if foreground {
				call.promote.Do(func() { close(call.priority) })
			}
			dg.mu.Unlock()
			lease.release()
			sysmon.Default.IncThumbWork("merged")
			return waitDetached(ctx, call)
		}
		// 集合元数据单独有界，不作为第二份表示预算；避免不同集合组合无限续生。
		if len(dg.terms) < defaultQueueSize {
			break
		}
		changed := dg.changed
		dg.mu.Unlock()
		select {
		case <-ctx.Done():
			lease.release()
			return ctx.Err()
		case <-changed:
		}
	}
	call := &detachedCall{done: make(chan struct{}), priority: make(chan struct{})}
	if foreground {
		call.promote.Do(func() { close(call.priority) })
	}
	dg.terms[key] = call
	dg.wg.Add(1)
	dg.mu.Unlock()

	go func() {
		defer dg.wg.Done()
		defer lease.release()
		// 后台续生核心：不用调用方的 ctx——客户端断开≠生成作废；服务关闭会取消。
		// panic 兜底（2026-10-17）：恢复后写 call.err 并正常 close(done)，
		// 等待者拿到明确错误而不是永久挂起；单文件致崩不终止服务进程。
		defer func() {
			if p := recover(); p != nil {
				call.err = fmt.Errorf("缩略图后台续生 panic: %v\n%s", p, debug.Stack())
				slog.Error("缩略图后台续生 panic 已恢复",
					"panic", p, "stack", string(debug.Stack()))
			}
			close(call.done)
			dg.mu.Lock()
			delete(dg.terms, key)
			close(dg.changed)
			dg.changed = make(chan struct{})
			dg.mu.Unlock()
		}()
		call.err = fn(call.priority)
	}()

	return waitDetached(ctx, call)
}

func waitDetached(ctx context.Context, call *detachedCall) error {
	select {
	case <-call.done:
		return call.err
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (dg *detachedGroup) stop() {
	dg.mu.Lock()
	defer dg.mu.Unlock()
	if !dg.closed {
		dg.closed = true
		close(dg.changed)
		dg.changed = make(chan struct{})
	}
}

// EnsureDetached 是懒生成端点（httpapi GetMediaThumbAssetId）的入口：
// 缓存命中直接返回；未命中经 detachedGroup 单飞合并后在后台续生。
// 等待语义与旧同步 Ensure 兼容——客户端活着且生成完成时返回 nil，
// 本次请求即可直接读盘回图（首访不等二连击）；客户端已取消则返回
// ctx.Err()，httpapi 按「缩略图不可用」404 占位语义处理（连接已断，
// 写不写都无观感差异），生成继续。
func (g *Generator) EnsureDetached(ctx context.Context, assetID, srcPath string, kind Kind, sizes []Size) error {
	version, err := g.sourceVersion(srcPath)
	if err != nil {
		return err
	}
	g.initLifecycle()
	keys := g.missingRepresentations(assetID, version, sizes)
	if len(keys) == 0 {
		return nil
	}
	// 只有准入等待依附请求；已接纳集合与每个尺寸都依附服务生命周期。
	admission, cancel := context.WithTimeout(ctx, frameTimeout)
	defer cancel()
	lease, err := g.budget.acquire(admission, keys, true)
	if err != nil {
		return err
	}
	return g.detached.doOwned(fmt.Sprintf("%s|%s|%v", assetID, version, sizes), admission, true, lease, func(<-chan struct{}) error {
		return g.ensureVersion(g.life, assetID, srcPath, kind, sizes, version, true)
	})
}
