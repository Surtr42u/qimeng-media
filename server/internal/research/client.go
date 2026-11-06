package research

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"time"
)

// 出口安全默认值（SECURITY：研究网关不提供任意 URL 抓取面）。
const (
	// defaultFetchTimeout 单次上游请求超时：研究是交互式核验，超过这个
	// 时长要么上游异常要么网络不通，继续等只会拖住请求。
	defaultFetchTimeout = 20 * time.Second
	// defaultMaxResponseBytes 单次响应字节上限（2MiB）：平台的 JSON/HTML
	// 结果远小于此值，超限视为异常（也防内存放大）。
	defaultMaxResponseBytes = 2 << 20
	// defaultUserAgent 研究请求的默认 UA：部分平台（如 e621）无 UA 直接
	// 403；显式标识用途便于平台运营识别自动化访问。
	defaultUserAgent = "QimengMedia-Research/1.0"
)

// blockedNetworks 与 creatorprofile 同款口径的保留/文档/私有段兜底
// （IsPrivate/IsLoopback 等已覆盖主要段，这里补保留段与 IPv4 映射前缀）。
var blockedNetworks = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"), netip.MustParsePrefix("240.0.0.0/4"), netip.MustParsePrefix("2001::/23"),
	netip.MustParsePrefix("100.64.0.0/10"), netip.MustParsePrefix("192.0.0.0/24"), netip.MustParsePrefix("192.0.2.0/24"),
	netip.MustParsePrefix("198.18.0.0/15"), netip.MustParsePrefix("198.51.100.0/24"), netip.MustParsePrefix("203.0.113.0/24"),
	netip.MustParsePrefix("2001:db8::/32"), netip.MustParsePrefix("64:ff9b::/96"), netip.MustParsePrefix("2002::/16"),
}

// publicIP 报告地址是否为可访问的公网单播地址（与 creatorprofile 同口径）。
func publicIP(ip netip.Addr) bool {
	ip = ip.Unmap()
	if !ip.IsGlobalUnicast() || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
		return false
	}
	for _, p := range blockedNetworks {
		if p.Contains(ip) {
			return false
		}
	}
	return true
}

// Request 是一次上游请求（只由本包 provider 构造，不接受外部任意 URL）。
type Request struct {
	// Method GET/POST。
	Method string
	// URL 完整地址；协议、主机与地址合法性在 Client.Fetch 内统一校验。
	URL string
	// Header 额外请求头（Referer/Accept 等），会覆盖默认值。
	Header map[string]string
	// Body 请求体（表单或 JSON）。
	Body []byte
	// ContentType 请求体类型（空 = 不设置）。
	ContentType string
}

// Response 是上游响应（非 2xx 也返回，由 provider 分类）。
type Response struct {
	StatusCode int
	Body       []byte
	Header     http.Header
}

// Fetcher 是 provider 依赖的最小抓取接口（测试注入 httptest 客户端或假实现）。
type Fetcher interface {
	Fetch(ctx context.Context, req Request) (Response, error)
}

// FetchErrorKind 是抓取失败类别（provider 统一映射 provider_unavailable，
// 但保留细分便于日志与测试断言）。
type FetchErrorKind int

// 抓取失败类别。
const (
	// FetchInvalidURL URL 不合法（非 https、带凭据、无主机）。
	FetchInvalidURL FetchErrorKind = iota
	// FetchHostNotAllowed 主机不在 provider 域名白名单内。
	FetchHostNotAllowed
	// FetchBlockedAddress 解析到私有/回环/保留地址（SSRF/DNS rebinding 防御）。
	FetchBlockedAddress
	// FetchTimeout 请求超时。
	FetchTimeout
	// FetchNetwork 连接/读写失败。
	FetchNetwork
	// FetchTooLarge 响应超过字节上限。
	FetchTooLarge
)

// FetchError 是安全客户端的分类错误；Message 不含响应正文与凭据。
type FetchError struct {
	Kind    FetchErrorKind
	Message string
}

func (e *FetchError) Error() string { return e.Message }

// Unwrap 让 errors.Is(err, context.DeadlineExceeded) 等判定可用。
func (e *FetchError) Unwrap() error {
	if e.Kind == FetchTimeout {
		return context.DeadlineExceeded
	}
	return nil
}

// ClientOptions 是安全客户端参数。
type ClientOptions struct {
	// AllowedHosts 是允许访问的主机名（小写、不含端口），来自 provider 基地址。
	AllowedHosts []string
	// Timeout 单次请求超时；<=0 用 defaultFetchTimeout。
	Timeout time.Duration
	// MaxBytes 响应字节上限；<=0 用 defaultMaxResponseBytes。
	MaxBytes int64
	// UserAgent 默认 UA；空用 defaultUserAgent。
	UserAgent string
	// AllowLoopback 仅测试用途：允许解析到回环地址并使用 http。
	// 生产装配恒 false；置 true 时仍只允许白名单主机。
	AllowLoopback bool
}

// Client 是研究网关的唯一出口：仅 https（测试允许回环 http）、主机白名单、
// 禁重定向与环境代理、拨号前解析并校验公网地址、超时与响应字节上限。
type Client struct {
	allowed  map[string]struct{}
	timeout  time.Duration
	maxBytes int64
	ua       string
	loopback bool
	http     *http.Client
}

// NewClient 构造安全客户端。
func NewClient(opts ClientOptions) *Client {
	timeout := opts.Timeout
	if timeout <= 0 {
		timeout = defaultFetchTimeout
	}
	maxBytes := opts.MaxBytes
	if maxBytes <= 0 {
		maxBytes = defaultMaxResponseBytes
	}
	ua := opts.UserAgent
	if ua == "" {
		ua = defaultUserAgent
	}
	allowed := make(map[string]struct{}, len(opts.AllowedHosts))
	for _, host := range opts.AllowedHosts {
		if h := strings.ToLower(strings.TrimSpace(host)); h != "" {
			allowed[h] = struct{}{}
		}
	}
	c := &Client{allowed: allowed, timeout: timeout, maxBytes: maxBytes, ua: ua, loopback: opts.AllowLoopback}
	transport := &http.Transport{
		// 禁环境代理：容器/桌面环境里的 HTTP_PROXY 会把研究流量（含
		// sidecar 之外的平台请求）转到不受控的中间人。
		Proxy: nil,
		// 禁连接复用：每次请求重新解析并校验地址，避免 DNS rebinding 后
		// 复用已建立的连接。
		DisableKeepAlives:     true,
		TLSHandshakeTimeout:   timeout,
		ResponseHeaderTimeout: timeout,
		DialContext:           c.dialContext,
	}
	c.http = &http.Client{
		Transport: transport,
		Timeout:   timeout,
		// 不跟随重定向：上游把请求转到内网/其他主机等于绕过白名单。
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return errors.New("research: 不跟随上游重定向")
		},
	}
	return c
}

// dialContext 解析全部地址并校验公网可达后才拨号（拨已验证 IP，
// 与 creatorprofile.fetch 同款 SSRF/DNS rebinding 防御）。
func (c *Client) dialContext(ctx context.Context, network, address string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(address)
	if err != nil {
		return nil, &FetchError{Kind: FetchNetwork, Message: "research: 上游地址不合法"}
	}
	ips, err := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
	if err != nil {
		return nil, &FetchError{Kind: FetchNetwork, Message: "research: 解析上游主机失败"}
	}
	if len(ips) == 0 {
		return nil, &FetchError{Kind: FetchBlockedAddress, Message: "research: 上游主机没有可用地址"}
	}
	for _, ip := range ips {
		if !c.loopback && !publicIP(ip) {
			return nil, &FetchError{Kind: FetchBlockedAddress, Message: "research: 上游主机解析到非公网地址"}
		}
	}
	var dialer net.Dialer
	return dialer.DialContext(ctx, network, net.JoinHostPort(ips[0].String(), port))
}

// Fetch 执行一次受控请求。返回的 error 只表示传输层/安全层失败；HTTP
// 非 2xx 仍返回 Response 由调用方分类。
func (c *Client) Fetch(ctx context.Context, req Request) (Response, error) {
	u, err := c.validateURL(req.URL)
	if err != nil {
		return Response{}, err
	}
	method := req.Method
	if method == "" {
		method = http.MethodGet
	}
	var body io.Reader
	if len(req.Body) > 0 {
		body = strings.NewReader(string(req.Body))
	}
	httpReq, err := http.NewRequestWithContext(ctx, method, u.String(), body)
	if err != nil {
		return Response{}, &FetchError{Kind: FetchInvalidURL, Message: "research: 构造上游请求失败"}
	}
	httpReq.Header.Set("User-Agent", c.ua)
	httpReq.Header.Set("Accept", "application/json, text/html;q=0.8")
	if req.ContentType != "" {
		httpReq.Header.Set("Content-Type", req.ContentType)
	}
	for k, v := range req.Header {
		httpReq.Header.Set(k, v)
	}
	resp, err := c.http.Do(httpReq)
	if err != nil {
		return Response{}, classifyTransportError(err)
	}
	defer func() {
		// 只读上限内的响应体；关闭失败不影响结果（连接立即丢弃）。
		_ = resp.Body.Close()
	}()
	data, readErr := io.ReadAll(io.LimitReader(resp.Body, c.maxBytes+1))
	if readErr != nil {
		return Response{}, classifyTransportError(readErr)
	}
	if int64(len(data)) > c.maxBytes {
		return Response{}, &FetchError{Kind: FetchTooLarge, Message: "research: 上游响应超过字节上限"}
	}
	return Response{StatusCode: resp.StatusCode, Body: data, Header: resp.Header}, nil
}

// validateURL 校验协议、主机白名单与 URL 形态。
func (c *Client) validateURL(raw string) (*url.URL, error) {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || u.Hostname() == "" || u.User != nil {
		return nil, &FetchError{Kind: FetchInvalidURL, Message: "research: 上游 URL 不合法"}
	}
	host := strings.ToLower(u.Hostname())
	if _, ok := c.allowed[host]; !ok {
		return nil, &FetchError{Kind: FetchHostNotAllowed, Message: "research: 上游主机不在白名单"}
	}
	switch u.Scheme {
	case "https":
		return u, nil
	case "http":
		// http 只为测试（httptest 假上游）开口：必须同时开启 loopback 且
		// 主机就是回环字面量；生产装配 AllowedHosts 全为 https 域名。
		if c.loopback && (host == "127.0.0.1" || host == "::1") {
			return u, nil
		}
		return nil, &FetchError{Kind: FetchInvalidURL, Message: "research: 上游只允许 https"}
	default:
		return nil, &FetchError{Kind: FetchInvalidURL, Message: "research: 上游协议不支持"}
	}
}

// classifyTransportError 把 net/http 错误收敛成 FetchError。
func classifyTransportError(err error) error {
	var fetcherr *FetchError
	if errors.As(err, &fetcherr) {
		return fetcherr
	}
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
		return &FetchError{Kind: FetchTimeout, Message: "research: 上游请求超时"}
	}
	var nerr net.Error
	if errors.As(err, &nerr) && nerr.Timeout() {
		return &FetchError{Kind: FetchTimeout, Message: "research: 上游请求超时"}
	}
	return &FetchError{Kind: FetchNetwork, Message: "research: 上游网络请求失败"}
}

// hostOf 返回 URL 的主机名（小写、不含端口与用户信息）；解析失败返回空串。
// 证据与响应 domain 字段共用，避免两处各写一份解析。
func hostOf(raw string) string {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return ""
	}
	return strings.ToLower(u.Hostname())
}

// classifyStatus 把上游 HTTP 状态映射为结果状态（provider 共用）。
func classifyStatus(code int) (Status, *ResultError) {
	switch {
	case code >= 200 && code < 300:
		return StatusOk, nil
	case code == http.StatusNotFound || code == http.StatusGone:
		return StatusNotFound, &ResultError{Code: "NOT_FOUND", Message: "上游没有该对象"}
	case code == http.StatusUnauthorized || code == http.StatusForbidden || code == http.StatusUnavailableForLegalReasons:
		return StatusBlocked, &ResultError{Code: "BLOCKED", Message: "上游拒绝访问（可能需要登录或被平台风控）"}
	case code == http.StatusTooManyRequests:
		return StatusRateLimited, &ResultError{Code: "RATE_LIMITED", Message: "上游限流"}
	default:
		return StatusProviderUnavailable, &ResultError{Code: "UPSTREAM_ERROR", Message: fmt.Sprintf("上游返回 HTTP %d", code)}
	}
}

// unavailable 由抓取错误构造 provider_unavailable 结果（消息不暴露内网细节）。
func unavailable(err error) ProviderResult {
	var fetcherr *FetchError
	code := "PROVIDER_UNAVAILABLE"
	message := "上游不可用"
	if errors.As(err, &fetcherr) {
		switch fetcherr.Kind {
		case FetchTimeout:
			code = "UPSTREAM_TIMEOUT"
			message = "上游请求超时"
		case FetchTooLarge:
			code = "UPSTREAM_RESPONSE_TOO_LARGE"
			message = "上游响应超过上限"
		case FetchHostNotAllowed:
			code = "HOST_NOT_ALLOWED"
			message = "上游主机不在白名单"
		case FetchBlockedAddress:
			code = "ADDRESS_BLOCKED"
			message = "上游地址不被允许"
		case FetchInvalidURL:
			code = "INVALID_UPSTREAM_URL"
			message = "上游 URL 不合法"
		}
	}
	return ProviderResult{Status: StatusProviderUnavailable, Err: &ResultError{Code: code, Message: message}}
}

// invalidResponse 是"响应拿到了但结构不符合预期"的统一结果。
func invalidResponse(what string) ProviderResult {
	return ProviderResult{Status: StatusProviderUnavailable, Err: &ResultError{Code: "INVALID_RESPONSE", Message: what + "响应结构不符合预期"}}
}

// fetchUpstream 执行请求并把传输/HTTP 状态收敛为 (Response, 终止结果)。
// 第二个返回值非 nil 表示本次查询已有终态（调用方直接返回）。
func fetchUpstream(ctx context.Context, f Fetcher, req Request) (Response, *ProviderResult) {
	resp, err := f.Fetch(ctx, req)
	if err != nil {
		res := unavailable(err)
		return Response{}, &res
	}
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return resp, nil
	}
	status, rerr := classifyStatus(resp.StatusCode)
	res := ProviderResult{Status: status, Err: rerr}
	return resp, &res
}
