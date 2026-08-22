package sysmon

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/common/expfmt"
)

// gatherText 把实例 Registry 的全部指标编码成 Prometheus 文本（Gather + expfmt），
// 与 /metrics 输出同一条代码路径，验证"指标名 + label"最终对外可见。
func gatherText(t *testing.T, m *BusinessMetrics) string {
	t.Helper()
	mfs, err := m.Registry().Gather()
	if err != nil {
		t.Fatalf("Gather: %v", err)
	}
	var buf bytes.Buffer
	enc := expfmt.NewEncoder(&buf, expfmt.NewFormat(expfmt.TypeTextPlain))
	for _, mf := range mfs {
		if err := enc.Encode(mf); err != nil {
			t.Fatalf("编码 %s: %v", mf.GetName(), err)
		}
	}
	return buf.String()
}

// TestBusinessMetricsGatherText 全量埋点后 Gather 文本必须含预期指标名与 label 值。
func TestBusinessMetricsGatherText(t *testing.T) {
	m := NewBusinessMetrics()

	m.IncHTTPRequest("/api/v1/media", "GET", 200)
	m.ObserveHTTPRequestDuration("/api/v1/media", 0.03)
	m.AddMediaBytes(MediaVideo, 4096)
	m.IncUpload(UploadFail)
	m.AddUploadBytes(4096)
	m.SetSSEConnections(2)
	m.SetScanDuration(12.5)
	m.SetLibraryFiles(FileImage, 7)
	m.SetLibraryFiles(FileVideo, 3)
	m.SetThumbQueueDepth(9)
	m.SetTrashItems(4)
	m.SetTrashBytes(8192)

	text := gatherText(t, m)
	want := []string{
		"http_requests_total",
		`endpoint="/api/v1/media"`,
		`method="GET"`,
		`code="200"`,
		"http_request_duration_seconds",
		"media_bytes_total",
		`kind="video"`,
		"upload_total",
		`result="fail"`,
		"upload_bytes_total 4096",
		"sse_connections 2",
		"scan_duration_seconds 12.5",
		"library_files",
		`type="image"`,
		`type="video"`,
		"thumb_queue_depth 9",
		"trash_items 4",
		"trash_bytes 8192",
	}
	for _, w := range want {
		if !strings.Contains(text, w) {
			t.Errorf("Gather 文本缺少 %q", w)
		}
	}
}

// TestBusinessMetricsHandler Handler() 走 HTTP 输出：200、Prometheus 文本
// Content-Type、正文含已 Set 的 gauge 值。
func TestBusinessMetricsHandler(t *testing.T) {
	m := NewBusinessMetrics()
	m.SetSSEConnections(1)

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/metrics", nil)
	m.Handler()(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("status = %d, want 200", rec.Code)
	}
	if ct := rec.Header().Get("Content-Type"); !strings.HasPrefix(ct, "text/plain") {
		t.Errorf("Content-Type = %q, want text/plain...", ct)
	}
	if !strings.Contains(rec.Body.String(), "sse_connections 1") {
		t.Errorf("正文缺少 sse_connections 1, body:\n%s", rec.Body.String())
	}
}

// TestRegistryIsolation 实例指标绝不落入 prometheus 默认 registry——
// 默认 Gatherer 只应含 client_golang 预注册的 go_*/process_* 指标。
func TestRegistryIsolation(t *testing.T) {
	m := NewBusinessMetrics()
	m.IncHTTPRequest("/x", "GET", 200)
	m.SetSSEConnections(5)

	ours := []string{
		"http_requests_total", "http_request_duration_seconds", "media_bytes_total",
		"upload_total", "upload_bytes_total", "sse_connections", "scan_duration_seconds",
		"library_files", "thumb_queue_depth", "trash_items", "trash_bytes",
	}
	mfs, err := prometheus.DefaultGatherer.Gather()
	if err != nil {
		t.Fatalf("DefaultGatherer.Gather: %v", err)
	}
	for _, mf := range mfs {
		for _, name := range ours {
			if mf.GetName() == name {
				t.Errorf("指标 %s 泄漏进了默认 registry（违背单测隔离+不污染宿主的封装目标）", name)
			}
		}
	}
}

// TestDefaultInstance 包级 Default 可用（httpapi 接线时的单例入口）。
func TestDefaultInstance(t *testing.T) {
	if Default == nil {
		t.Fatal("Default 实例为 nil")
	}
	Default.IncHTTPRequest("/default-check", "GET", 200)
	if text := gatherText(t, Default); !strings.Contains(text, `endpoint="/default-check"`) {
		t.Errorf("Default 实例 Gather 文本缺少埋点")
	}
}
