package thumbnail

import (
	"context"
	"runtime"
)

// genSlots 是进程级 ffmpeg 生成并发闸：限制同一进程内同时运行的缩略图生成任务数
// （2026-09-13 用户真机 BUG-A 侧修）。为什么要存在：懒生成端点（httpapi 对每个
// 缩略图未命中请求同步调 Generator.Ensure）与工作池路径最终都汇入 ensureOne 起
// ffmpeg 进程，此前无任何并发上限——真库首屏几十个请求同时未命中 = ffmpeg 风暴，
// 打满 NAS CPU 后拖慢 original 媒体流，是客户端侧 10s 读超时误报「无法解码」的诱因。
//
// 为什么是包级而非 Generator 实例级：ffmpeg 是进程级共享资源（CPU 核数固定），
// 并发预算按进程收口，多个 Generator 实例（测试/未来多库装配）也不会超卖；
// 生产装配恒单例（main 组合根），包级与实例级行为一致。
//
// 为什么容量取 runtime.GOMAXPROCS(0)：ffmpeg 是 CPU 密集外部进程，并发超过可用
// 核数只会互相抢时间片；跟 GOMAXPROCS 而非 NumCPU——容器/部署侧限核或调低调度
// 并发时（含 GOMAXPROCS 环境变量），闸随运行时口径自动收紧。
var genSlots = newSemaphore(runtime.GOMAXPROCS(0))

// semaphore 固定容量的计数信号量：acquire 占闸位（满载排队）、release 还闸位。
// 为什么自写而不引 golang.org/x/sync/semaphore：容量运行期恒定、无批量/ TryAcquire
// 需求，带缓冲 channel 的 select-ctx 语义足够且不新增依赖；行为由 semaphore_test.go 锁定。
type semaphore struct {
	// slots 容量即并发上限：发送=占用（满则阻塞排队），接收=释放。
	slots chan struct{}
}

// newSemaphore 创建闸。capacity<=0 防御性夹到 1：容量 0 的无缓冲 channel 会把
// 「占用」漂移成「必须同时 acquire/release 的会合」，闸位语义失效。
func newSemaphore(capacity int) *semaphore {
	if capacity <= 0 {
		capacity = 1
	}
	return &semaphore{slots: make(chan struct{}, capacity)}
}

// acquire 占用一个闸位：有空位立即成功；满载时排队等待，ctx 取消（HTTP 请求断开/
// 上游超时）立即中断排队并返回 false——排队等待而非拒绝，上游放弃则不白占队伍。
// 快路径先行：有空位直接占用（即便 ctx 已取消——调用方下一步 exec 会立即失败），
// 避免已就绪场景被 select 的双就绪随机分支误判成取消。
func (s *semaphore) acquire(ctx context.Context) bool {
	select {
	case s.slots <- struct{}{}:
		return true
	default:
	}
	select {
	case s.slots <- struct{}{}:
		return true
	case <-ctx.Done():
		return false
	}
}

// release 归还一个闸位（唤醒一个排队者）。必须与成功 acquire 一一配对
// （ensureOne 经 defer 释放，成功/失败/panic 路径都不漏）。
func (s *semaphore) release() {
	<-s.slots
}
