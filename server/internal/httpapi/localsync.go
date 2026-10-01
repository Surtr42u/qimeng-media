// localsync.go：本机文件夹自动同步通道的运行态与状态端点（ADR-0030）。
//
// 通道语义：服务端轮询 QIMENG_LOCAL_SYNC_ROOT——直接子文件夹名=库名（净化
// 匹配），媒体走直传同款校验与入库，成功后源文件 move 入库根；同步根直接下
// 的 *.txt 走 importTxt（keep 语义），成功后归档进 .synced/；一切失败文件
// 原地保留、状态可见、每轮自动重试。
//
// 本文件只放内存运行态（观测/条目/最近成功）与两个协议端点；每轮编排逻辑
// 见 localsync_runner.go；纯逻辑（匹配/扫描/重叠检查）在 internal/localsync。
package httpapi

import (
	"net/http"
	"sort"
	"sync"
	"time"

	"qimeng-media/server/internal/httpapi/gen"
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

// 协议错误码：通道未启用（POST /api/v1/local-sync/trigger 的 409）。
// 同步责任：与 api/openapi.yaml 该端点 409 响应描述（code=LOCAL_SYNC_DISABLED）
// 双写联动，改须双向同步。
const codeLocalSyncDisabled = "LOCAL_SYNC_DISABLED"

// LocalSyncItemState 的四个取值：与 api/openapi.yaml LocalSyncItemState
// enum 逐字一致（协议侧改动须同步这里，反之亦然）。
const (
	localSyncStateWaitingStable = "waiting-stable"
	localSyncStateFailed        = "failed"
	localSyncStateIgnored       = "ignored"
	localSyncStateSynced        = "synced"
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
func (s *Server) lsSetLastErr(msg string) {
	s.localSync.mu.Lock()
	defer s.localSync.mu.Unlock()
	s.localSync.lastErr = msg
}

// lsLastErr 读通道级错误快照（空串 = 健康）：trigger 端点 409 判定用，
// 与 runner 写入同一把锁（无锁读会撕裂「置错/清错」的可见性）。
func (s *Server) lsLastErr() string {
	s.localSync.mu.Lock()
	defer s.localSync.mu.Unlock()
	return s.localSync.lastErr
}

// lsSetPaused 记录本轮媒体处理闸门状态。
func (s *Server) lsSetPaused(paused bool) {
	s.localSync.mu.Lock()
	defer s.localSync.mu.Unlock()
	s.localSync.paused = paused
}

// lsItemLocked 取（或建）条目——调用方必须已持 mu。
func (s *Server) lsItemLocked(rel string) *localSyncItem {
	it, ok := s.localSync.items[rel]
	if !ok {
		it = &localSyncItem{relPath: rel}
		s.localSync.items[rel] = it
	}
	return it
}

// lsMarkWaiting 记录「等待稳定/暂停」状态（不触碰 attempts——等待不是
// 尝试，此前失败攒下的尝试数保留）。libraryName 可空。
func (s *Server) lsMarkWaiting(rel, kind, libraryName string, size int64) {
	s.localSync.mu.Lock()
	defer s.localSync.mu.Unlock()
	it := s.lsItemLocked(rel)
	it.kind = kind
	it.libraryName = libraryName
	it.state = localSyncStateWaitingStable
	it.errText = ""
	it.size = size
}

// lsMarkFailed 记录失败状态（文件原地保留，下轮重试）。attempts 与
// lastAttemptAt 在此处一并落账——失败即一次完整尝试；与状态字段同一把锁
// 一次写入（首次创建即完整字段），消除此前「先建壳递增、再落状态」两次
// 加锁之间并发 GET 可见枚举外空串 state 条目的竞态。
func (s *Server) lsMarkFailed(rel, kind, libraryName, errText string, size int64) {
	s.localSync.mu.Lock()
	defer s.localSync.mu.Unlock()
	it := s.lsItemLocked(rel)
	it.kind = kind
	it.libraryName = libraryName
	it.state = localSyncStateFailed
	it.errText = errText
	it.size = size
	it.attempts++
	it.lastAttemptAt = s.now()
}

// lsMarkIgnored 记录明确忽略条目（非媒体扩展名/根级媒体/隐藏条目等）。
func (s *Server) lsMarkIgnored(rel, kind, reason string) {
	s.localSync.mu.Lock()
	defer s.localSync.mu.Unlock()
	it := s.lsItemLocked(rel)
	it.kind = kind
	it.libraryName = ""
	it.state = localSyncStateIgnored
	it.errText = reason
}

// lsMarkSynced 记录成功：条目移出 items（源文件已不在同步源）、头插进
// recent 环形（截到上限）、累计数 +1。errNote 非空 = 成功但有备注
// （如已落库位但注册交给扫描器兜底）。
func (s *Server) lsMarkSynced(rel, kind, libraryName, errNote string, size int64) {
	s.localSync.mu.Lock()
	defer s.localSync.mu.Unlock()
	delete(s.localSync.items, rel)
	item := localSyncItem{
		relPath:     rel,
		kind:        kind,
		libraryName: libraryName,
		state:       localSyncStateSynced,
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
func (s *Server) lsPrune(alive map[string]bool) {
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

// lsItemToGen 装配协议 LocalSyncItem（可空字段按「有意义才填」口径置 nil，
// 避免 omitempty 指针序列化出空串/零值噪音）。
func lsItemToGen(it localSyncItem) gen.LocalSyncItem {
	kind := gen.LocalSyncItemKind(it.kind)
	state := gen.LocalSyncItemState(it.state)
	out := gen.LocalSyncItem{
		Path:  ptr(it.relPath),
		Kind:  &kind,
		State: &state,
	}
	if it.libraryName != "" {
		out.LibraryName = ptr(it.libraryName)
	}
	if it.errText != "" {
		out.Error = ptr(it.errText)
	}
	if it.attempts > 0 {
		out.Attempts = ptr(it.attempts)
	}
	if it.size > 0 {
		out.SizeBytes = ptr(it.size)
	}
	if !it.lastAttemptAt.IsZero() {
		out.LastAttemptAt = ptr(it.lastAttemptAt)
	}
	return out
}

// lsSnapshot 装配状态端点载荷（gen.LocalSyncStatus）。enabled 的口径：
// Root 已配置且通道级错误为空（目录不存在/重叠检查失败都会置 lastErr）。
func (s *Server) lsSnapshot(cfgRoot string, interval time.Duration) gen.LocalSyncStatus {
	s.localSync.mu.Lock()
	defer s.localSync.mu.Unlock()
	var failed, ignored, waiting int
	paths := make([]string, 0, len(s.localSync.items))
	for p := range s.localSync.items {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	for _, it := range s.localSync.items {
		switch it.state {
		case localSyncStateFailed:
			failed++
		case localSyncStateIgnored:
			ignored++
		case localSyncStateWaitingStable:
			waiting++
		}
	}
	if len(paths) > localSyncStatusItemCap {
		paths = paths[:localSyncStatusItemCap]
	}
	items := make([]gen.LocalSyncItem, 0, len(paths))
	for _, p := range paths {
		items = append(items, lsItemToGen(*s.localSync.items[p]))
	}
	recent := make([]gen.LocalSyncItem, 0, len(s.localSync.recent))
	for _, it := range s.localSync.recent {
		recent = append(recent, lsItemToGen(it))
	}
	enabled := cfgRoot != "" && s.localSync.lastErr == ""
	st := gen.LocalSyncStatus{
		Enabled:         ptr(enabled),
		Root:            ptr(cfgRoot),
		IntervalSeconds: ptr(int(interval / time.Second)),
		Paused:          ptr(s.localSync.paused),
		SyncedTotal:     ptr(s.localSync.syncedTotal),
		LastError:       ptr(s.localSync.lastErr),
		Items:           &items,
		RecentSynced:    &recent,
		Counts: &struct {
			Failed        *int `json:"failed,omitempty"`
			Ignored       *int `json:"ignored,omitempty"`
			WaitingStable *int `json:"waitingStable,omitempty"`
		}{
			Failed:        ptr(failed),
			Ignored:       ptr(ignored),
			WaitingStable: ptr(waiting),
		},
	}
	if !s.localSync.lastCycleAt.IsZero() {
		st.LastCycleAt = ptr(s.localSync.lastCycleAt)
	}
	return st
}

// GetApiV1LocalSyncStatus 本机自动同步通道状态（只读）。
func (s *Server) GetApiV1LocalSyncStatus(w http.ResponseWriter, r *http.Request) {
	st := s.lsSnapshot(s.cfg.LocalSync.Root, s.localSyncInterval())
	writeJSON(w, http.StatusOK, st)
}

// PostApiV1LocalSyncTrigger 立即触发一轮同步扫描（异步）：向 runner 投递
// 非阻塞 kick（容量 1，进行中/已排队时丢弃——重复触发无意义），不等待
// 完成（202，与扫描触发端点同款语义）。通道未配置同步根、或通道级 lastErr
// 非空（根不存在/安全校验未通过——与 openapi 409 描述「未配置或安全校验
// 未通过」对齐）时 409，lastErr 原文透传。
func (s *Server) PostApiV1LocalSyncTrigger(w http.ResponseWriter, r *http.Request) {
	if s.cfg.LocalSync.Root == "" {
		writeErr(w, http.StatusConflict, codeLocalSyncDisabled,
			"本机同步通道未启用：请配置 QIMENG_LOCAL_SYNC_ROOT（或 yaml local_sync.root）后重启服务")
		return
	}
	if lastErr := s.lsLastErr(); lastErr != "" {
		writeErr(w, http.StatusConflict, codeLocalSyncDisabled, lastErr)
		return
	}
	select {
	case s.localSync.kick <- struct{}{}:
	default:
	}
	w.WriteHeader(http.StatusAccepted)
}
