// recommend_cache.go：推荐流短 TTL 响应缓存（2026-09-18 性能批）。
//
// 为什么缓存：/recommendations 每请求做全库候选聚合 + 全库标签 + 十维打分 +
// 展示计数回写（recommendations.go），在手机 SoC（ADR-0015 单机形态）大库上
// 是秒级；而 App 侧重复请求形态固定（同 seed 同参重试、tab 往返、短时间多次
// 回首页），短窗内原样复用把重复请求从秒级降到毫秒级。
//
// 语义边界（已记档 DOMAIN_RULES §1.4.3，行为变化以此为准）：
//   - 缓存键含全部打分输入的环境维度（日界/seed/mediaType/cosOnly/权重快照/
//     修订号/翻页窗），命中时返回与首算完全相同的切片。
//   - 命中路径仍对返回项回写当日展示计数（§1.4.3 计数不丢，走事务批量写）；
//     但排序所用的计数快照不随命中刷新——同键请求在 TTL 内恒同序（旧行为
//     是每次按最新计数重排），累计的每日惩罚在下次未命中重算时统一生效。
//   - 高频行为事件（浏览 open/play/dwell）不做主动失效——每开一个视频就失效
//     会让缓存常冷；行为维度（engagement/recency）的影响在 TTL 到期后自然
//     反映，偏差窗口 ≤ recommendCacheTTL。
//   - 结构性变更的失效主通道 = Server 装配期订阅 library.changed 事件
//     （server.go New：watch 增量/扫描完成/上传/回收站/标签/整理/库开关全部
//     汇此，一处覆盖）；不发该事件的变更（点赞/收藏/导入/作者重建）由各
//     handler 直接调 invalidateRecommendCache。修订号让旧条目整体作废。
//   - 冷启动首击由开机预热承接（recommend_prewarm.go）：起监听即后台预计算
//     App 首屏同键默认流，与首条请求经 do() 的单飞共享同一次计算。
package httpapi

import (
	"fmt"
	"log/slog"
	"runtime/debug"
	"sync"
	"time"

	"golang.org/x/sync/singleflight"

	"qimeng-media/server/internal/httpapi/gen"
)

const (
	// recommendCacheTTL 条目存活窗。下界：覆盖「登录后连续操作」的短窗收益；
	// 上界：结构性失效已兜住主要漂移源，这里只约束行为维度的陈旧上限。
	// 60s 是陈旧感与命中率的折中，不做配置项（调参需求出现再入 config）。
	recommendCacheTTL = time.Minute

	// recommendCacheMaxEntries 条目上限。键空间 = (修订号,日,seed,mediaType,
	// cosOnly,权重,翻页窗)，单用户实际活跃键个位数；超限整体清空（简单优先，
	// 不做逐条 LRU——单条为一次翻页窗的 200 项响应，重建成本可接受）。
	recommendCacheMaxEntries = 32
)

// recommendCacheKey 标识一次推荐计算的全部输入环境维度。翻页窗用原始
// offset+limit 双值（不按候选规模截断——截断值随库内容漂移，会破坏键稳定
// 性；且二者缺一不可：offset=2&limit=2 与 offset=0&limit=4 的 offset+limit
// 同为 4 但切片窗不同，仅存和值会互相串页——OffsetPaging 用例抓过）。
type recommendCacheKey struct {
	rev       int64
	day       string
	seed      int
	mediaType string // "" = 全部类型
	cosOnly   bool
	prefs     string // 权重快照指纹（recommendPrefsFingerprint）
	offset    int
	limit     int
}

// recommendCacheEntry 一份可复用的推荐响应切片。
type recommendCacheEntry struct {
	storedAt time.Time
	body     []gen.AssetSummary
	assetIDs []string // 命中路径回写当日展示计数的对象（§1.4.3）
}

// recommendCache 并发安全的推荐响应缓存；rev 随 invalidate 递增，
// 旧修订号的键永不命中（免逐条清理）。并发同键去重（单飞）由 sf 承担——
// 选型记档（AI_README_FIRST「选型约束」轻量通道，2026-10-02 治理批）：
//   - 业界通行方案 = golang.org/x/sync/singleflight（Go 官方扩展件，本仓
//     依赖分级决策序第 3 级），替代 2026-09-18 手写的 inflight map + 席位
//     goroutine 簿记（根因 = 当年规则不对称下条件反射式自研）；
//   - 不直接采信其默认 panic 语义：上游 doCall 对 compute panic 的处置是
//     `go panic(e)` 兜底（v0.23.0 singleflight.go 实证）——裸崩进程并遗留
//     select{} 常驻 goroutine；Do 路径等待方也会跟着 panic。故 compute 的
//     panic 在进入 Group 前就地转译（safeCompute），Group 永远收到正常
//     返回，其清理时机（doCall defer：放行等待方→清槽）与原手写版逐点等价；
//   - 复查条件：x/sync 后续版本若提供「panic 可选转译」官方面（upstream
//     issue 讨论中）可退役 safeCompute 包装；退役触发 = 转译层引入行为
//     分叉或上游语义收敛后包装成为死代码。
type recommendCache struct {
	mu      sync.Mutex
	rev     int64
	entries map[recommendCacheKey]recommendCacheEntry
	sf      singleflight.Group
}

func newRecommendCache() *recommendCache {
	// singleflight.Group 零值可用（内部 map 惰性初始化），无需显式装配。
	return &recommendCache{
		entries: make(map[recommendCacheKey]recommendCacheEntry),
	}
}

// sfKeyString singleflight 字符串键。结构体含 string 字段，禁止位置化拼接
// （fmt.Sprintf("%v") 一类形态遇字段值含分隔符可碰撞——mediaType 来自查询
// 参数），逐字段 %q 转义后键语义与旧 inflight map（可比较结构体直接作键）
// 逐字段等价、无歧义。开销：每未命中请求一次小结构体格式化，相对一次全库
// 打分管线可忽略。
func sfKeyString(key recommendCacheKey) string {
	return fmt.Sprintf("rev=%d|day=%q|seed=%d|mediaType=%q|cosOnly=%t|prefs=%q|offset=%d|limit=%d",
		key.rev, key.day, key.seed, key.mediaType, key.cosOnly, key.prefs, key.offset, key.limit)
}

// do 缓存主入口：命中返回缓存条目；未命中在 singleflight 单飞保护下执行
// compute（并发同键只跑一次，其余等待共享同一结果），成功后登记缓存。
// compute 按约定不写展示计数——计数是「真实展示」语义（DOMAIN_RULES §1.4.3），
// 由真实服务路径取得结果后自行回写，预热路径刻意不回写。
// panic 契约（2026-10-02 治理批改写，2026-09-18 评审补丁的「席位方 re-panic
// 交 net/http recover」契约退役）：compute 的 panic 在席位 goroutine 就地
// 转译为错误并 slog 记录堆栈（原 net/http recover 的日志职责收编于此），
// 全部调用方（含等待方）拿到转译错误而非 panic/永久阻塞；槽位清理由
// singleflight 的 doCall defer 承担，同键下一轮正常重算。改写动机与上游
// panic 语义不兼容的实证见 recommendCache 注记与 safeCompute。
func (c *recommendCache) do(key recommendCacheKey, compute func() ([]gen.AssetSummary, []string, error)) ([]gen.AssetSummary, []string, error) {
	if e, ok := c.get(key); ok {
		return e.body, e.assetIDs, nil
	}
	res, err, _ := c.sf.Do(sfKeyString(key), func() (any, error) {
		body, ids, err := c.safeCompute(compute)
		if err != nil {
			return nil, err
		}
		c.put(key, body, ids)
		return sfResult{body: body, ids: ids}, nil
	})
	if err != nil {
		return nil, nil, err
	}
	r := res.(sfResult)
	return r.body, r.ids, nil
}

// sfResult 单飞执行体的结果载体（Do 的 val 通道要求 any）。
type sfResult struct {
	body []gen.AssetSummary
	ids  []string
}

// safeCompute panic 转译边界：compute 的 panic 不得逃入 singleflight（上游
// 对 panic 的处置是 `go panic(e)` 兜底 + Do 等待方跟随 panic，v0.23.0 doCall
// 实证，见 recommendCache 注记）——就地 recover 转译为错误，slog 带堆栈记录
// （生产语义：原由 net/http recover 记录的崩溃现场仍可追溯，且错误路径让
// handler 走正常 500 响应而非连接中断）。
func (c *recommendCache) safeCompute(compute func() ([]gen.AssetSummary, []string, error)) (body []gen.AssetSummary, ids []string, err error) {
	defer func() {
		if p := recover(); p != nil {
			body, ids, err = nil, nil, fmt.Errorf("推荐流计算 panic: %v", p)
			slog.Error("推荐流计算 panic（单飞席位转译为错误返回）",
				"panic", p, "stack", string(debug.Stack()))
		}
	}()
	return compute()
}

// get 命中返回条目副本（值拷贝，body/assetIDs 共享底层数组——只读约定，
// 调用方不得修改），过期/不存在返回 false。
func (c *recommendCache) get(key recommendCacheKey) (recommendCacheEntry, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.entries[key]
	if !ok {
		return recommendCacheEntry{}, false
	}
	if time.Since(e.storedAt) >= recommendCacheTTL {
		delete(c.entries, key)
		return recommendCacheEntry{}, false
	}
	return e, true
}

// put 存入一轮回填；超限整体清空后重存（见 recommendCacheMaxEntries 注释）。
func (c *recommendCache) put(key recommendCacheKey, body []gen.AssetSummary, assetIDs []string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.entries) >= recommendCacheMaxEntries {
		c.entries = make(map[recommendCacheKey]recommendCacheEntry)
	}
	c.entries[key] = recommendCacheEntry{storedAt: time.Now(), body: body, assetIDs: assetIDs}
}

// invalidate 结构性变更后的整体失效：修订号 +1，全部旧键即刻不命中。
func (c *recommendCache) invalidate() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.rev++
	c.entries = make(map[recommendCacheKey]recommendCacheEntry)
}

// revision 供构造键时取当前修订号（锁内快照，与 invalidate 串行化）。
func (c *recommendCache) revision() int64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.rev
}

// invalidateRecommendCache 推荐流缓存失效入口（推荐输入发生结构性变更后调用；
// 哪些变更算「结构性」见本文件头语义边界）。nil 防御：部分测试直接构造
// Server 字段时未装配缓存，失效是纯性能优化，跳过无害。
func (s *Server) invalidateRecommendCache() {
	if s.recommendCache != nil {
		s.recommendCache.invalidate()
	}
}
