package syncchannel

import (
	"sync"
	"time"
)

// 本机同步通道运行参数（注释含含义与来源；与协议联动的值标注同步责任）。
const (
	// localSyncRecentCap 最近成功条目的环形上限：与 api/openapi.yaml
	// LocalSyncStatus.recentSynced 描述（「环形保留，上限 20 条」）双写同步，
	// 协议侧改动须同步此处，反之亦然。
	localSyncRecentCap = 20
	// localSyncStatusItemCap 状态端点 items 截断上限：与 openapi
	// LocalSyncStatus.items 描述（「上限 500 条，超出按路径序截断」）双写
	// 同步；counts 始终全量口径（截断前）。
	localSyncStatusItemCap = 500
	// localSyncMaxTxtBytes 单个作者表 TXT 的读取护栏（10MB）：作者表是
	// 手写文本，正常量级远小于此；护栏防「用户把视频改名 .txt」撑爆内存。
	// 与 errors.go maxJSONBody（JSON 请求体 1MB）是两套独立上限，互不相干。
	localSyncMaxTxtBytes = int64(10) << 20
	// TXT 归档目录名（.synced）单一来源 = localsync.ReservedInternalDirName
	// （扫描剪枝与归档移动共用，编译期同值；本包不再手抄第二份）。
)

// LocalSyncItemState 的四个取值：与 api/openapi.yaml LocalSyncItemState
// enum 逐字一致（协议侧改动须同步这里，反之亦然）。
const (
	StateWaitingStable = "waiting-stable"
	StateFailed        = "failed"
	StateIgnored       = "ignored"
	StateSynced        = "synced"
)

// localSyncItem 是一个同步源条目的运行态快照（mu 保护；state 字符串取值
// 见上方常量）。state=synced 只出现在 recent（items 里的成功条目即删）。
type localSyncItem struct {
	relPath       string
	kind          string
	libraryName   string
	state         string
	errText       string
	size          int64
	attempts      int
	lastAttemptAt time.Time
}

// localSyncObs 是一轮扫描对某文件的观测（size/mtime），跨轮比对用。
type localSyncObs struct {
	size  int64
	mtime time.Time
}

// localSyncState 是通道全部运行态。并发模型：runner 单 goroutine + 端点
// 并发读，全部访问经 mu；kick 是容量 1 的非阻塞触发信号（trigger 端点
// 投递、runner 消费，满即丢弃——积压无意义）。
type localSyncState struct {
	mu           sync.Mutex
	observations map[string]localSyncObs
	items        map[string]*localSyncItem
	recent       []localSyncItem
	syncedTotal  int
	lastCycleAt  time.Time
	lastErr      string
	paused       bool
	kick         chan struct{}
}

// newLocalSyncState 构造运行态（New 组装期调用一次）。
func newLocalSyncState() *localSyncState {
	return &localSyncState{
		observations: map[string]localSyncObs{},
		items:        map[string]*localSyncItem{},
		kick:         make(chan struct{}, 1),
	}
}

// lsSetLastErr 记录通道级错误（空串 = 健康）。
func (s *Service) lsSetLastErr(msg string) {
	s.localSync.mu.Lock()
	defer s.localSync.mu.Unlock()
	s.localSync.lastErr = msg
}

// lsLastErr 读通道级错误快照（空串 = 健康）：trigger 端点 409 判定用，
// 与 runner 写入同一把锁（无锁读会撕裂「置错/清错」的可见性）。
func (s *Service) lsLastErr() string {
	s.localSync.mu.Lock()
	defer s.localSync.mu.Unlock()
	return s.localSync.lastErr
}

// lsSetPaused 记录本轮媒体处理闸门状态。
func (s *Service) lsSetPaused(paused bool) {
	s.localSync.mu.Lock()
	defer s.localSync.mu.Unlock()
	s.localSync.paused = paused
}

// lsItemLocked 取（或建）条目——调用方必须已持 mu。
func (s *Service) lsItemLocked(rel string) *localSyncItem {
	it, ok := s.localSync.items[rel]
	if !ok {
		it = &localSyncItem{relPath: rel}
		s.localSync.items[rel] = it
	}
	return it
}

// lsMarkWaiting 记录「等待稳定/暂停」状态（不触碰 attempts——等待不是
// 尝试，此前失败攒下的尝试数保留）。libraryName 可空。
func (s *Service) lsMarkWaiting(rel, kind, libraryName string, size int64) {
	s.localSync.mu.Lock()
	defer s.localSync.mu.Unlock()
	it := s.lsItemLocked(rel)
	it.kind = kind
	it.libraryName = libraryName
	it.state = StateWaitingStable
	it.errText = ""
	it.size = size
}

// lsMarkFailed 记录失败状态（文件原地保留，下轮重试）。attempts 与
// lastAttemptAt 在此处一并落账——失败即一次完整尝试；与状态字段同一把锁
// 一次写入（首次创建即完整字段），消除此前「先建壳递增、再落状态」两次
// 加锁之间并发 GET 可见枚举外空串 state 条目的竞态。
func (s *Service) lsMarkFailed(rel, kind, libraryName, errText string, size int64) {
	s.localSync.mu.Lock()
	defer s.localSync.mu.Unlock()
	it := s.lsItemLocked(rel)
	it.kind = kind
	it.libraryName = libraryName
	it.state = StateFailed
	it.errText = errText
	it.size = size
	it.attempts++
	it.lastAttemptAt = s.now()
}

// lsMarkIgnored 记录明确忽略条目（非媒体扩展名/根级媒体/隐藏条目等）。
func (s *Service) lsMarkIgnored(rel, kind, reason string) {
	s.localSync.mu.Lock()
	defer s.localSync.mu.Unlock()
	it := s.lsItemLocked(rel)
	it.kind = kind
	it.libraryName = ""
	it.state = StateIgnored
	it.errText = reason
}

// lsMarkSynced 记录成功：条目移出 items（源文件已不在同步源）、头插进
// recent 环形（截到上限）、累计数 +1。errNote 非空 = 成功但有备注
// （如已落库位但注册交给扫描器兜底）。
func (s *Service) lsMarkSynced(rel, kind, libraryName, errNote string, size int64) {
	s.localSync.mu.Lock()
	defer s.localSync.mu.Unlock()
	delete(s.localSync.items, rel)
	item := localSyncItem{
		relPath:     rel,
		kind:        kind,
		libraryName: libraryName,
		state:       StateSynced,
		errText:     errNote,
		size:        size,
	}
	s.localSync.recent = append([]localSyncItem{item}, s.localSync.recent...)
	if len(s.localSync.recent) > localSyncRecentCap {
		s.localSync.recent = s.localSync.recent[:localSyncRecentCap]
	}
	s.localSync.syncedTotal++
}

// lsPrune 清掉本轮已不在同步源内的观测与条目（文件被外部移走/删除后，
// 状态面板不应残留幽灵条目；recent 是历史记录，不清）。
func (s *Service) lsPrune(alive map[string]bool) {
	s.localSync.mu.Lock()
	defer s.localSync.mu.Unlock()
	for rel := range s.localSync.observations {
		if !alive[rel] {
			delete(s.localSync.observations, rel)
		}
	}
	for rel := range s.localSync.items {
		if !alive[rel] {
			delete(s.localSync.items, rel)
		}
	}
}
