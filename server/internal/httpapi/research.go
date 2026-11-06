// research.go：媒体研究流水线端点（GET /research/context、POST /research/
// providers/{provider}/query、POST /research/lens，ADR-0053）。
//
// 分层（ADR-0019）：本层只做协议校验、错误映射与响应装配；provider 矩阵、
// 安全 HTTP 出口、预算、sidecar 传输与证据落盘都在 internal/research。
// 研究默认关闭（research.enabled=false）：context 仍 200 回报 enabled=false，
// 查询 503 RESEARCH_DISABLED，Lens 503 RESEARCH_SIDECAR_DISABLED。
package httpapi

import (
	"errors"
	"io"
	"mime"
	"net/http"
	"strings"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/research"
)

const (
	// maxLensImageBytes 是 Lens 上传的单文件上限（协议 20MiB）。
	maxLensImageBytes = 20 << 20
	// lensMultipartOverhead 是 multipart 边界/头部预留：总请求体上限 =
	// 图片上限 + 1MiB，避免把边界开销误判成图片超限。
	lensMultipartOverhead = 1 << 20
	// lensMultipartMemory 是 multipart 解析的内存阈值（超出部分落临时文件）。
	lensMultipartMemory = 4 << 20
)

// GetApiV1ResearchContext 返回研究能力上下文。
func (s *Server) GetApiV1ResearchContext(w http.ResponseWriter, r *http.Request) {
	info := s.research.Context(r.Context())
	providers := make([]gen.ResearchProviderInfo, 0, len(info.Providers))
	for _, provider := range info.Providers {
		operations := make([]gen.ResearchOperation, 0, len(provider.Operations))
		for _, op := range provider.Operations {
			operations = append(operations, gen.ResearchOperation(op))
		}
		providers = append(providers, gen.ResearchProviderInfo{
			Name:       gen.ResearchProviderName(provider.Name),
			Operations: operations,
			Enabled:    provider.Enabled,
			Limits: gen.ResearchProviderLimits{
				PerMinute: provider.Limits.PerMinute,
				PerDay:    provider.Limits.PerDay,
			},
		})
	}
	sidecar := gen.ResearchSidecarStatus{
		Configured: info.Sidecar.Configured,
		Available:  info.Sidecar.Available,
	}
	if info.Sidecar.Configured {
		pending := info.Sidecar.Pending
		sidecar.Pending = &pending
	}
	writeJSON(w, http.StatusOK, gen.ResearchContext{
		Enabled:   info.Enabled,
		Sidecar:   sidecar,
		Providers: providers,
		Limits: gen.ResearchLimits{
			LensPerHour: info.LensLimits.PerHour,
			LensPerDay:  info.LensLimits.PerDay,
		},
		FetchedAt: info.FetchedAt,
	})
}

// PostApiV1ResearchProvidersProviderQuery 执行一次 provider 查询。
func (s *Server) PostApiV1ResearchProvidersProviderQuery(w http.ResponseWriter, r *http.Request, provider gen.ResearchProviderName) {
	var body gen.ResearchProviderQuery
	if !decodeJSON(w, r, &body) {
		return
	}
	if !body.Operation.Valid() {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "operation 不在支持范围内")
		return
	}
	target := strings.TrimSpace(body.Target)
	if target == "" || len([]rune(target)) > research.MaxTargetRunes {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "target 须为 1–500 字符")
		return
	}
	limit := research.DefaultLimit
	if body.Options != nil && body.Options.Limit != nil {
		if *body.Options.Limit < 1 || *body.Options.Limit > research.MaxLimit {
			writeErr(w, http.StatusBadRequest, codeInvalidParam, "options.limit 须在 1–50")
			return
		}
		limit = *body.Options.Limit
	}
	name := research.Name(provider)
	operation := research.Operation(body.Operation)
	result, err := s.research.Query(r.Context(), name, research.Query{
		Operation: operation,
		Target:    target,
		Limit:     limit,
	})
	if err != nil {
		s.writeResearchError(w, err)
		return
	}
	items := make([]gen.ResearchItem, 0, len(result.Items))
	for _, item := range result.Items {
		items = append(items, researchItemToGen(item))
	}
	payload := gen.ResearchProviderResult{
		Provider:  gen.ResearchProviderName(name),
		Operation: gen.ResearchOperation(operation),
		Target:    target,
		Status:    gen.ResearchProviderResultStatus(result.Status),
		FetchedAt: result.FetchedAt,
		LatencyMs: result.LatencyMs,
		Items:     items,
	}
	if result.Err != nil {
		payload.Error = &gen.Error{Code: result.Err.Code, Message: result.Err.Message}
	}
	writeJSON(w, http.StatusOK, payload)
}

// PostApiV1ResearchLens 把图片转发给本机 Lens sidecar（best-effort）。
func (s *Server) PostApiV1ResearchLens(w http.ResponseWriter, r *http.Request) {
	mediaType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || mediaType != "multipart/form-data" {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "请求体必须为 multipart/form-data")
		return
	}
	// 总请求体上限 = 图片上限 + 边界开销；超过即 413（MaxBytesReader 在
	// ParseMultipartForm 内触发）。
	r.Body = http.MaxBytesReader(w, r.Body, maxLensImageBytes+lensMultipartOverhead)
	if err := r.ParseMultipartForm(lensMultipartMemory); err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			writeErr(w, http.StatusRequestEntityTooLarge, codeTooLarge, "图片超过 20MiB 上限")
			return
		}
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "multipart 请求体不合法或缺少 file 字段")
		return
	}
	defer func() {
		if r.MultipartForm != nil {
			// 临时文件清理失败只影响磁盘占用，不影响本次结果。
			_ = r.MultipartForm.RemoveAll()
		}
	}()
	file, _, err := r.FormFile("file")
	if err != nil {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "缺少 file 字段")
		return
	}
	defer func() { _ = file.Close() }()
	data, err := io.ReadAll(io.LimitReader(file, maxLensImageBytes+1))
	if err != nil {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "读取图片失败")
		return
	}
	if len(data) == 0 {
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "图片为空")
		return
	}
	if len(data) > maxLensImageBytes {
		writeErr(w, http.StatusRequestEntityTooLarge, codeTooLarge, "图片超过 20MiB 上限")
		return
	}
	// 类型以魔数嗅探为准，不信任声明的 Content-Type（客户端可任意伪造）。
	sniffed := http.DetectContentType(data)
	switch sniffed {
	case "image/jpeg", "image/png", "image/webp":
	default:
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "仅支持 JPEG/PNG/WebP 图片")
		return
	}
	result, err := s.research.Lens(r.Context(), data, sniffed)
	if err != nil {
		s.writeResearchError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, lensResultToGen(result))
}

// writeResearchError 把研究哨兵错误映射为协议错误码。
func (s *Server) writeResearchError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, research.ErrDisabled):
		writeErr(w, http.StatusServiceUnavailable, codeResearchDisabled, "研究流水线未启用")
	case errors.Is(err, research.ErrUnknownProvider):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "未知 provider")
	case errors.Is(err, research.ErrUnsupportedOperation):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "该 provider 不支持此 operation")
	case errors.Is(err, research.ErrInvalidTarget):
		writeErr(w, http.StatusBadRequest, codeInvalidParam, "target 不合法")
	case errors.Is(err, research.ErrSidecarDisabled):
		writeErr(w, http.StatusServiceUnavailable, codeResearchSidecarDisabled, "Lens sidecar 未配置或已禁用")
	case errors.Is(err, research.ErrSidecarUnavailable):
		writeErr(w, http.StatusServiceUnavailable, codeResearchSidecarUnavailable, "Lens sidecar 不可达")
	case errors.Is(err, research.ErrSidecarTimeout):
		writeErr(w, http.StatusGatewayTimeout, codeResearchSidecarTimeout, "Lens sidecar 超时")
	case errors.Is(err, research.ErrSidecarResponse):
		writeErr(w, http.StatusBadGateway, codeResearchSidecarError, "Lens sidecar 返回异常响应")
	case errors.Is(err, research.ErrLensRateLimited):
		writeErr(w, http.StatusTooManyRequests, codeRateLimited, "Lens 预算已用尽，请稍后重试")
	case errors.Is(err, research.ErrSidecarBusy):
		writeErr(w, http.StatusTooManyRequests, codeRateLimited, "Lens sidecar 忙，请稍后重试")
	default:
		s.internalErr(w, "研究请求", err)
	}
}

// researchItemToGen 领域 item → 协议 ResearchItem（空字段省略）。
func researchItemToGen(item research.Item) gen.ResearchItem {
	out := gen.ResearchItem{}
	if item.Title != "" {
		title := item.Title
		out.Title = &title
	}
	if item.URL != "" {
		link := item.URL
		out.Url = &link
	}
	if item.Domain != "" {
		domain := item.Domain
		out.Domain = &domain
	}
	if item.Author != "" {
		author := item.Author
		out.Author = &author
	}
	if item.ID != "" {
		id := item.ID
		out.Id = &id
	}
	if item.PublishedAt != "" {
		published := item.PublishedAt
		out.PublishedAt = &published
	}
	if len(item.Extra) > 0 {
		extra := make(map[string]string, len(item.Extra))
		for key, value := range item.Extra {
			if value == "" {
				continue
			}
			extra[key] = value
		}
		if len(extra) > 0 {
			out.Extra = &extra
		}
	}
	return out
}

// lensResultToGen 领域 Lens 结果 → 协议 ResearchLensResult（空切片保持 []）。
func lensResultToGen(result research.LensResult) gen.ResearchLensResult {
	out := gen.ResearchLensResult{
		Status:      gen.ResearchLensResultStatus(result.Status),
		FrameSha256: result.FrameSHA256,
		TopDomains:  append([]string{}, result.TopDomains...),
	}
	if result.TextSHA256 != "" {
		textSHA := result.TextSHA256
		out.TextSha256 = &textSHA
	}
	if result.FetchedAt != nil {
		fetched := *result.FetchedAt
		out.FetchedAt = &fetched
	}
	if result.ElapsedMs > 0 {
		elapsed := result.ElapsedMs
		out.ElapsedMs = &elapsed
	}
	if result.ResultURL != "" {
		link := result.ResultURL
		out.ResultUrl = &link
	}
	out.TopTitles = make([]gen.ResearchLensTitle, 0, len(result.TopTitles))
	for _, title := range result.TopTitles {
		out.TopTitles = append(out.TopTitles, gen.ResearchLensTitle{Title: title.Title, Domain: title.Domain})
	}
	out.Cites = make([]gen.ResearchLensCite, 0, len(result.Cites))
	for _, cite := range result.Cites {
		entry := gen.ResearchLensCite{Domain: cite.Domain, Url: cite.URL}
		if cite.Title != "" {
			title := cite.Title
			entry.Title = &title
		}
		out.Cites = append(out.Cites, entry)
	}
	out.Links = make([]gen.ResearchLensLink, 0, len(result.Links))
	for _, link := range result.Links {
		entry := gen.ResearchLensLink{Domain: link.Domain, Url: link.URL}
		if link.Title != "" {
			title := link.Title
			entry.Title = &title
		}
		out.Links = append(out.Links, entry)
	}
	if result.Err != nil {
		out.Error = &gen.Error{Code: result.Err.Code, Message: result.Err.Message}
	}
	return out
}
