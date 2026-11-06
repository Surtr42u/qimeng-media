package research

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// TestSidecarClientHealthAndLens：Bearer、multipart 字段名、snake_case 字段
// 归一与 links 过滤（契约见 tools/research_sidecar/README.md）。
func TestSidecarClientHealthAndLens(t *testing.T) {
	var authHeader string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		authHeader = r.Header.Get("Authorization")
		switch r.URL.Path {
		case "/healthz":
			w.WriteHeader(http.StatusOK)
			_, _ = io.WriteString(w, `{"ok":true,"ready":true}`)
		case "/lens":
			if err := r.ParseMultipartForm(1 << 20); err != nil {
				t.Errorf("sidecar 收到非 multipart: %v", err)
			}
			file, header, err := r.FormFile("file")
			if err != nil {
				t.Errorf("缺少 file 字段: %v", err)
			} else {
				_ = file.Close()
				if header.Filename != "frame.png" {
					t.Errorf("filename = %q, 期望 frame.png", header.Filename)
				}
			}
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{
  "ok": true,
  "status": "ok",
  "frame_sha256": "ignored",
  "text_sha256": "abc",
  "text_len": 42,
  "fetched_at": "2026-11-06T12:00:00Z",
  "elapsed_ms": 17500,
  "topTitles": [{"title": "Some Title", "domain": "example.com"}],
  "topDomains": ["example.com", ""],
  "cites": [{"title": "cite", "url": "https://example.com/c?token=secret", "domain": "example.com"}],
  "links": [{"url": "https://example.com/link", "domain": "example.com", "title": "link"}, {"url": ""}],
  "result_url": "https://lens.example.com/result"
}`)
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()

	client, err := NewSidecarClient(server.URL, "synthetic-token")
	if err != nil {
		t.Fatalf("构造 sidecar 客户端失败: %v", err)
	}
	if !client.Health(context.Background()) {
		t.Fatal("/healthz ready=true 应视为可用")
	}
	if authHeader != "Bearer synthetic-token" {
		t.Fatalf("Authorization = %q", authHeader)
	}

	image := []byte("synthetic-image-bytes")
	result, err := client.Lens(context.Background(), image, "image/png")
	if err != nil {
		t.Fatalf("Lens 失败: %v", err)
	}
	sum := sha256.Sum256(image)
	if result.FrameSHA256 != hex.EncodeToString(sum[:]) {
		t.Errorf("FrameSHA256 = %q, 期望本地复算值", result.FrameSHA256)
	}
	if result.Status != LensStatusOk || result.TextSHA256 != "abc" || result.ElapsedMs != 17500 {
		t.Errorf("Lens 结果 = %+v", result)
	}
	if result.FetchedAt == nil || !result.FetchedAt.Equal(time.Date(2026, 11, 6, 12, 0, 0, 0, time.UTC)) {
		t.Errorf("FetchedAt = %v", result.FetchedAt)
	}
	if len(result.TopTitles) != 1 || result.TopTitles[0].Title != "Some Title" {
		t.Errorf("TopTitles = %+v", result.TopTitles)
	}
	if len(result.TopDomains) != 1 || result.TopDomains[0] != "example.com" {
		t.Errorf("TopDomains = %+v", result.TopDomains)
	}
	if len(result.Cites) != 1 || result.ResultURL != "https://lens.example.com/result" {
		t.Errorf("Cites/ResultURL = %+v %q", result.Cites, result.ResultURL)
	}
	// links 是 best-effort：空 URL 被过滤，合法外链透传。
	if len(result.Links) != 1 || result.Links[0].URL != "https://example.com/link" {
		t.Errorf("Links = %+v", result.Links)
	}
}

// TestSidecarClientRejectsNonLoopback：sidecar 凭据只发给本机回环。
func TestSidecarClientRejectsNonLoopback(t *testing.T) {
	for _, endpoint := range []string{
		"http://example.com:8080",
		"https://127.0.0.1:8080",
		"http://192.168.1.5:8080",
	} {
		if _, err := NewSidecarClient(endpoint, "token"); err == nil {
			t.Errorf("endpoint %q 应被拒绝", endpoint)
		}
	}
	if _, err := NewSidecarClient("http://127.0.0.1:8080", ""); err == nil {
		t.Fatal("空 token 应被拒绝")
	}
}

// TestSidecarClientStatusMapping：HTTP 状态 + sidecar status/error.code 到公开
// 哨兵/结果状态的映射。
func TestSidecarClientStatusMapping(t *testing.T) {
	cases := []struct {
		name   string
		status int
		body   string
		want   LensStatus
		err    error
	}{
		{"upload_rejected_422", http.StatusUnprocessableEntity, `{"ok":false,"status":"error","error":{"code":"UPLOAD_REJECTED"}}`, LensStatusUploadRejected, nil},
		{"input_invalid_400", http.StatusBadRequest, `{"ok":false,"status":"error","error":{"code":"INPUT_INVALID"}}`, LensStatusUploadRejected, nil},
		{"blocked_bot_503", http.StatusServiceUnavailable, `{"ok":false,"status":"blocked_bot","error":{"code":"BLOCKED_BOT","message":"captcha"}}`, LensStatusBlocked, nil},
		{"unavailable_503", http.StatusServiceUnavailable, `{"ok":false,"status":"error"}`, "", ErrSidecarUnavailable},
		{"timeout_504", http.StatusGatewayTimeout, `{"ok":false,"status":"timeout","error":{"code":"TIMEOUT"}}`, LensStatusTimeout, nil},
		{"deadline_504", http.StatusGatewayTimeout, "", "", ErrSidecarTimeout},
		{"busy_429", http.StatusTooManyRequests, "", "", ErrSidecarBusy},
		{"no_result_502", http.StatusBadGateway, "", "", ErrSidecarResponse},
		{"unauthorized", http.StatusUnauthorized, "", "", ErrSidecarResponse},
		{"server_error", http.StatusInternalServerError, "", "", ErrSidecarResponse},
		{"bad_json", http.StatusOK, "not json", "", ErrSidecarResponse},
		{"unknown_status", http.StatusOK, `{"ok":true,"status":"weird"}`, "", ErrSidecarResponse},
		{"error_busy_200", http.StatusOK, `{"ok":false,"status":"error","error":{"code":"BUSY"}}`, "", ErrSidecarBusy},
		{"blocked_200", http.StatusOK, `{"ok":false,"status":"blocked_bot","error":{"code":"BLOCKED_BOT"}}`, LensStatusBlocked, nil},
		{"timeout_200", http.StatusOK, `{"ok":false,"status":"timeout","error":{"code":"TIMEOUT"}}`, LensStatusTimeout, nil},
	}
	for _, c := range cases {
		server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(c.status)
			_, _ = io.WriteString(w, c.body)
		}))
		client, err := NewSidecarClient(server.URL, "token")
		if err != nil {
			server.Close()
			t.Fatalf("%s: 构造客户端失败: %v", c.name, err)
		}
		result, err := client.Lens(context.Background(), []byte("img"), "image/jpeg")
		server.Close()
		if c.err != nil {
			if !errors.Is(err, c.err) {
				t.Errorf("%s: err = %v, 期望 %v", c.name, err, c.err)
			}
			continue
		}
		if err != nil {
			t.Errorf("%s: 不应返回 error: %v", c.name, err)
			continue
		}
		if c.want != "" && result.Status != c.want {
			t.Errorf("%s: status = %q, 期望 %q", c.name, result.Status, c.want)
		}
		if c.want != LensStatusOk && result.Err == nil {
			t.Errorf("%s: 非 ok 状态应带 error", c.name)
		}
	}
}

// TestSidecarClientTimeout：网关自己的上下文超时映射为 ErrSidecarTimeout。
func TestSidecarClientTimeout(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(200 * time.Millisecond)
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, `{"ok":true,"status":"ok"}`)
	}))
	defer server.Close()
	client, err := NewSidecarClient(server.URL, "token")
	if err != nil {
		t.Fatalf("构造客户端失败: %v", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
	defer cancel()
	if _, err := client.Lens(ctx, []byte("img"), "image/png"); !errors.Is(err, ErrSidecarTimeout) {
		t.Fatalf("err = %v, 期望 ErrSidecarTimeout", err)
	}
}

// TestSidecarHealthFailure：非 2xx、ready=false 都视为不可用；nil 客户端安全。
func TestSidecarHealthFailure(t *testing.T) {
	failing := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer failing.Close()
	client, err := NewSidecarClient(failing.URL, "token")
	if err != nil {
		t.Fatalf("构造客户端失败: %v", err)
	}
	if client.Health(context.Background()) {
		t.Fatal("500 应视为不可用")
	}

	notReady := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, `{"ok":true,"ready":false}`)
	}))
	defer notReady.Close()
	client, err = NewSidecarClient(notReady.URL, "token")
	if err != nil {
		t.Fatalf("构造客户端失败: %v", err)
	}
	if client.Health(context.Background()) {
		t.Fatal("ready=false 应视为不可用")
	}
	if (*SidecarClient)(nil).Configured() {
		t.Fatal("nil 客户端不应视为已配置")
	}
}
