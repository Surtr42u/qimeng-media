// metrics_middleware_test.go：HTTP 请求指标中间件行为锁定。
//
// 关键行为假设（本测试存在的原因）：gen 的 StdHTTPServerOptions.Middlewares
// 在 ServeMux 路由匹配**之后**执行，此时 r.Pattern 已被 Go 1.22+ 的
// ServeMux 设置为命中的路由模板——endpoint 必须是模板而非实际 URL
// （低基数红线）。这里用同代 ServeMux 模式注册复现该时序；registry 用
// NewBusinessMetrics 独立实例，断言互不叠加。
package httpapi

import (
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"qimeng-media/server/internal/sysmon"
)

// newMetricsTestMux 按与 gen 生成物相同的形态（方法+通配符模式）注册路由
// 并包上指标中间件，metrics 用独立实例。
func newMetricsTestMux(t *testing.T) (*http.ServeMux, *sysmon.BusinessMetrics) {
	t.Helper()
	metrics := sysmon.NewBusinessMetrics()
	mw := newMetricsMiddleware(metrics)
	mux := http.NewServeMux()
	register := func(pattern string, h http.HandlerFunc) {
		mux.Handle(pattern, mw(h))
	}
	register("GET /api/v1/assets/{assetId}", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("asset"))
	})
	register("POST /api/v1/assets/upload", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusCreated)
	})
	// 排除清单四条原样注册（含 SSE 流，验证 Flusher 透传）。
	register("GET /metrics", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("metrics"))
	})
	register("GET /api/v1/healthz", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("ok"))
	})
	register("GET /api/v1/readyz", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("ok"))
	})
	register("GET /api/v1/events", func(w http.ResponseWriter, r *http.Request) {
		// 模拟 SSE：断言经过中间件后仍是 Flusher（丢失断言会 500）。
		if _, ok := w.(http.Flusher); !ok {
			t.Error("statusRecorder 丢失了 http.Flusher，SSE 将无法工作")
			return
		}
		_, _ = w.Write([]byte("event: hello\n\n"))
	})
	return mux, metrics
}

// metricsText 拉取 Prometheus 文本输出（走封装层 Handler，不摸底层 collector）。
func metricsText(t *testing.T, m *sysmon.BusinessMetrics) string {
	t.Helper()
	rec := httptest.NewRecorder()
	m.Handler()(rec, httptest.NewRequest(http.MethodGet, "/metrics", nil))
	return rec.Body.String()
}

// TestMetricsMiddleware_endpointIsRouteTemplate 锁定 endpoint=路由模板：
// 实际 URL 的路径参数（abc-123）绝不能出现在 label 里。
func TestMetricsMiddleware_endpointIsRouteTemplate(t *testing.T) {
	mux, metrics := newMetricsTestMux(t)
	res := httptest.NewRecorder()
	mux.ServeHTTP(res, httptest.NewRequest(http.MethodGet, "/api/v1/assets/abc-123", nil))
	if res.Code != http.StatusOK {
		t.Fatalf("应 200，got %d", res.Code)
	}
	out := metricsText(t, metrics)
	want := `http_requests_total{code="200",endpoint="/api/v1/assets/{assetId}",method="GET"} 1`
	if !strings.Contains(out, want) {
		t.Errorf("应包含 %q，实际输出：\n%s", want, out)
	}
	if strings.Contains(out, "abc-123") {
		t.Errorf("endpoint 退化成了实际 URL（高基数），输出：\n%s", out)
	}
	// duration 直方图对同一 endpoint 有观测（count=1）。
	if !strings.Contains(out, `http_request_duration_seconds_count{endpoint="/api/v1/assets/{assetId}"}`) {
		t.Errorf("duration 直方图缺观测，输出：\n%s", out)
	}
}

// TestMetricsMiddleware_statusCodeCaptured：显式 WriteHeader 的非 200 状态
// 码必须如实进入 code label。
func TestMetricsMiddleware_statusCodeCaptured(t *testing.T) {
	mux, metrics := newMetricsTestMux(t)
	res := httptest.NewRecorder()
	mux.ServeHTTP(res, httptest.NewRequest(http.MethodPost, "/api/v1/assets/upload", nil))
	if res.Code != http.StatusCreated {
		t.Fatalf("应 201，got %d", res.Code)
	}
	out := metricsText(t, metrics)
	want := `http_requests_total{code="201",endpoint="/api/v1/assets/upload",method="POST"} 1`
	if !strings.Contains(out, want) {
		t.Errorf("应包含 %q，实际输出：\n%s", want, out)
	}
}

// TestMetricsMiddleware_excludedEndpoints：排除清单四条路由即使命中处理
// 也整条不计数（qps 与 duration 都不产生序列）。
func TestMetricsMiddleware_excludedEndpoints(t *testing.T) {
	mux, metrics := newMetricsTestMux(t)
	for _, path := range []string{"/metrics", "/api/v1/healthz", "/api/v1/readyz"} {
		res := httptest.NewRecorder()
		mux.ServeHTTP(res, httptest.NewRequest(http.MethodGet, path, nil))
		if res.Code != http.StatusOK {
			t.Errorf("%s 处理应正常 200，got %d", path, res.Code)
		}
	}
	out := metricsText(t, metrics)
	if strings.Contains(out, "http_requests_total") {
		t.Errorf("排除清单路由不应产生 http_requests_total 序列，输出：\n%s", out)
	}
	if strings.Contains(out, "http_request_duration_seconds") {
		t.Errorf("排除清单路由不应产生 duration 序列，输出：\n%s", out)
	}
}

// rfRecorder 是实现了 io.ReaderFrom 的底层 writer（模拟 net/http 的
// response）：记录 ReadFrom 是否被委托到它。httptest.ResponseRecorder 没有
// ReadFrom 方法（go doc 实测），无法用于验证委托路径，故自建。
type rfRecorder struct {
	http.ResponseWriter
	readFromCalled bool
	n              int64
}

func (w *rfRecorder) ReadFrom(src io.Reader) (int64, error) {
	w.readFromCalled = true
	n, err := io.Copy(io.Discard, src)
	w.n += n
	return n, err
}

// TestMetricsMiddleware_readFromDelegated 锁定 ReadFrom 委托：handler 经
// ReadFrom 输出（http.ServeContent 的实际路径）时，statusRecorder 必须把
// ReadFrom 转给底层 writer 的 ReadFrom（否则底层 sendfile 零拷贝退化为
// 用户态缓冲拷贝），且状态码捕获在 ReadFrom 路径上不失效（隐式 200）。
func TestMetricsMiddleware_readFromDelegated(t *testing.T) {
	metrics := sysmon.NewBusinessMetrics()
	h := newMetricsMiddleware(metrics)(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		rf, ok := w.(io.ReaderFrom)
		if !ok {
			t.Fatal("statusRecorder 未实现 io.ReaderFrom，ServeContent 大文件发送将退化为用户态拷贝")
		}
		if _, err := rf.ReadFrom(strings.NewReader("payload")); err != nil {
			t.Fatalf("ReadFrom 失败: %v", err)
		}
	}))
	bottom := &rfRecorder{ResponseWriter: httptest.NewRecorder()}
	req := httptest.NewRequest(http.MethodGet, "/api/v1/assets/abc-123", nil)
	req.Pattern = "GET /api/v1/assets/{assetId}" // 复现 ServeMux 匹配后的时序
	h.ServeHTTP(bottom, req)
	if !bottom.readFromCalled {
		t.Error("statusRecorder 未把 ReadFrom 委托给底层 writer 的 ReadFrom（sendfile 断路）")
	}
	out := metricsText(t, metrics)
	want := `http_requests_total{code="200",endpoint="/api/v1/assets/{assetId}",method="GET"} 1`
	if !strings.Contains(out, want) {
		t.Errorf("ReadFrom 路径状态码捕获失效，应包含 %q，实际输出：\n%s", want, out)
	}
}

// TestMetricsMiddleware_sseFlusherPreserved：/api/v1/events 虽不计数，
// 但响应链必须保留 Flusher（SSE 逐帧 flush 依赖），响应内容不被破坏，
// 且整条路由（qps 与 duration）确认不产生任何序列（排除口径的反向断言）。
func TestMetricsMiddleware_sseFlusherPreserved(t *testing.T) {
	mux, metrics := newMetricsTestMux(t)
	res := httptest.NewRecorder()
	mux.ServeHTTP(res, httptest.NewRequest(http.MethodGet, "/api/v1/events", nil))
	if res.Code != http.StatusOK {
		t.Fatalf("SSE 应 200，got %d", res.Code)
	}
	if !strings.Contains(res.Body.String(), "event: hello") {
		t.Errorf("SSE 首帧应原样写出，got %q", res.Body.String())
	}
	out := metricsText(t, metrics)
	if strings.Contains(out, "http_requests_total") {
		t.Errorf("SSE 排除路径不应产生 http_requests_total 序列，输出：\n%s", out)
	}
	if strings.Contains(out, "http_request_duration_seconds") {
		t.Errorf("SSE 排除路径不应产生 duration 序列，输出：\n%s", out)
	}
}
