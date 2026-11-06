package research

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"mime/multipart"
	"net/http"
	"strings"
	"time"

	"qimeng-media/server/internal/config"
)

// sidecar 相关默认值（sidecar 单请求总预算 90s，单帧实测 13–34s）。
const (
	// defaultLensTimeout 是网关等待 sidecar 完成一帧的上限：必须大于
	// sidecar 自身的 90s 总预算（tools/research_sidecar 文档建议 100–120s），
	// 否则网关先超时会把 sidecar 的正常慢帧误判成故障。
	defaultLensTimeout = 120 * time.Second
	// sidecarHealthTimeout 是 /healthz 探测超时（探针必须快速失败）。
	sidecarHealthTimeout = 3 * time.Second
	// maxSidecarResponseBytes 是 sidecar 响应字节上限（JSON 摘要很小）。
	maxSidecarResponseBytes = 1 << 20
	// maxSidecarHealthBytes 是 /healthz 响应读取上限（状态 JSON 很小）。
	maxSidecarHealthBytes = 64 << 10
	// sidecarErrorMaxRunes 是回传协议前截断的 sidecar 错误消息长度。
	sidecarErrorMaxRunes = 300
)

// SidecarClient 是本机 Lens sidecar 的 HTTP 客户端：loopback 校验、Bearer
// token、GET /healthz 与 POST /lens（multipart/form-data，字段 file）。
//
// sidecar 不是稳定证据源：视觉匹配卡片没有外部 href，links 经常为空，
// topTitles/topDomains/cites 来自正文启发式；本客户端只负责传输、字段名
// 归一（兼容 snake_case/camelCase）与状态映射，不把识别结果包装成"已核验
// 作者"。
type SidecarClient struct {
	// base 是规范化后的基地址（http://127.0.0.1:<port> 或 http://[::1]:<port>）。
	base    string
	token   string
	timeout time.Duration
	http    *http.Client
}

// NewSidecarClient 校验端点并构造客户端。token 为空直接报错（sidecar 恒定
// 要求 Bearer；静默匿名请求只会换来 401）。
func NewSidecarClient(endpoint, token string) (*SidecarClient, error) {
	base, err := config.ParseResearchSidecarEndpoint(endpoint)
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(token) == "" {
		return nil, errors.New("research: sidecar token 不能为空")
	}
	transport := &http.Transport{
		// loopback 字面量无需 DNS，也不需要环境代理；禁复用避免残留连接。
		Proxy:             nil,
		DisableKeepAlives: true,
	}
	return &SidecarClient{
		base:    base,
		token:   token,
		timeout: defaultLensTimeout,
		http: &http.Client{
			Transport: transport,
			Timeout:   defaultLensTimeout,
			CheckRedirect: func(*http.Request, []*http.Request) error {
				return errors.New("research: 不跟随 sidecar 重定向")
			},
		},
	}, nil
}

// Configured 报告客户端是否可用（nil 安全）。
func (c *SidecarClient) Configured() bool { return c != nil }

// Health 探测 sidecar 是否可达：/healthz 无鉴权（sidecar 契约），2xx 且
// ready 不为 false 视为可用；任何非 2xx 或网络失败都视为不可用。
func (c *SidecarClient) Health(ctx context.Context) bool {
	if c == nil {
		return false
	}
	ctx, cancel := context.WithTimeout(ctx, sidecarHealthTimeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.base+"/healthz", nil)
	if err != nil {
		return false
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	resp, err := c.http.Do(req)
	if err != nil {
		return false
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return false
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxSidecarHealthBytes))
	if err != nil {
		return false
	}
	// sidecar 的 /healthz 区分"HTTP 在跑"与"浏览器就绪"：有 ready 字段时
	// 以它为准，没字段（其他实现）则 2xx 即可用。
	var payload struct {
		OK    *bool `json:"ok"`
		Ready *bool `json:"ready"`
	}
	if err := json.Unmarshal(data, &payload); err == nil {
		if payload.Ready != nil {
			return *payload.Ready
		}
		if payload.OK != nil {
			return *payload.OK
		}
	}
	return true
}

// sidecarLensResponse 是 sidecar /lens 的响应面：字段同时接受 snake_case
// （tools/research_sidecar 现行契约，如 frame_sha256/elapsed_ms/result_url）
// 与 camelCase（历史/兼容形态），取值时非空优先。
type sidecarLensResponse struct {
	OK      *bool  `json:"ok"`
	Status  string `json:"status"`
	Message string `json:"message"`

	FrameSha256Snake string          `json:"frame_sha256"`
	FrameSha256Camel string          `json:"frameSha256"`
	TextSha256Snake  string          `json:"text_sha256"`
	TextSha256Camel  string          `json:"textSha256"`
	FetchedAtSnake   json.RawMessage `json:"fetched_at"`
	FetchedAtCamel   json.RawMessage `json:"fetchedAt"`
	ElapsedMsSnake   int64           `json:"elapsed_ms"`
	ElapsedMsCamel   *int64          `json:"elapsedMs"`
	ResultURLSnake   string          `json:"result_url"`
	ResultURLCamel   string          `json:"resultUrl"`

	TopTitles []struct {
		Title  string `json:"title"`
		Domain string `json:"domain"`
	} `json:"topTitles"`
	TopDomains []string `json:"topDomains"`
	Cites      []struct {
		Title  string `json:"title"`
		URL    string `json:"url"`
		Domain string `json:"domain"`
	} `json:"cites"`
	Links []struct {
		URL    string `json:"url"`
		Domain string `json:"domain"`
		Title  string `json:"title"`
	} `json:"links"`
	Error *struct {
		Code    string `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

// firstNonEmpty 返回第一个非空字符串（snake/camel 字段二选一）。
func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if trimmed := strings.TrimSpace(value); trimmed != "" {
			return trimmed
		}
	}
	return ""
}

// Lens 把一张图片转发给 sidecar 并归一结果。返回 error 时使用公开哨兵
// （ErrSidecarTimeout/ErrSidecarUnavailable/ErrSidecarBusy/ErrSidecarResponse），
// 由 httpapi 映射 504/503/429/502；sidecar 的业务状态（blocked/timeout/
// upload_rejected）按协议 200 返回，由 LensResult.Status 表达。
func (c *SidecarClient) Lens(ctx context.Context, image []byte, mediaType string) (LensResult, error) {
	if c == nil {
		return LensResult{}, ErrSidecarDisabled
	}
	frameSum := sha256.Sum256(image)
	result := LensResult{FrameSHA256: hex.EncodeToString(frameSum[:])}
	body, contentType, err := buildLensMultipart(image, mediaType)
	if err != nil {
		return result, ErrSidecarResponse
	}
	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.base+"/lens", bytes.NewReader(body))
	if err != nil {
		return result, ErrSidecarResponse
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	req.Header.Set("Content-Type", contentType)
	req.Header.Set("Accept", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return result, classifySidecarTransport(ctx, err)
	}
	defer func() { _ = resp.Body.Close() }()
	data, readErr := io.ReadAll(io.LimitReader(resp.Body, maxSidecarResponseBytes+1))
	if readErr != nil {
		return result, classifySidecarTransport(ctx, readErr)
	}
	var payload sidecarLensResponse
	parsed := len(data) > 0 && json.Unmarshal(data, &payload) == nil
	switch resp.StatusCode {
	case http.StatusOK, http.StatusCreated:
		if !parsed {
			return result, ErrSidecarResponse
		}
		return c.fillLensResult(result, payload)
	case http.StatusBadRequest, http.StatusUnprocessableEntity:
		// INPUT_INVALID / UPLOAD_REJECTED：sidecar 拒绝该图片（业务状态，200）。
		result.Status = LensStatusUploadRejected
		result.Err = sidecarResultError(payload, parsed, "UPLOAD_REJECTED", "sidecar 拒绝该图片")
		return result, nil
	case http.StatusTooManyRequests:
		// BUSY：sidecar 单实例串行且已有请求在飞；调用方按 Retry-After 退避。
		return result, ErrSidecarBusy
	case http.StatusServiceUnavailable:
		if parsed && lensStatusOf(payload) == LensStatusBlocked {
			result.Status = LensStatusBlocked
			result.Err = sidecarResultError(payload, true, "BLOCKED_BOT", "sidecar 被上游或验证码挡住")
			return result, nil
		}
		return result, ErrSidecarUnavailable
	case http.StatusGatewayTimeout:
		if parsed && lensStatusOf(payload) == LensStatusTimeout {
			result.Status = LensStatusTimeout
			result.Err = sidecarResultError(payload, true, "TIMEOUT", "sidecar 单帧处理超时")
			return result, nil
		}
		return result, ErrSidecarTimeout
	default:
		// 401/403（token 不匹配）、502（NO_RESULT）、5xx 与未知状态一律按
		// sidecar 异常响应处理，不向调用方回显 sidecar 响应正文（可能含
		// 本机路径）。
		return result, ErrSidecarResponse
	}
}

// fillLensResult 把 2xx 响应归一为领域结果；ok=false 时按 sidecar 错误码分支。
func (c *SidecarClient) fillLensResult(result LensResult, payload sidecarLensResponse) (LensResult, error) {
	if payload.OK != nil && !*payload.OK {
		switch lensStatusOf(payload) {
		case LensStatusBlocked:
			result.Status = LensStatusBlocked
			result.Err = sidecarResultError(payload, true, "BLOCKED_BOT", "sidecar 被上游或验证码挡住")
			return result, nil
		case LensStatusTimeout:
			result.Status = LensStatusTimeout
			result.Err = sidecarResultError(payload, true, "TIMEOUT", "sidecar 单帧处理超时")
			return result, nil
		case LensStatusUploadRejected:
			result.Status = LensStatusUploadRejected
			result.Err = sidecarResultError(payload, true, "UPLOAD_REJECTED", "sidecar 拒绝该图片")
			return result, nil
		default:
			if code := sidecarErrorCode(payload); strings.EqualFold(code, "BUSY") {
				return result, ErrSidecarBusy
			}
			return result, ErrSidecarResponse
		}
	}
	status := lensStatusOf(payload)
	switch status {
	case LensStatusOk, LensStatusBlocked, LensStatusTimeout, LensStatusUploadRejected, LensStatusSidecarUnavailable:
	default:
		// 契约违约：未知状态不能当成 ok。
		return result, ErrSidecarResponse
	}
	result.Status = status
	result.TextSHA256 = firstNonEmpty(payload.TextSha256Snake, payload.TextSha256Camel)
	if payload.ElapsedMsCamel != nil && *payload.ElapsedMsCamel > 0 {
		result.ElapsedMs = *payload.ElapsedMsCamel
	} else {
		result.ElapsedMs = payload.ElapsedMsSnake
	}
	result.FetchedAt = parseSidecarTime(firstRaw(payload.FetchedAtSnake, payload.FetchedAtCamel))
	result.ResultURL = firstNonEmpty(payload.ResultURLSnake, payload.ResultURLCamel)
	for _, title := range payload.TopTitles {
		result.TopTitles = append(result.TopTitles, LensTitle{
			Title: strings.TrimSpace(title.Title), Domain: strings.TrimSpace(title.Domain),
		})
	}
	for _, domain := range payload.TopDomains {
		if trimmed := strings.TrimSpace(domain); trimmed != "" {
			result.TopDomains = append(result.TopDomains, trimmed)
		}
	}
	for _, cite := range payload.Cites {
		result.Cites = append(result.Cites, LensCite{
			Title: strings.TrimSpace(cite.Title), URL: strings.TrimSpace(cite.URL), Domain: strings.TrimSpace(cite.Domain),
		})
	}
	// links 是 best-effort：只透传指向公网 http(s) 的条目，空数组保持空。
	for _, link := range payload.Links {
		raw := strings.TrimSpace(link.URL)
		if raw == "" || !isHTTPURL(raw) {
			continue
		}
		domain := strings.TrimSpace(link.Domain)
		if domain == "" {
			domain = hostOf(raw)
		}
		result.Links = append(result.Links, LensLink{URL: raw, Domain: domain, Title: strings.TrimSpace(link.Title)})
	}
	if payload.Error != nil {
		result.Err = &ResultError{
			Code:    strings.TrimSpace(payload.Error.Code),
			Message: truncateRunes(strings.TrimSpace(payload.Error.Message), sidecarErrorMaxRunes),
		}
		if result.Err.Code == "" {
			result.Err.Code = "SIDECAR_ERROR"
		}
	}
	if result.Err == nil && status != LensStatusOk {
		result.Err = &ResultError{Code: strings.ToUpper(string(status)), Message: lensStatusMessage(status)}
	}
	return result, nil
}

// lensStatusOf 把 sidecar 的 status 字符串归一为协议枚举；未知值返回空串
// （由调用方按契约违约处理）。兼容 ok/blocked_bot/timeout/error 与历史
// ok/blocked/timeout/upload_rejected/sidecar_unavailable 形态。
func lensStatusOf(payload sidecarLensResponse) LensStatus {
	switch strings.ToLower(strings.TrimSpace(payload.Status)) {
	case "":
		if payload.OK != nil && !*payload.OK {
			return lensStatusFromErrorCode(sidecarErrorCode(payload))
		}
		return LensStatusOk
	case "ok", "success":
		return LensStatusOk
	case "blocked", "blocked_bot":
		return LensStatusBlocked
	case "timeout":
		return LensStatusTimeout
	case "upload_rejected", "input_invalid":
		return LensStatusUploadRejected
	case "sidecar_unavailable":
		return LensStatusSidecarUnavailable
	case "error":
		return lensStatusFromErrorCode(sidecarErrorCode(payload))
	default:
		return LensStatus("")
	}
}

// lensStatusFromErrorCode 把 sidecar 错误码映射到业务状态（空串表示需要
// 由 HTTP 状态/哨兵错误表达）。
func lensStatusFromErrorCode(code string) LensStatus {
	switch strings.ToLower(strings.TrimSpace(code)) {
	case "blocked_bot":
		return LensStatusBlocked
	case "timeout":
		return LensStatusTimeout
	case "upload_rejected", "input_invalid":
		return LensStatusUploadRejected
	default:
		return LensStatus("")
	}
}

// sidecarErrorCode 读取 sidecar 错误码。
func sidecarErrorCode(payload sidecarLensResponse) string {
	if payload.Error == nil {
		return ""
	}
	return strings.TrimSpace(payload.Error.Code)
}

// sidecarResultError 构造业务错误：优先复用 sidecar 的 code/message，
// 缺失或非法时回退到调用方给的兜底值。
func sidecarResultError(payload sidecarLensResponse, parsed bool, fallbackCode, fallbackMessage string) *ResultError {
	if parsed && payload.Error != nil {
		code := strings.TrimSpace(payload.Error.Code)
		if code != "" {
			return &ResultError{Code: code, Message: truncateRunes(strings.TrimSpace(payload.Error.Message), sidecarErrorMaxRunes)}
		}
	}
	if parsed {
		if message := truncateRunes(strings.TrimSpace(payload.Message), sidecarErrorMaxRunes); message != "" {
			return &ResultError{Code: fallbackCode, Message: message}
		}
	}
	return &ResultError{Code: fallbackCode, Message: fallbackMessage}
}

// buildLensMultipart 构造 multipart/form-data（字段 file），扩展名随媒体类型。
func buildLensMultipart(image []byte, mediaType string) ([]byte, string, error) {
	var buf bytes.Buffer
	writer := multipart.NewWriter(&buf)
	filename := "frame"
	switch mediaType {
	case "image/jpeg":
		filename += ".jpg"
	case "image/png":
		filename += ".png"
	case "image/webp":
		filename += ".webp"
	default:
		filename += ".bin"
	}
	part, err := writer.CreateFormFile("file", filename)
	if err != nil {
		return nil, "", err
	}
	if _, err := part.Write(image); err != nil {
		return nil, "", err
	}
	if err := writer.Close(); err != nil {
		return nil, "", err
	}
	return buf.Bytes(), writer.FormDataContentType(), nil
}

// lensStatusMessage 给非 ok 状态补一条人类可读说明。
func lensStatusMessage(status LensStatus) string {
	switch status {
	case LensStatusBlocked:
		return "sidecar 被上游或验证码挡住"
	case LensStatusTimeout:
		return "sidecar 单帧处理超时"
	case LensStatusUploadRejected:
		return "sidecar 拒绝该图片"
	case LensStatusSidecarUnavailable:
		return "sidecar 不可达"
	default:
		return "sidecar 未给出可用结果"
	}
}

// classifySidecarTransport 把传输层错误映射为公开哨兵。
func classifySidecarTransport(ctx context.Context, err error) error {
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) || ctx.Err() != nil {
		return ErrSidecarTimeout
	}
	return ErrSidecarUnavailable
}

// firstRaw 返回第一个非空 JSON 原始值（snake/camel 字段二选一）。
func firstRaw(values ...json.RawMessage) json.RawMessage {
	for _, value := range values {
		if len(value) > 0 && string(value) != "null" {
			return value
		}
	}
	return nil
}

// parseSidecarTime 解析 sidecar 的抓取时刻：接受 RFC3339 字符串或 Unix
// 毫秒/秒数字（sidecar 各版本口径可能不同，能读就归一，读不出即省略）。
func parseSidecarTime(raw json.RawMessage) *time.Time {
	if len(raw) == 0 {
		return nil
	}
	var text string
	if err := json.Unmarshal(raw, &text); err == nil {
		text = strings.TrimSpace(text)
		if text == "" {
			return nil
		}
		if t, err := time.Parse(time.RFC3339Nano, text); err == nil {
			return &t
		}
		return nil
	}
	var number json.Number
	if err := json.Unmarshal(raw, &number); err == nil {
		if n, err := number.Int64(); err == nil && n > 0 {
			if n < 1_000_000_000_000 { // 秒级
				t := time.Unix(n, 0).UTC()
				return &t
			}
			t := time.UnixMilli(n).UTC()
			return &t
		}
	}
	return nil
}

// isHTTPURL 报告字符串是否为 http(s) 绝对 URL（links 过滤用）。
func isHTTPURL(raw string) bool {
	lower := strings.ToLower(raw)
	return strings.HasPrefix(lower, "https://") || strings.HasPrefix(lower, "http://")
}
