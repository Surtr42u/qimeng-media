// research_test.go：研究流水线端点协议层测试（ADR-0053）。
// 全部流量打在 httptest 隔离实例：假上游 = 本机 httptest，假 sidecar =
// 本机 httptest loopback；不访问真实平台、不触碰 8420 真库。
package httpapi

import (
	"bytes"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/research"
)

// researchProviderNames 是 v1 固定矩阵（测试与生产同源）。
var researchProviderNames = []research.Name{
	research.NameFxTwitter, research.NamePixiv, research.NameYandere,
	research.NameE621, research.NameWayback, research.NameSteam, research.NameBing,
}

// newResearchServiceForTest 组装研究服务：provider 基地址指向假上游，
// sidecar 指向假 sidecar（全部 loopback，显式 AllowLoopbackEndpoints）。
func newResearchServiceForTest(t *testing.T, upstreamURL, sidecarURL string, tweak func(*config.ResearchConfig)) *research.Service {
	t.Helper()
	cfg := config.ResearchConfig{Enabled: true, LensPerHour: 20, LensPerDay: 100, ProviderPerMinute: 20}
	if tweak != nil {
		tweak(&cfg)
	}
	endpoints := make(map[research.Name]string, len(researchProviderNames))
	for _, name := range researchProviderNames {
		endpoints[name] = upstreamURL
	}
	opts := research.Options{
		Config: cfg, DataDir: t.TempDir(),
		Logger:                 slog.New(slog.NewTextHandler(io.Discard, nil)),
		Endpoints:              endpoints,
		AllowLoopbackEndpoints: true,
	}
	if sidecarURL != "" {
		client, err := research.NewSidecarClient(sidecarURL, "synthetic-token")
		if err != nil {
			t.Fatalf("构造假 sidecar 客户端失败: %v", err)
		}
		opts.Sidecar = client
	}
	svc, err := research.New(opts)
	if err != nil {
		t.Fatalf("构造研究服务失败: %v", err)
	}
	return svc
}

// newFakeSidecar 返回满足 sidecar 契约的假服务（/healthz + /lens）。
func newFakeSidecar(t *testing.T, status int) *httptest.Server {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/healthz":
			w.WriteHeader(http.StatusOK)
		case "/lens":
			if status != http.StatusOK {
				w.WriteHeader(status)
				return
			}
			if _, _, err := r.FormFile("file"); err != nil {
				t.Errorf("假 sidecar 未收到 file: %v", err)
			}
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"ok":true,"status":"ok","text_sha256":"abc","text_len":42,"elapsed_ms":17000,"topTitles":[{"title":"T","domain":"example.com"}],"topDomains":["example.com"],"cites":[],"links":[]}`)
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(server.Close)
	return server
}

// postLens 以 multipart/form-data 上传一张合成图片。
func postLens(t *testing.T, env *testEnv, field string, data []byte) *http.Response {
	t.Helper()
	var buf bytes.Buffer
	writer := multipart.NewWriter(&buf)
	part, err := writer.CreateFormFile(field, "frame.png")
	if err != nil {
		t.Fatalf("构造 multipart 失败: %v", err)
	}
	if _, err := part.Write(data); err != nil {
		t.Fatalf("写 multipart 失败: %v", err)
	}
	if err := writer.Close(); err != nil {
		t.Fatalf("关闭 multipart 失败: %v", err)
	}
	req, err := http.NewRequest(http.MethodPost, env.ts.URL+"/api/v1/research/lens", &buf)
	if err != nil {
		t.Fatalf("构造请求失败: %v", err)
	}
	req.Header.Set("Authorization", "Bearer "+env.token)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("Lens 请求失败: %v", err)
	}
	return resp
}

// pngHeader 是魔数嗅探可识别的最小 PNG 前缀（测试不做真实解码）。
var pngHeader = []byte{0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a}

// TestResearchContextEndpoint：context 返回启停/矩阵/限额/sidecar 状态。
func TestResearchContextEndpoint(t *testing.T) {
	env := newTestEnv(t)
	upstream := httptest.NewServer(http.NotFoundHandler())
	t.Cleanup(upstream.Close)
	sidecar := newFakeSidecar(t, http.StatusOK)
	env.s.research = newResearchServiceForTest(t, upstream.URL, sidecar.URL, nil)

	resp := env.do(t, "GET", "/api/v1/research/context", "")
	defer closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("context 期望 200, 得到 %d", resp.StatusCode)
	}
	var info gen.ResearchContext
	if err := decodeBody(resp, &info); err != nil {
		t.Fatalf("解析 ResearchContext 失败: %v", err)
	}
	if !info.Enabled {
		t.Error("enabled 应为 true")
	}
	if len(info.Providers) != len(researchProviderNames) {
		t.Fatalf("providers = %d, 期望 %d", len(info.Providers), len(researchProviderNames))
	}
	if info.Providers[0].Name != gen.ResearchProviderFxTwitter || info.Providers[len(info.Providers)-1].Name != gen.ResearchProviderBing {
		t.Errorf("provider 顺序 = %+v", info.Providers)
	}
	if info.Providers[0].Limits.PerMinute != 20 {
		t.Errorf("perMinute = %d", info.Providers[0].Limits.PerMinute)
	}
	if !info.Sidecar.Configured || !info.Sidecar.Available {
		t.Errorf("sidecar = %+v", info.Sidecar)
	}
	if info.Sidecar.Pending == nil {
		t.Error("已配置 sidecar 应回传 pending")
	}
	if info.Limits.LensPerHour != 20 || info.Limits.LensPerDay != 100 {
		t.Errorf("limits = %+v", info.Limits)
	}
	if info.FetchedAt.IsZero() {
		t.Error("fetchedAt 不应为零值")
	}
}

// TestResearchProviderQueryEndpoint：假上游全链（客户端→provider→响应）。
func TestResearchProviderQueryEndpoint(t *testing.T) {
	env := newTestEnv(t)
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/johndoe" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"code":200,"message":"OK","user":{"id":"123","screen_name":"JohnDoe","name":"John","followers":10}}`)
	}))
	t.Cleanup(upstream.Close)
	env.s.research = newResearchServiceForTest(t, upstream.URL, "", nil)

	resp := env.do(t, "POST", "/api/v1/research/providers/fxtwitter/query",
		`{"operation":"author","target":"@johndoe","options":{"limit":5}}`)
	defer closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("query 期望 200, 得到 %d", resp.StatusCode)
	}
	var result gen.ResearchProviderResult
	if err := decodeBody(resp, &result); err != nil {
		t.Fatalf("解析 ResearchProviderResult 失败: %v", err)
	}
	if result.Provider != gen.ResearchProviderFxTwitter || result.Operation != gen.ResearchOpAuthor || result.Target != "@johndoe" {
		t.Errorf("回显字段 = %+v", result)
	}
	if result.Status != gen.ResearchStatusOk || len(result.Items) != 1 {
		t.Fatalf("结果 = %+v", result)
	}
	if result.Items[0].Url == nil || *result.Items[0].Url != "https://x.com/JohnDoe" {
		t.Errorf("item url = %v", result.Items[0].Url)
	}
	if result.Items[0].Extra == nil || (*result.Items[0].Extra)["followers"] != "10" {
		t.Errorf("item extra = %v", result.Items[0].Extra)
	}
	if result.Error != nil {
		t.Errorf("成功结果不应带 error: %+v", result.Error)
	}
}

// TestResearchProviderQueryValidation：协议层校验失败一律 400 INVALID_PARAM。
func TestResearchProviderQueryValidation(t *testing.T) {
	env := newTestEnv(t)
	upstream := httptest.NewServer(http.NotFoundHandler())
	t.Cleanup(upstream.Close)
	env.s.research = newResearchServiceForTest(t, upstream.URL, "", nil)

	cases := []struct {
		name string
		path string
		body string
	}{
		{"unsupported_operation", "/api/v1/research/providers/fxtwitter/query", `{"operation":"work","target":"x"}`},
		{"unknown_provider", "/api/v1/research/providers/unknown/query", `{"operation":"author","target":"x"}`},
		{"unknown_operation", "/api/v1/research/providers/fxtwitter/query", `{"operation":"bogus","target":"x"}`},
		{"empty_target", "/api/v1/research/providers/fxtwitter/query", `{"operation":"author","target":"  "}`},
		{"limit_zero", "/api/v1/research/providers/fxtwitter/query", `{"operation":"author","target":"x","options":{"limit":0}}`},
		{"limit_too_large", "/api/v1/research/providers/fxtwitter/query", `{"operation":"author","target":"x","options":{"limit":51}}`},
	}
	for _, c := range cases {
		resp := env.do(t, "POST", c.path, c.body)
		code := resp.StatusCode
		var payload gen.Error
		err := decodeBody(resp, &payload)
		closeBody(resp)
		if code != http.StatusBadRequest {
			t.Errorf("%s 期望 400, 得到 %d", c.name, code)
			continue
		}
		if err != nil || payload.Code != codeInvalidParam {
			t.Errorf("%s code = %+v, 期望 INVALID_PARAM", c.name, payload)
		}
	}

	// 非 JSON Content-Type：跨站简单请求防线（errors.go requireJSONContentType）。
	req, _ := http.NewRequest(http.MethodPost, env.ts.URL+"/api/v1/research/providers/fxtwitter/query",
		strings.NewReader(`{"operation":"author","target":"x"}`))
	req.Header.Set("Authorization", "Bearer "+env.token)
	req.Header.Set("Content-Type", "text/plain")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	defer closeBody(resp)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("text/plain 期望 400, 得到 %d", resp.StatusCode)
	}
}

// TestResearchDisabledEndpoints：研究关闭时 context 可读、查询 503。
func TestResearchDisabledEndpoints(t *testing.T) {
	env := newTestEnv(t)
	upstream := httptest.NewServer(http.NotFoundHandler())
	t.Cleanup(upstream.Close)
	env.s.research = newResearchServiceForTest(t, upstream.URL, "", func(c *config.ResearchConfig) { c.Enabled = false })

	resp := env.do(t, "GET", "/api/v1/research/context", "")
	var info gen.ResearchContext
	_ = decodeBody(resp, &info)
	closeBody(resp)
	if info.Enabled {
		t.Error("context.enabled 应为 false")
	}

	resp = env.do(t, "POST", "/api/v1/research/providers/fxtwitter/query", `{"operation":"author","target":"x"}`)
	defer closeBody(resp)
	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("关闭时 query 期望 503, 得到 %d", resp.StatusCode)
	}
	var payload gen.Error
	if err := decodeBody(resp, &payload); err != nil || payload.Code != codeResearchDisabled {
		t.Errorf("code = %+v, 期望 RESEARCH_DISABLED", payload)
	}
}

// TestResearchLensEndpoint：假 sidecar 全链（multipart 上传→结果装配）。
func TestResearchLensEndpoint(t *testing.T) {
	env := newTestEnv(t)
	upstream := httptest.NewServer(http.NotFoundHandler())
	t.Cleanup(upstream.Close)
	sidecar := newFakeSidecar(t, http.StatusOK)
	env.s.research = newResearchServiceForTest(t, upstream.URL, sidecar.URL, nil)

	resp := postLens(t, env, "file", pngHeader)
	defer closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("lens 期望 200, 得到 %d", resp.StatusCode)
	}
	var result gen.ResearchLensResult
	if err := decodeBody(resp, &result); err != nil {
		t.Fatalf("解析 ResearchLensResult 失败: %v", err)
	}
	if result.Status != gen.LensStatusOk {
		t.Errorf("status = %q", result.Status)
	}
	if len(result.FrameSha256) != 64 {
		t.Errorf("frameSha256 = %q, 期望 64 位十六进制", result.FrameSha256)
	}
	if len(result.TopTitles) != 1 || result.TopTitles[0].Title != "T" {
		t.Errorf("topTitles = %+v", result.TopTitles)
	}
	if result.Links == nil || len(result.Links) != 0 {
		t.Errorf("links = %v, 期望空数组（best-effort 可为空）", result.Links)
	}
	if result.ElapsedMs == nil || *result.ElapsedMs != 17000 {
		t.Errorf("elapsedMs = %v", result.ElapsedMs)
	}

	// 鉴权：无 token 走全局 Bearer → 401。
	noAuth, err := http.Post(env.ts.URL+"/api/v1/research/lens", "multipart/form-data; boundary=x", strings.NewReader(""))
	if err != nil {
		t.Fatalf("无 token 请求失败: %v", err)
	}
	defer closeBody(noAuth)
	if noAuth.StatusCode != http.StatusUnauthorized {
		t.Fatalf("无 token 期望 401, 得到 %d", noAuth.StatusCode)
	}
}

// TestResearchLensValidation：缺字段、非图片、超限分别 400/400/413。
func TestResearchLensValidation(t *testing.T) {
	env := newTestEnv(t)
	upstream := httptest.NewServer(http.NotFoundHandler())
	t.Cleanup(upstream.Close)
	sidecar := newFakeSidecar(t, http.StatusOK)
	env.s.research = newResearchServiceForTest(t, upstream.URL, sidecar.URL, nil)

	// 字段名不对 → 400。
	resp := postLens(t, env, "image", pngHeader)
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("错误字段名期望 400, 得到 %d", resp.StatusCode)
	}
	closeBody(resp)

	// 非图片字节 → 400（魔数嗅探拒绝）。
	resp = postLens(t, env, "file", []byte("this is not an image"))
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("非图片期望 400, 得到 %d", resp.StatusCode)
	}
	closeBody(resp)

	// 超过 20MiB → 413。
	oversized := make([]byte, maxLensImageBytes+1)
	copy(oversized, pngHeader)
	resp = postLens(t, env, "file", oversized)
	if resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Errorf("超限期望 413, 得到 %d", resp.StatusCode)
	}
	closeBody(resp)
}

// TestResearchLensSidecarFailures：sidecar 未配置/不可达/预算耗尽的映射。
func TestResearchLensSidecarFailures(t *testing.T) {
	env := newTestEnv(t)
	upstream := httptest.NewServer(http.NotFoundHandler())
	t.Cleanup(upstream.Close)

	// 未配置 sidecar → 503 RESEARCH_SIDECAR_DISABLED。
	env.s.research = newResearchServiceForTest(t, upstream.URL, "", nil)
	resp := postLens(t, env, "file", pngHeader)
	var payload gen.Error
	_ = decodeBody(resp, &payload)
	closeBody(resp)
	if resp.StatusCode != http.StatusServiceUnavailable || payload.Code != codeResearchSidecarDisabled {
		t.Fatalf("未配置 sidecar = %d %+v, 期望 503 RESEARCH_SIDECAR_DISABLED", resp.StatusCode, payload)
	}

	// sidecar 503 → 503 RESEARCH_SIDECAR_UNAVAILABLE。
	sidecar := newFakeSidecar(t, http.StatusServiceUnavailable)
	env.s.research = newResearchServiceForTest(t, upstream.URL, sidecar.URL, nil)
	resp = postLens(t, env, "file", pngHeader)
	payload = gen.Error{}
	_ = decodeBody(resp, &payload)
	closeBody(resp)
	if resp.StatusCode != http.StatusServiceUnavailable || payload.Code != codeResearchSidecarUnavailable {
		t.Fatalf("sidecar 503 = %d %+v, 期望 RESEARCH_SIDECAR_UNAVAILABLE", resp.StatusCode, payload)
	}

	// 每小时/每日预算 1：第二次 429。
	okSidecar := newFakeSidecar(t, http.StatusOK)
	env.s.research = newResearchServiceForTest(t, upstream.URL, okSidecar.URL, func(c *config.ResearchConfig) {
		c.LensPerHour = 1
		c.LensPerDay = 1
	})
	resp = postLens(t, env, "file", pngHeader)
	closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("第一次 lens 期望 200, 得到 %d", resp.StatusCode)
	}
	resp = postLens(t, env, "file", pngHeader)
	payload = gen.Error{}
	_ = decodeBody(resp, &payload)
	closeBody(resp)
	if resp.StatusCode != http.StatusTooManyRequests || payload.Code != codeRateLimited {
		t.Fatalf("预算耗尽 = %d %+v, 期望 429 RATE_LIMITED", resp.StatusCode, payload)
	}
}

// TestResearchLensSidecarBusinessStatuses：sidecar 业务状态按协议 200 表达
// （blocked_bot / timeout），sidecar BUSY 映射 429，异常响应 502。
func TestResearchLensSidecarBusinessStatuses(t *testing.T) {
	env := newTestEnv(t)
	upstream := httptest.NewServer(http.NotFoundHandler())
	t.Cleanup(upstream.Close)

	newStatusSidecar := func(t *testing.T, status int, body string) *httptest.Server {
		t.Helper()
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			switch r.URL.Path {
			case "/healthz":
				_, _ = io.WriteString(w, `{"ok":true,"ready":true}`)
			case "/lens":
				w.WriteHeader(status)
				_, _ = io.WriteString(w, body)
			default:
				http.NotFound(w, r)
			}
		}))
		t.Cleanup(server.Close)
		return server
	}

	// 503 BLOCKED_BOT：sidecar 被风控挡住 = 业务结果 blocked（200），不是 503。
	blocked := newStatusSidecar(t, http.StatusServiceUnavailable,
		`{"ok":false,"status":"blocked_bot","error":{"code":"BLOCKED_BOT","message":"captcha"}}`)
	env.s.research = newResearchServiceForTest(t, upstream.URL, blocked.URL, nil)
	resp := postLens(t, env, "file", pngHeader)
	var result gen.ResearchLensResult
	_ = decodeBody(resp, &result)
	closeBody(resp)
	if resp.StatusCode != http.StatusOK || result.Status != gen.LensStatusBlocked {
		t.Fatalf("blocked_bot = %d %+v, 期望 200 status=blocked", resp.StatusCode, result.Status)
	}
	if result.Error == nil || result.Error.Code != "BLOCKED_BOT" {
		t.Errorf("blocked error = %+v", result.Error)
	}

	// 504 TIMEOUT：sidecar 报告单帧超时 = 业务结果 timeout（200）。
	timeout := newStatusSidecar(t, http.StatusGatewayTimeout,
		`{"ok":false,"status":"timeout","error":{"code":"TIMEOUT"}}`)
	env.s.research = newResearchServiceForTest(t, upstream.URL, timeout.URL, nil)
	resp = postLens(t, env, "file", pngHeader)
	result = gen.ResearchLensResult{}
	_ = decodeBody(resp, &result)
	closeBody(resp)
	if resp.StatusCode != http.StatusOK || result.Status != gen.LensStatusTimeout {
		t.Fatalf("timeout = %d %+v, 期望 200 status=timeout", resp.StatusCode, result.Status)
	}

	// sidecar 429 BUSY → 429 RATE_LIMITED（调用方退避）。
	busy := newStatusSidecar(t, http.StatusTooManyRequests, `{"ok":false,"status":"error","error":{"code":"BUSY"}}`)
	env.s.research = newResearchServiceForTest(t, upstream.URL, busy.URL, nil)
	resp = postLens(t, env, "file", pngHeader)
	var payload gen.Error
	_ = decodeBody(resp, &payload)
	closeBody(resp)
	if resp.StatusCode != http.StatusTooManyRequests || payload.Code != codeRateLimited {
		t.Fatalf("busy = %d %+v, 期望 429 RATE_LIMITED", resp.StatusCode, payload)
	}

	// 502 NO_RESULT → 502 RESEARCH_SIDECAR_ERROR。
	noResult := newStatusSidecar(t, http.StatusBadGateway, `{"ok":false,"status":"error","error":{"code":"NO_RESULT"}}`)
	env.s.research = newResearchServiceForTest(t, upstream.URL, noResult.URL, nil)
	resp = postLens(t, env, "file", pngHeader)
	payload = gen.Error{}
	_ = decodeBody(resp, &payload)
	closeBody(resp)
	if resp.StatusCode != http.StatusBadGateway || payload.Code != codeResearchSidecarError {
		t.Fatalf("no_result = %d %+v, 期望 502 RESEARCH_SIDECAR_ERROR", resp.StatusCode, payload)
	}
}
