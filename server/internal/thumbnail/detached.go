package thumbnail

import (
	"context"
	"fmt"
	"os"
	"sync"
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
//   - 续生：生成体挂 context.Background()（genSlots 仍限并发、frameTimeout
//     仍兜底单尺寸时长），调用方取消只结束「等待」，不杀生成——本次请求
//     拿到 ctx.Err() 退出，但缩略图终将落盘，下次请求命中缓存；
//   - 先查后做：进入单飞前先查缓存存在性，命中零成本返回（命中率高时
//     常规路径零开销）。
//
// 行为由 detached_test.go 锁定（等待者取消不杀生成/同 key 合并/不同 key 不合并）。
type detachedGroup struct {
	mu    sync.Mutex
	terms map[string]*detachedCall
}

// detachedCall 一次进行中的生成：done 关闭即完成（err 为最终结果）。
// err 先写后 close——close 建立 happen-before，等待者收到关闭即可安全读 err。
type detachedCall struct {
	done chan struct{}
	err  error
}

func newDetachedGroup() *detachedGroup {
	return &detachedGroup{terms: map[string]*detachedCall{}}
}

// do 对 key 执行 fn：已有进行中的同 key 调用则直接等待其结果（等待可被
// ctx 取消中断，但那不影响 fn 继续跑完）；否则立即注册并起独立 goroutine
// 执行 fn（fn 内部自行管并发与超时，本层不限制）。返回值：fn 的结果，或
// ctx 取消时的 ctx.Err()。
// 去重只覆盖「进行中」——fn 完成即回收条目（close(done) 后 delete），迟到者
// 重新发起，与 x/sync/singleflight 同款语义；真链路无重跑成本——EnsureDetached
// 先查磁盘缓存，落盘后的请求走快路径根本不进 do。
func (dg *detachedGroup) do(key string, ctx context.Context, fn func() error) error {
	dg.mu.Lock()
	if call, ok := dg.terms[key]; ok {
		dg.mu.Unlock()
		select {
		case <-call.done:
			return call.err
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	call := &detachedCall{done: make(chan struct{})}
	dg.terms[key] = call
	dg.mu.Unlock()

	go func() {
		// 后台续生核心：不用调用方的 ctx——客户端断开≠生成作废
		call.err = fn()
		close(call.done)
		dg.mu.Lock()
		delete(dg.terms, key)
		dg.mu.Unlock()
	}()

	select {
	case <-call.done:
		return call.err
	case <-ctx.Done():
		return ctx.Err()
	}
}

// EnsureDetached 是懒生成端点（httpapi GetMediaThumbAssetId）的入口：
// 缓存命中直接返回；未命中经 detachedGroup 单飞合并后在后台续生。
// 等待语义与旧同步 Ensure 兼容——客户端活着且生成完成时返回 nil，
// 本次请求即可直接读盘回图（首访不等二连击）；客户端已取消则返回
// ctx.Err()，httpapi 按「缩略图不可用」404 占位语义处理（连接已断，
// 写不写都无观感差异），生成继续。
func (g *Generator) EnsureDetached(ctx context.Context, assetID, srcPath string, kind Kind, sizes []Size) error {
	for _, size := range sizes {
		if _, err := os.Stat(g.thumbDst(assetID, size)); err != nil {
			// 任一尺寸缺失才需要生成；全存在走快路径零 ffmpeg
			return g.detached.do(fmt.Sprintf("%s|%v", assetID, sizes), ctx, func() error {
				return g.Ensure(context.Background(), assetID, srcPath, kind, sizes)
			})
		}
	}
	return nil
}
