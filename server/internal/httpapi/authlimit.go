// authlimit.go：auth 端点（setup/login/dev-login）的进程内固定窗口限速器。
// 标准库实现（sync + time），刻意不引 golang.org/x/time/rate——go.mod 没有
// 该依赖，引新依赖须走 ADR，而 auth 场景"每分钟 N 次"的固定窗口已够用。
package httpapi

import (
	"sync"
	"time"
)

// authRateLimitMax 是每个限速窗口内允许的 auth 尝试次数上限。
// 为什么是 10/分钟：密码校验走 argon2id（m=64MB/次），限速同时缓冲
// 暴力猜解与内存 DoS（10 次/分钟 ≈ 10×64MB/分钟 的内存压力上限，
// 单机 NAS 可承受）。
const authRateLimitMax = 10

// authRateLimitWindow 是限速窗口长度（固定窗口，非滑动窗口：窗口边界
// 处最多双倍突发，对 NAS 单用户场景可接受，换取零分配实现）。
const authRateLimitWindow = time.Minute

// authLimiter 是单实例固定窗口计数器。auth 端点全在单进程内（无多实例
// 部署形态），进程内限速即全局限速；窗口起点取第一次请求时刻而非整分
// 钟对齐，实现更简单且不影响语义。
type authLimiter struct {
	mu          sync.Mutex
	max         int
	window      time.Duration
	windowStart time.Time // 零值 = 尚无请求，首次 allow 时开窗
	count       int       // 当前窗口内已放行的次数
}

// newAuthLimiter 构造限速器；装配处传包级常量（authRateLimitMax /
// authRateLimitWindow），测试可传小窗口验证过期重置。
func newAuthLimiter(max int, window time.Duration) *authLimiter {
	return &authLimiter{max: max, window: window}
}

// allow 判定一次尝试是否放行（放行即计数）。now 由调用方传入
// （Server.now()），使测试能用 fakeClock 推进窗口而无需真实时间流逝。
// 窗口过期自动重置——客户端"稍后再试"的语义来源。
func (l *authLimiter) allow(now time.Time) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.windowStart.IsZero() || !now.Before(l.windowStart.Add(l.window)) {
		// 首次请求或窗口已过：开新窗（now 落在旧窗结束点及之后都算过期）
		l.windowStart = now
		l.count = 0
	}
	if l.count >= l.max {
		return false
	}
	l.count++
	return true
}
