package research

import (
	"context"
	"errors"
	"log/slog"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"qimeng-media/server/internal/config"
)

// sidecarHealthTTL 是 context 里 sidecar 可用性探测结果的缓存时长：
// /research/context 是 UI 轮询端点，每次真探测会把本地 sidecar 打成探活
// 热点；5s 足够覆盖"刚起来/刚挂掉"的感知需求。
const sidecarHealthTTL = 5 * time.Second

// Options 是研究服务装配参数（DI 只在 main 或测试装配处发生）。
type Options struct {
	// Config 是 research 配置段（main 传 cfg.Research；测试可直接构造）。
	Config config.ResearchConfig
	// DataDir 是服务端私有数据根（证据目录默认落在这里）。
	DataDir string
	// Logger 结构化日志；nil 用 slog.Default。
	Logger *slog.Logger
	// Now 时钟注入（测试用固定时钟验证预算窗口；nil 用 time.Now）。
	Now func() time.Time
	// Endpoints 覆盖 provider 基地址（仅测试注入 httptest 假上游；生产留空）。
	Endpoints map[Name]string
	// AllowLoopbackEndpoints 允许 endpoint 解析到回环地址（仅测试；生产必须 false）。
	AllowLoopbackEndpoints bool
	// Sidecar 注入 sidecar 客户端（测试注入假 sidecar；nil 时按配置构造）。
	Sidecar *SidecarClient
}

// Service 是研究流水线的门面：provider 网关 + Lens 代理 + 预算 + 证据。
// 并发安全：预算、证据、健康缓存、在途计数各自加锁/原子。
type Service struct {
	enabled  bool
	registry *registry
	limits   *limits
	sidecar  *SidecarClient
	evidence *EvidenceStore
	now      func() time.Time
	logger   *slog.Logger

	// lensSlots 是 sidecar 的并发闸（sidecar 单实例串行、limit=1）：
	// 同一服务端的并发 Lens 请求在进程内排队，避免自己打自己换来 429 BUSY。
	lensSlots chan struct{}

	pending atomic.Int64

	healthMu sync.Mutex
	healthAt time.Time
	healthOK bool

	providerPerMinute int
	lensLimits        LensLimits
}

// New 装配研究服务。配置不合法（sidecar 端点/凭据）时返回 error，由 main
// 在启动期拒绝启动；研究关闭时仍返回可用实例（context 可读，查询显式禁用）。
func New(opts Options) (*Service, error) {
	now := opts.Now
	if now == nil {
		now = time.Now
	}
	logger := opts.Logger
	if logger == nil {
		logger = slog.Default()
	}
	cfg := opts.Config
	// 预算兜底：config.Load 已保证正值；直接构造 Options 的测试/嵌入调用
	// 可能留零值——零预算会被 budget 解释成"不限制"，这里恢复默认值，
	// 让 context 回报真实限额。
	if cfg.LensPerHour <= 0 {
		cfg.LensPerHour = config.DefaultResearchLensPerHour
	}
	if cfg.LensPerDay <= 0 {
		cfg.LensPerDay = config.DefaultResearchLensPerDay
	}
	if cfg.ProviderPerMinute <= 0 {
		cfg.ProviderPerMinute = config.DefaultResearchProviderPerMinute
	}
	s := &Service{
		enabled:           cfg.Enabled,
		now:               now,
		logger:            logger,
		lensSlots:         make(chan struct{}, 1),
		providerPerMinute: cfg.ProviderPerMinute,
		lensLimits:        LensLimits{PerHour: cfg.LensPerHour, PerDay: cfg.LensPerDay},
	}
	switch {
	case opts.Sidecar != nil:
		s.sidecar = opts.Sidecar
	case strings.TrimSpace(cfg.SidecarEndpoint) != "":
		client, err := NewSidecarClient(cfg.SidecarEndpoint, cfg.SidecarToken)
		if err != nil {
			return nil, err
		}
		s.sidecar = client
	}
	dir := strings.TrimSpace(cfg.EvidenceDir)
	if dir == "" && strings.TrimSpace(opts.DataDir) != "" {
		dir = filepath.Join(opts.DataDir, evidenceDirName)
	}
	s.evidence = NewEvidenceStore(dir, now)
	client := NewClient(ClientOptions{
		AllowedHosts:  allowedHosts(opts.Endpoints),
		AllowLoopback: opts.AllowLoopbackEndpoints,
	})
	s.registry = newRegistry(client, opts.Endpoints)
	s.limits = newLimits(now, cfg.ProviderPerMinute, cfg.LensPerHour, cfg.LensPerDay)
	return s, nil
}

// Enabled 报告研究流水线是否启用。
func (s *Service) Enabled() bool { return s != nil && s.enabled }

// SidecarConfigured 报告是否配置了 Lens sidecar。
func (s *Service) SidecarConfigured() bool { return s != nil && s.sidecar != nil }

// Context 返回研究能力上下文（enabled=false 时仍可用）。
func (s *Service) Context(ctx context.Context) Context {
	info := Context{
		Enabled:    s.enabled,
		LensLimits: s.lensLimits,
		FetchedAt:  s.now(),
	}
	for _, name := range s.registry.order {
		provider, ok := s.registry.lookup(name)
		if !ok {
			continue
		}
		info.Providers = append(info.Providers, ProviderInfo{
			Name:       name,
			Operations: provider.Operations(),
			Enabled:    s.enabled,
			Limits:     ProviderLimits{PerMinute: s.providerPerMinute, PerDay: 0},
		})
	}
	info.Sidecar = SidecarState{
		Configured: s.sidecar != nil,
		Available:  s.sidecarAvailable(ctx),
	}
	if s.sidecar != nil {
		info.Sidecar.Pending = int(s.pending.Load())
	}
	return info
}

// sidecarAvailable 带短缓存地探测 sidecar（研究关闭时恒 false，不打探活）。
func (s *Service) sidecarAvailable(ctx context.Context) bool {
	if s.sidecar == nil || !s.enabled {
		return false
	}
	s.healthMu.Lock()
	if !s.healthAt.IsZero() && s.now().Sub(s.healthAt) < sidecarHealthTTL {
		ok := s.healthOK
		s.healthMu.Unlock()
		return ok
	}
	s.healthMu.Unlock()
	ok := s.sidecar.Health(ctx)
	s.healthMu.Lock()
	s.healthAt = s.now()
	s.healthOK = ok
	s.healthMu.Unlock()
	return ok
}

// Query 执行一次 provider 查询。返回 error 只表示协议/开关类失败（未知
// provider、不支持的 operation、非法 target、研究关闭）；上游失败以
// ProviderResult.Status 表达，仍是 200 语义。
func (s *Service) Query(ctx context.Context, name Name, q Query) (ProviderResult, error) {
	if !s.enabled {
		return ProviderResult{}, ErrDisabled
	}
	provider, ok := s.registry.lookup(name)
	if !ok {
		return ProviderResult{}, ErrUnknownProvider
	}
	if !supports(provider, q.Operation) {
		return ProviderResult{}, ErrUnsupportedOperation
	}
	target := strings.TrimSpace(q.Target)
	if err := validateTarget(target); err != nil {
		return ProviderResult{}, err
	}
	q.Target = target
	q.Limit = NormalizeLimit(q.Limit)
	if !s.limits.takeProvider(name) {
		// 预算命中不访问上游，但仍要回报 fetchedAt（协议必填）与限流原因。
		return ProviderResult{
			Status:    StatusRateLimited,
			FetchedAt: s.now(),
			Err:       &ResultError{Code: "RATE_LIMITED", Message: "该 provider 每分钟预算已用尽，请稍后重试"},
		}, nil
	}
	started := s.now()
	result, err := provider.Query(ctx, q)
	fetchedAt := s.now()
	result.FetchedAt = fetchedAt
	if elapsed := fetchedAt.Sub(started).Milliseconds(); elapsed > 0 {
		result.LatencyMs = elapsed
	}
	if err != nil {
		if errors.Is(err, ErrInvalidTarget) {
			return ProviderResult{}, ErrInvalidTarget
		}
		s.logger.Warn("研究 provider 查询失败", "provider", name, "operation", q.Operation, "error", err)
		result = ProviderResult{
			Status:    StatusProviderUnavailable,
			Err:       &ResultError{Code: "INTERNAL", Message: "研究查询失败"},
			FetchedAt: fetchedAt,
		}
	}
	s.recordProvider(name, q, result)
	return result, nil
}

// Lens 把图片转发给本机 sidecar。error 使用公开哨兵（httpapi 映射 503/504/502）；
// 预算耗尽返回 ErrLensRateLimited（429）。
func (s *Service) Lens(ctx context.Context, image []byte, mediaType string) (LensResult, error) {
	if !s.enabled || s.sidecar == nil {
		return LensResult{}, ErrSidecarDisabled
	}
	if !s.limits.takeLens() {
		return LensResult{}, ErrLensRateLimited
	}
	// sidecar 单实例串行：进程内先排队，排队期间取消按超时处理。
	select {
	case s.lensSlots <- struct{}{}:
		defer func() { <-s.lensSlots }()
	case <-ctx.Done():
		return LensResult{}, ErrSidecarTimeout
	}
	s.pending.Add(1)
	defer s.pending.Add(-1)
	result, err := s.sidecar.Lens(ctx, image, mediaType)
	if err != nil {
		// 传输类失败也已消耗一次预算：预算守的是 sidecar 负载，失败请求
		// 同样占用了串行槽位；调用方按 503/504 退避重试即可。
		return LensResult{}, err
	}
	s.recordLens(result)
	return result, nil
}

// recordProvider 尽力落盘证据；失败只告警（研究证据不是业务数据）。
func (s *Service) recordProvider(name Name, q Query, res ProviderResult) {
	if err := s.evidence.SaveProvider(name, q, res); err != nil {
		s.logger.Warn("研究证据落盘失败", "provider", name, "error", err)
	}
}

// recordLens 同上。
func (s *Service) recordLens(res LensResult) {
	if err := s.evidence.SaveLens(res); err != nil {
		s.logger.Warn("Lens 证据落盘失败", "error", err)
	}
}

// validateTarget 校验通用 target 约束（非空、<=500 字符、无控制字符）。
func validateTarget(target string) error {
	if target == "" || len([]rune(target)) > MaxTargetRunes {
		return ErrInvalidTarget
	}
	for _, r := range target {
		if r == '\n' || r == '\r' || r == '\t' {
			continue
		}
		if r < 0x20 || r == 0x7f {
			return ErrInvalidTarget
		}
	}
	return nil
}
