package research

import (
	"context"
	"errors"
	"time"
)

// Name 是 provider 标识（与 api/openapi.yaml 的 ResearchProviderName 同步）。
type Name string

// provider 名（v1 固定矩阵）。
const (
	NameFxTwitter Name = "fxtwitter"
	NamePixiv     Name = "pixiv"
	NameYandere   Name = "yandere"
	NameE621      Name = "e621"
	NameWayback   Name = "wayback"
	NameSteam     Name = "steam"
	NameBing      Name = "bing"
)

// Operation 是研究操作语义（与协议侧 ResearchOperation 同步）。
type Operation string

// 研究操作（每个 provider 只支持矩阵内的组合）。
const (
	OperationAuthor   Operation = "author"
	OperationPost     Operation = "post"
	OperationWork     Operation = "work"
	OperationArtist   Operation = "artist"
	OperationTags     Operation = "tags"
	OperationURL      Operation = "url"
	OperationWorkshop Operation = "workshop"
	OperationSearch   Operation = "search"
)

// Status 是 provider 查询结果状态（与协议侧 ResearchProviderResult.status
// 同步）。HTTP 层恒 200，除非是协议/鉴权/开关错误。
type Status string

// provider 查询状态。
const (
	// StatusOk 平台返回了可用字段（至少一条 item）。
	StatusOk Status = "ok"
	// StatusNotFound 平台明确不存在或空结果。
	StatusNotFound Status = "not_found"
	// StatusBlocked 平台拒绝（401/403/451，或要求登录/验证）。
	StatusBlocked Status = "blocked"
	// StatusRateLimited 本地每分钟预算耗尽，或平台返回 429。
	StatusRateLimited Status = "rate_limited"
	// StatusProviderUnavailable 上游超时/5xx/响应不可解析/网络失败。
	StatusProviderUnavailable Status = "provider_unavailable"
)

// LensStatus 是 Lens 结果状态（与协议侧 ResearchLensResult.status 同步）。
type LensStatus string

// Lens 结果状态。
const (
	// LensStatusOk sidecar 完成了一次识别（不等于认出作者）。
	LensStatusOk LensStatus = "ok"
	// LensStatusBlocked sidecar 被上游/验证码挡住。
	LensStatusBlocked LensStatus = "blocked"
	// LensStatusTimeout sidecar 帧处理超时（sidecar 侧报告）。
	LensStatusTimeout LensStatus = "timeout"
	// LensStatusUploadRejected sidecar 拒绝了该图片。
	LensStatusUploadRejected LensStatus = "upload_rejected"
	// LensStatusSidecarUnavailable sidecar 不可达（HTTP 层另返回 503）。
	LensStatusSidecarUnavailable LensStatus = "sidecar_unavailable"
)

// MaxTargetRunes 是 target 的字符上限（协议 target.maxLength=500）。
const MaxTargetRunes = 500

// DefaultLimit 是 options.limit 缺省值。
const DefaultLimit = 10

// MaxLimit 是 options.limit 上限（协议 1..50）。
const MaxLimit = 50

// MaxItems 是响应 items 的硬上限（协议 maxItems=20）。
const MaxItems = 20

// Item 是单条 provider 结果（公开字段，缺失即省略）。
type Item struct {
	Title       string
	URL         string
	Domain      string
	Author      string
	ID          string
	PublishedAt string
	Extra       map[string]string
}

// ResultError 是结果内的错误描述（协议 error{code,message}）。
type ResultError struct {
	Code    string
	Message string
}

// ProviderResult 是一次 provider 查询的领域结果。
type ProviderResult struct {
	Status    Status
	Items     []Item
	Err       *ResultError
	FetchedAt time.Time
	LatencyMs int64
}

// Query 是一次 provider 查询入参。
type Query struct {
	Operation Operation
	Target    string
	Limit     int
}

// NormalizeLimit 把期望条数收敛到 1..MaxLimit（<=0 用默认值）。
func NormalizeLimit(limit int) int {
	if limit <= 0 {
		return DefaultLimit
	}
	if limit > MaxLimit {
		return MaxLimit
	}
	return limit
}

// itemLimit 是本次实际取条数：不超过 MaxItems。
func (q Query) itemLimit() int {
	n := NormalizeLimit(q.Limit)
	if n > MaxItems {
		return MaxItems
	}
	return n
}

// Provider 是 provider 网关的最小接口：httpapi 只经 Service 访问，测试可
// 注入假 Fetcher/基地址构造真实 provider 实例。
type Provider interface {
	// Name 返回 provider 标识。
	Name() Name
	// Operations 返回该 provider 支持的 operation（固定矩阵）。
	Operations() []Operation
	// Query 执行一次查询。target 非法返回 ErrInvalidTarget；上游失败以
	// ProviderResult.Status 表达，不返回 error（便于统一 200 语义）。
	Query(ctx context.Context, q Query) (ProviderResult, error)
}

// 服务层哨兵错误（httpapi 据此映射 400/429/502/503/504）。
var (
	// ErrDisabled 研究整体关闭（research.enabled=false）。
	ErrDisabled = errors.New("research: 研究流水线未启用")
	// ErrUnknownProvider 未知 provider。
	ErrUnknownProvider = errors.New("research: 未知 provider")
	// ErrUnsupportedOperation provider 不支持该 operation。
	ErrUnsupportedOperation = errors.New("research: provider 不支持该 operation")
	// ErrInvalidTarget target 为空/超长/格式不合法。
	ErrInvalidTarget = errors.New("research: 查询目标不合法")
	// ErrLensRateLimited Lens 每小时/每日预算耗尽。
	ErrLensRateLimited = errors.New("research: Lens 预算耗尽")
	// ErrSidecarDisabled sidecar 未配置或研究关闭（HTTP 503）。
	ErrSidecarDisabled = errors.New("research: Lens sidecar 未启用")
	// ErrSidecarUnavailable sidecar 不可达（HTTP 503）。
	ErrSidecarUnavailable = errors.New("research: Lens sidecar 不可达")
	// ErrSidecarTimeout sidecar 超时（HTTP 504）。
	ErrSidecarTimeout = errors.New("research: Lens sidecar 超时")
	// ErrSidecarBusy sidecar 忙（429 BUSY，单实例串行已有请求在飞；HTTP 429）。
	ErrSidecarBusy = errors.New("research: Lens sidecar 忙")
	// ErrSidecarResponse sidecar 返回异常响应（HTTP 502）。
	ErrSidecarResponse = errors.New("research: Lens sidecar 响应异常")
)

// ProviderLimits 是 provider 的进程内预算（协议 ResearchProviderLimits）。
type ProviderLimits struct {
	PerMinute int
	PerDay    int
}

// ProviderInfo 是 context 里的 provider 描述（协议 ResearchProviderInfo）。
type ProviderInfo struct {
	Name       Name
	Operations []Operation
	Enabled    bool
	Limits     ProviderLimits
}

// SidecarState 是 context 里的 sidecar 状态（协议 ResearchSidecarStatus）。
type SidecarState struct {
	Configured bool
	Available  bool
	Pending    int
}

// Context 是 GET /research/context 的领域结果（协议 ResearchContext）。
type Context struct {
	Enabled    bool
	Sidecar    SidecarState
	Providers  []ProviderInfo
	LensLimits LensLimits
	FetchedAt  time.Time
}

// LensLimits 是 Lens 预算（协议 ResearchLimits）。
type LensLimits struct {
	PerHour int
	PerDay  int
}

// LensTitle 是 sidecar 正文启发式标题（协议 ResearchLensTitle）。
type LensTitle struct {
	Title  string `json:"title"`
	Domain string `json:"domain"`
}

// LensCite 是 sidecar 正文启发式引用（协议 ResearchLensCite）。
type LensCite struct {
	Title  string `json:"title,omitempty"`
	URL    string `json:"url"`
	Domain string `json:"domain"`
}

// LensLink 是 sidecar 结果卡片外链（协议 ResearchLensLink；可为空）。
type LensLink struct {
	URL    string `json:"url"`
	Domain string `json:"domain"`
	Title  string `json:"title,omitempty"`
}

// LensResult 是一次 Lens 反查的领域结果（协议 ResearchLensResult）。
type LensResult struct {
	Status      LensStatus
	FrameSHA256 string
	TextSHA256  string
	FetchedAt   *time.Time
	ElapsedMs   int64
	TopTitles   []LensTitle
	TopDomains  []string
	Cites       []LensCite
	Links       []LensLink
	ResultURL   string
	Err         *ResultError
}
