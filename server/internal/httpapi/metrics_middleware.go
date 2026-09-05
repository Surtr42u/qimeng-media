// metrics_middleware.go：HTTP 请求指标中间件（http_requests_total /
// http_request_duration_seconds 的唯一埋点）。
//
// 挂载点在 gen.StdHTTPServerOptions.Middlewares：一处覆盖全部 gen 路由，
// 且执行时机在 ServeMux 路由匹配之后（gen 生成的 wrapper 先匹配再包
// 中间件），因此 r.Pattern 已就绪、endpoint 天然是低基数路由模板。
// 口径与 docs/OBSERVABILITY.md「指标口径注记」一致，两侧同步修改。
package httpapi

import (
	"io"
	"net/http"
	"strings"
	"time"

	"qimeng-media/server/internal/sysmon"
)

// metricsExcludedEndpoints 是不参与 http 请求计数（qps 与 duration 整条
// 跳过）的路由模板清单，口径见 docs/OBSERVABILITY.md：
//   - /metrics：指标抓取自引用，计进去只会让 QPS 恒 ≥ 抓取频率；
//   - /api/v1/healthz、/api/v1/readyz：探针高频轮询，会淹没业务请求曲线；
//   - /api/v1/events：SSE 分钟级长流，qps/duration 对它无意义（长尾污染），
//     在线数由 sse_connections gauge 单独覆盖。
//
// 与 openapi.yaml 的 security: [] / 探针路径联动：协议侧改动须同步此处，
// 反之亦然（topRouter 免鉴权清单是另一份独立清单，语义不同勿合并）。
var metricsExcludedEndpoints = map[string]struct{}{
	"/metrics":        {},
	"/api/v1/healthz": {},
	"/api/v1/readyz":  {},
	"/api/v1/events":  {},
}

// metricsUnmatchedEndpoint 是 r.Pattern 为空时的低基数兜底 label（理论上
// 不可达：中间件只在 gen wrapper 内、即路由匹配之后运行；防御未来装配
// 方式变化时 endpoint 退化成高基数的 URL）。
const metricsUnmatchedEndpoint = "UNMATCHED"

// metricsEndpointPattern 从 r.Pattern 提取路由模板：Go 1.22+ 的 ServeMux
// 在匹配时设置 r.Pattern（形如 "GET /api/v1/assets/{assetId}"），去掉方法
// 前缀后作 endpoint label。禁用 r.URL.Path——路径参数与查询串是高基数，
// 会撑爆时间序列（metrics.go 注册注释与 OBSERVABILITY.md 同口径）。
func metricsEndpointPattern(pattern string) string {
	if i := strings.IndexByte(pattern, ' '); i >= 0 {
		return pattern[i+1:]
	}
	return pattern
}

// statusRecorder 捕获 handler 实际写出的状态码（未显式 WriteHeader 时按
// net/http 惯例记 200——第一次 Write 隐式 200）。
type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

func (r *statusRecorder) Write(p []byte) (int, error) {
	if r.status == 0 {
		r.status = http.StatusOK
	}
	return r.ResponseWriter.Write(p)
}

// ReadFrom 透传 io.ReaderFrom：http.ServeContent 内部用 io.CopyN 输出 body，
// wrapper 不实现该接口会让底层连接的 sendfile 零拷贝退化成用户态缓冲拷贝
// （大文件直链白耗 CPU 与内存带宽）。委托底层 writer 的 ReadFrom，底层不
// 支持时 io.Copy 兜底；两条路径都先补隐式 200（与 Write 同语义：第一次
// 写出即隐式 200，状态码捕获不能丢）。
func (r *statusRecorder) ReadFrom(src io.Reader) (int64, error) {
	if r.status == 0 {
		r.status = http.StatusOK
	}
	if rf, ok := r.ResponseWriter.(io.ReaderFrom); ok {
		return rf.ReadFrom(src)
	}
	return io.Copy(r.ResponseWriter, src)
}

// Flush 透传 http.Flusher：中间件物理上包着全部 gen 路由（含 SSE
// /api/v1/events——虽在排除清单不计数，但流仍经此链），丢掉 Flusher
// 断言会让 events 包的 flusher 判定失败直接 500。
func (r *statusRecorder) Flush() {
	if f, ok := r.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

// statusCode 返回捕获的状态码（零值 = 未写过 = 隐式 200）。
func (r *statusRecorder) statusCode() int {
	if r.status == 0 {
		return http.StatusOK
	}
	return r.status
}

// newMetricsMiddleware 构造 gen 路由的指标中间件。参数化 metrics 实例
// 便于单测用独立 registry 断言；进程装配传 sysmon.Default（server.go）。
//
// 已知口径（OBSERVABILITY.md 注记）：绑定层 400（query 参数校验失败）、
// 未匹配路由 404 与方法不匹配 405（后两者 ServeMux 直接兜底）发生在
// 中间件之前/之外，不进入 http 指标。
func newMetricsMiddleware(m *sysmon.BusinessMetrics) func(http.Handler) http.Handler {
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			endpoint := metricsEndpointPattern(r.Pattern)
			if endpoint == "" {
				endpoint = metricsUnmatchedEndpoint
			}
			if _, excluded := metricsExcludedEndpoints[endpoint]; excluded {
				next.ServeHTTP(w, r)
				return
			}
			rec := &statusRecorder{ResponseWriter: w}
			start := time.Now()
			next.ServeHTTP(rec, r)
			m.IncHTTPRequest(endpoint, r.Method, rec.statusCode())
			m.ObserveHTTPRequestDuration(endpoint, time.Since(start).Seconds())
		})
	}
}
