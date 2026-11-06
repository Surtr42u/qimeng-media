package research

import (
	"sync"
	"time"
)

// budget 是进程内固定窗口计数器（可注入时钟，便于测试窗口换算）。
//
// 为什么用固定窗口而不是令牌桶：研究预算的语义是"每分钟/每小时/每天最多
// 多少次核验"，固定窗口与协议描述一一对应，排障时也能直接解释"下一分钟
// 重置"；令牌桶的平滑速率对交互式核验没有额外价值，反而难解释。
// 窗口边界按 UTC 对齐（time.Truncate 以 Unix 纪元为基准）：小时窗口按
// 整点重置，日窗口按 UTC 零点重置；进程重启即清零（内存计数）。
type budget struct {
	mu     sync.Mutex
	now    func() time.Time
	limit  int
	window time.Duration
	start  time.Time
	used   int
}

// newBudget 构造预算；limit<=0 表示不限制。
func newBudget(now func() time.Time, limit int, window time.Duration) *budget {
	return &budget{now: now, limit: limit, window: window}
}

// take 尝试占用一次预算。
func (b *budget) take() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.limit <= 0 {
		return true
	}
	start := b.now().Truncate(b.window)
	if !start.Equal(b.start) {
		b.start = start
		b.used = 0
	}
	if b.used >= b.limit {
		return false
	}
	b.used++
	return true
}

// refund 归还一次占用（用于"两个预算都要通过才算成功"的组合判定）。
func (b *budget) refund() {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.used > 0 {
		b.used--
	}
}

// limits 汇总全部进程内预算：每个 provider 每分钟一次，Lens 每小时+每日。
type limits struct {
	mu        sync.Mutex
	now       func() time.Time
	perMinute int
	lensHour  int
	lensDay   int
	providers map[Name]*budget
	hour      *budget
	day       *budget
}

// newLimits 构造预算集合（provider 预算按需建，已知 provider 也预先建）。
func newLimits(now func() time.Time, perMinute, lensPerHour, lensPerDay int) *limits {
	return &limits{
		now:       now,
		perMinute: perMinute,
		lensHour:  lensPerHour,
		lensDay:   lensPerDay,
		providers: make(map[Name]*budget),
		hour:      newBudget(now, lensPerHour, time.Hour),
		day:       newBudget(now, lensPerDay, 24*time.Hour),
	}
}

// takeProvider 占用一次 provider 预算。
func (l *limits) takeProvider(name Name) bool {
	l.mu.Lock()
	b, ok := l.providers[name]
	if !ok {
		b = newBudget(l.now, l.perMinute, time.Minute)
		l.providers[name] = b
	}
	l.mu.Unlock()
	return b.take()
}

// takeLens 占用一次 Lens 预算：日预算与小时预算都必须通过；小时通过而日
// 拒绝时归还小时占用，避免"被拒绝的请求白吃小时配额"。
func (l *limits) takeLens() bool {
	if !l.day.take() {
		return false
	}
	if !l.hour.take() {
		l.day.refund()
		return false
	}
	return true
}
