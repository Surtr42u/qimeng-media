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
	"sync"
	"time"

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
// 旧修订号的键永不命中（免逐条清理）。
type recommendCache struct {
	mu       sync.Mutex
	rev      int64
	entries  map[recommendCacheKey]recommendCacheEntry
	inflight map[recommendCacheKey]*recommendInflightCall
}

// recommendInflightCall 一轮进行中的计算（单飞）：冷启动首条请求与开机预热
// 并发到达时共享同一次全库计算，避免重复跑聚合+打分管线。
type recommendInflightCall struct {
	done chan struct{}
	body []gen.AssetSummary
	ids  []string
	err  error
}

func newRecommendCache() *recommendCache {
	return &recommendCache{
		entries:  make(map[recommendCacheKey]recommendCacheEntry),
		inflight: make(map[recommendCacheKey]*recommendInflightCall),
	}
}

// do 缓存主入口：命中返回缓存条目；未命中在单飞保护下执行 compute（并发
// 同键只跑一次，其余等待共享同一结果），成功后登记缓存。compute 按约定
// 不写展示计数——计数是「真实展示」语义（DOMAIN_RULES §1.4.3），由真实
// 服务路径取得结果后自行回写，预热路径刻意不回写。
// panic 契约（2026-09-18 评审补丁）：compute panic 时等待方拿到转译错误
// 返回而非永久阻塞，席位 goroutine 原样上抛交上层 recover——见 runSingleflight。
func (c *recommendCache) do(key recommendCacheKey, compute func() ([]gen.AssetSummary, []string, error)) ([]gen.AssetSummary, []string, error) {
	if e, ok := c.get(key); ok {
		return e.body, e.assetIDs, nil
	}
	c.mu.Lock()
	if call, ok := c.inflight[key]; ok {
		c.mu.Unlock()
		<-call.done
		return call.body, call.ids, call.err
	}
	call := &recommendInflightCall{done: make(chan struct{})}
	c.inflight[key] = call
	c.mu.Unlock()

	body, ids, err := c.runSingleflight(key, call, compute)
	if err == nil {
		c.put(key, body, ids)
	}
	return body, ids, err
}

// runSingleflight 席位持有者的计算执行体。回填结果→放行等待方→清理槽位
// 三步全部收在 defer 里：compute panic 也必须走完（否则等待方永久阻塞，
// inflight 槽位泄漏会让该键后续所有请求挂死到重启）；恢复路径把 panic
// 转译成错误交给等待方后原样 re-panic，堆栈仍由上层（net/http recover）
// 记录，不吞。放行先于清槽：close(done) 与 delete 之间到达的并发请求还能
// 挂上 inflight 即刻取走结果，不触发重复计算。
func (c *recommendCache) runSingleflight(key recommendCacheKey, call *recommendInflightCall, compute func() ([]gen.AssetSummary, []string, error)) (body []gen.AssetSummary, ids []string, err error) {
	defer func() {
		p := recover()
		if p != nil {
			body, ids, err = nil, nil, fmt.Errorf("推荐流计算 panic: %v", p)
		}
		call.body, call.ids, call.err = body, ids, err
		close(call.done)
		c.mu.Lock()
		delete(c.inflight, key)
		c.mu.Unlock()
		if p != nil {
			panic(p)
		}
	}()
	body, ids, err = compute()
	return body, ids, err
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
