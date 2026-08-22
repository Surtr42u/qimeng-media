package sysmon

import (
	"net/http"
	"strconv"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promauto"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

// MediaKind 是媒体流量计数的分类维度（OBSERVABILITY「媒体流量」一行：
// 原图/缩略图/视频直链各自累计——用户最关心的"流量"）。
type MediaKind string

const (
	MediaOrig  MediaKind = "orig"  // 原图
	MediaThumb MediaKind = "thumb" // 缩略图
	MediaVideo MediaKind = "video" // 视频直链
)

// UploadResult 是上传结果的维度。
type UploadResult string

const (
	UploadOK   UploadResult = "ok"
	UploadFail UploadResult = "fail"
)

// FileType 是库内文件类型维度（OBSERVABILITY「扫描」：库内图片数/视频数）。
type FileType string

const (
	FileImage FileType = "image"
	FileVideo FileType = "video"
)

// BusinessMetrics 承载全部业务指标（请求/流量/扫描/上传/回收站，OBSERVABILITY.md）。
//
// 封装层约定：调用方只允许通过本类型的方法（Inc/Observe/Set/Add）更新指标，
// 禁止 import prometheus 直接摸底层 collector——将来若更换指标库（如改用
// OpenTelemetry metrics），只需重写本文件的实现，业务代码零改动。
//
// 为什么每个实例持有私有 Registry 而不用 prometheus.DefaultRegisterer：
//  1. 单测隔离：每个测试自建实例，计数互不叠加，断言确定；
//  2. 不污染宿主进程：若宿主（或第三方库）已向默认 registry 注册同名指标，
//     全局注册会 panic；本包指标独立成岛，装配时也可整体选择是否暴露。
type BusinessMetrics struct {
	registry *prometheus.Registry

	httpRequests   *prometheus.CounterVec
	httpDuration   *prometheus.HistogramVec
	mediaBytes     *prometheus.CounterVec
	uploads        *prometheus.CounterVec
	uploadBytes    prometheus.Counter
	sseConnections prometheus.Gauge
	scanDuration   prometheus.Gauge
	libraryFiles   *prometheus.GaugeVec
	thumbQueue     prometheus.Gauge
	trashItems     prometheus.Gauge
	trashBytes     prometheus.Gauge
}

// Default 是进程级默认实例，供 httpapi 接线 /metrics 时使用；
// 测试请用 NewBusinessMetrics() 自建实例。
var Default = NewBusinessMetrics()

// NewBusinessMetrics 创建一套独立注册的业务指标。
func NewBusinessMetrics() *BusinessMetrics {
	reg := prometheus.NewRegistry()
	f := promauto.With(reg)

	// buckets 为什么取这组值：内网服务（千兆 LAN/NAS 直连）的元数据 API
	// 绝大多数落在 5ms~100ms；缩略图批量与视频直链受磁盘/带宽限制到秒级
	//（512MB @ 千兆 ≈ 4.3s）；顶部 30s/60s 兜住隧道外网与慢速 WiFi 的长尾，
	// 避免超慢请求全落到 +Inf 无法分位。
	durationBuckets := []float64{.005, .01, .025, .05, .1, .25, .5, 1, 2.5, 5, 10, 30, 60}

	// label 基数说明：endpoint 传路由模板（如 "/api/v1/media/{id}"）而非
	// 实际 URL，code 是有限状态码集合，method 是 HTTP 动词——均为低基数，
	// 不会撑爆时间序列内存。
	return &BusinessMetrics{
		registry: reg,
		httpRequests: f.NewCounterVec(prometheus.CounterOpts{
			Name: "http_requests_total",
			Help: "HTTP 请求总数，按 端点(路由模板)×方法×状态码 分维（QPS 由它 rate 而来）",
		}, []string{"endpoint", "method", "code"}),
		httpDuration: f.NewHistogramVec(prometheus.HistogramOpts{
			Name:    "http_request_duration_seconds",
			Help:    "HTTP 请求耗时直方图（p50/p95/p99），buckets 见 metrics.go 注释",
			Buckets: durationBuckets,
		}, []string{"endpoint"}),
		mediaBytes: f.NewCounterVec(prometheus.CounterOpts{
			Name: "media_bytes_total",
			Help: "媒体直链累计输出字节数（原图/缩略图/视频），用户最关心的流量指标",
		}, []string{"kind"}),
		uploads: f.NewCounterVec(prometheus.CounterOpts{
			Name: "upload_total",
			Help: "上传处理总数（成功/失败）",
		}, []string{"result"}),
		uploadBytes: f.NewCounter(prometheus.CounterOpts{
			Name: "upload_bytes_total",
			Help: "上传累计接收字节数（成功与失败都计入，用于评估带宽压力）",
		}),
		sseConnections: f.NewGauge(prometheus.GaugeOpts{
			Name: "sse_connections",
			Help: "当前活跃 SSE 连接数（在线客户端的近似值）",
		}),
		scanDuration: f.NewGauge(prometheus.GaugeOpts{
			Name: "scan_duration_seconds",
			Help: "上次全量库扫描耗时（秒）",
		}),
		libraryFiles: f.NewGaugeVec(prometheus.GaugeOpts{
			Name: "library_files",
			Help: "库内文件数（图片/视频）",
		}, []string{"type"}),
		thumbQueue: f.NewGauge(prometheus.GaugeOpts{
			Name: "thumb_queue_depth",
			Help: "待生成缩略图的队列深度",
		}),
		trashItems: f.NewGauge(prometheus.GaugeOpts{
			Name: "trash_items",
			Help: "回收站当前条目数",
		}),
		trashBytes: f.NewGauge(prometheus.GaugeOpts{
			Name: "trash_bytes",
			Help: "回收站当前占用字节数",
		}),
	}
}

// Registry 暴露底层注册表（仅供 Gather/测试/装配层桥接，业务代码禁止经它改指标）。
func (m *BusinessMetrics) Registry() *prometheus.Registry {
	return m.registry
}

// Handler 返回 Prometheus 文本格式的输出 handler（供 /metrics 接线）。
// promhttp.HandlerFor 内部即 Gather + expfmt 文本编码，是官方标准用法，
// 不直接复用 promhttp.Handler()（那个绑定默认 registry，与私有 Registry 策略冲突）。
func (m *BusinessMetrics) Handler() http.HandlerFunc {
	return promhttp.HandlerFor(m.registry, promhttp.HandlerOpts{}).ServeHTTP
}

// IncHTTPRequest 按 端点×方法×状态码 累加请求计数。endpoint 传路由模板。
func (m *BusinessMetrics) IncHTTPRequest(endpoint, method string, code int) {
	m.httpRequests.WithLabelValues(endpoint, method, itoa(code)).Inc()
}

// ObserveHTTPRequestDuration 记录一次请求耗时（秒）。
func (m *BusinessMetrics) ObserveHTTPRequestDuration(endpoint string, seconds float64) {
	m.httpDuration.WithLabelValues(endpoint).Observe(seconds)
}

// AddMediaBytes 累加媒体直链输出字节数。
func (m *BusinessMetrics) AddMediaBytes(kind MediaKind, bytes float64) {
	m.mediaBytes.WithLabelValues(string(kind)).Add(bytes)
}

// IncUpload 累加一次上传结果。
func (m *BusinessMetrics) IncUpload(result UploadResult) {
	m.uploads.WithLabelValues(string(result)).Inc()
}

// AddUploadBytes 累加上传接收字节数。
func (m *BusinessMetrics) AddUploadBytes(bytes float64) {
	m.uploadBytes.Add(bytes)
}

// SetSSEConnections 设置当前 SSE 连接数。
func (m *BusinessMetrics) SetSSEConnections(n int64) {
	m.sseConnections.Set(float64(n))
}

// SetScanDuration 设置上次全量扫描耗时（秒）。
func (m *BusinessMetrics) SetScanDuration(seconds float64) {
	m.scanDuration.Set(seconds)
}

// SetLibraryFiles 设置库内某类型文件数。
func (m *BusinessMetrics) SetLibraryFiles(fileType FileType, n int64) {
	m.libraryFiles.WithLabelValues(string(fileType)).Set(float64(n))
}

// SetThumbQueueDepth 设置缩略图待生成队列深度。
func (m *BusinessMetrics) SetThumbQueueDepth(n int64) {
	m.thumbQueue.Set(float64(n))
}

// SetTrashItems 设置回收站条目数。
func (m *BusinessMetrics) SetTrashItems(n int64) {
	m.trashItems.Set(float64(n))
}

// SetTrashBytes 设置回收站占用字节数。
func (m *BusinessMetrics) SetTrashBytes(n int64) {
	m.trashBytes.Set(float64(n))
}

// itoa 只是 strconv.Itoa 的本地别名，统一 code label 的字符串化入口。
func itoa(code int) string {
	return strconv.Itoa(code)
}
