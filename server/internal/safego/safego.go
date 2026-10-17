// Package safego 为长驻后台 goroutine 提供 panic 兜底。
//
// 为什么需要它：Go 的 panic 若在任意 goroutine 中未被 recover，会终止整个
// 进程。net/http 对请求 handler 有内建 recover，但扫描/预热/清扫/调度/同步
// 这些后台循环不在 HTTP 请求路径上——一次 nil map、越界或第三方库 panic
// 就会让整个服务退出（容器虽然会拉起，但全部客户端断线、在途任务丢失）。
// 本项目后台任务全部是"可重试/可自愈"的语义，单个任务崩溃应该只留下一条
// 带堆栈的错误日志并让下一轮继续，而不是杀掉进程。
//
// 用法：
//
//	safego.Go(logger, "缩略图预热", func() { s.warmupLoop() })
//
// 约束：fn 内若自行起子 goroutine，需对子 goroutine 再包一层（recover 只
// 作用于当前 goroutine）。
package safego

import (
	"log/slog"
	"runtime/debug"
)

// Go 启动一个带 panic 兜底的 goroutine。
//
// name 是任务名（日志字段 task），调用方给稳定的短名（如"扫描轮询"），
// 便于日志检索与告警聚合。logger 为 nil 时回落 slog.Default()。
func Go(logger *slog.Logger, name string, fn func()) {
	if logger == nil {
		logger = slog.Default()
	}
	go func() {
		defer func() {
			if p := recover(); p != nil {
				// debug.Stack 在 recover 的 defer 内仍能取到崩溃点堆栈。
				logger.Error("后台任务 panic 已恢复（进程继续运行）",
					"task", name, "panic", p, "stack", string(debug.Stack()))
			}
		}()
		fn()
	}()
}
