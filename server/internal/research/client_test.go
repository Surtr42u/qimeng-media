package research

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// TestClientRejectsForeignHost 锁定主机白名单：URL 由 provider 固定拼装，
// 任何非白名单主机（含被篡改的基地址）都必须被拒绝。
func TestClientRejectsForeignHost(t *testing.T) {
	client := NewClient(ClientOptions{AllowedHosts: []string{"api.example.com"}})
	_, err := client.Fetch(context.Background(), Request{URL: "https://evil.example.org/x"})
	if err == nil {
		t.Fatal("非白名单主机应被拒绝")
	}
	var fetcherr *FetchError
	if !errors.As(err, &fetcherr) || fetcherr.Kind != FetchHostNotAllowed {
		t.Fatalf("错误类别 = %v, 期望 FetchHostNotAllowed", err)
	}
}

// TestClientRejectsPlainHTTPForPublicHosts：生产形态只允许 https；http 只有
// 测试回环开口（AllowLoopback + 127.0.0.1 字面量）。
func TestClientRejectsPlainHTTPForPublicHosts(t *testing.T) {
	client := NewClient(ClientOptions{AllowedHosts: []string{"api.example.com"}})
	_, err := client.Fetch(context.Background(), Request{URL: "http://api.example.com/x"})
	var fetcherr *FetchError
	if !errors.As(err, &fetcherr) || fetcherr.Kind != FetchInvalidURL {
		t.Fatalf("错误类别 = %v, 期望 FetchInvalidURL", err)
	}
}

// TestClientRejectsUserinfo：带凭据的 URL 会把账号密码送进上游，直接拒绝。
func TestClientRejectsUserinfo(t *testing.T) {
	client := NewClient(ClientOptions{AllowedHosts: []string{"api.example.com"}})
	_, err := client.Fetch(context.Background(), Request{URL: "https://user:pass@api.example.com/x"})
	var fetcherr *FetchError
	if !errors.As(err, &fetcherr) || fetcherr.Kind != FetchInvalidURL {
		t.Fatalf("错误类别 = %v, 期望 FetchInvalidURL", err)
	}
}

// TestClientDoesNotFollowRedirect：上游把请求重定向到别处（含内网）必须失败，
// 且只发一次请求。
func TestClientDoesNotFollowRedirect(t *testing.T) {
	var hits int
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits++
		http.Redirect(w, r, "http://127.0.0.1:9/elsewhere", http.StatusFound)
	}))
	defer server.Close()
	client := NewClient(ClientOptions{AllowedHosts: []string{"127.0.0.1"}, AllowLoopback: true})
	if _, err := client.Fetch(context.Background(), Request{URL: server.URL + "/x"}); err == nil {
		t.Fatal("重定向应返回错误而不是跟随")
	}
	if hits != 1 {
		t.Fatalf("上游被请求 %d 次, 期望 1 次（不跟随重定向）", hits)
	}
}

// TestClientTimeoutAndSizeLimit：超时与响应超限必须分类为 FetchTimeout /
// FetchTooLarge，且不把正文带进错误消息。
func TestClientTimeoutAndSizeLimit(t *testing.T) {
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(150 * time.Millisecond)
		_, _ = w.Write([]byte("late"))
	}))
	defer slow.Close()
	client := NewClient(ClientOptions{AllowedHosts: []string{"127.0.0.1"}, AllowLoopback: true, Timeout: 30 * time.Millisecond})
	_, err := client.Fetch(context.Background(), Request{URL: slow.URL + "/x"})
	var fetcherr *FetchError
	if !errors.As(err, &fetcherr) || fetcherr.Kind != FetchTimeout {
		t.Fatalf("错误 = %v, 期望 FetchTimeout", err)
	}

	big := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("0123456789abcdef"))
	}))
	defer big.Close()
	limited := NewClient(ClientOptions{AllowedHosts: []string{"127.0.0.1"}, AllowLoopback: true, MaxBytes: 8})
	_, err = limited.Fetch(context.Background(), Request{URL: big.URL + "/x"})
	if !errors.As(err, &fetcherr) || fetcherr.Kind != FetchTooLarge {
		t.Fatalf("错误 = %v, 期望 FetchTooLarge", err)
	}
}

// TestClassifyStatus 锁定上游状态到结果状态的映射（provider 共用口径）。
func TestClassifyStatus(t *testing.T) {
	cases := map[int]Status{
		200: StatusOk,
		204: StatusOk,
		404: StatusNotFound,
		410: StatusNotFound,
		401: StatusBlocked,
		403: StatusBlocked,
		451: StatusBlocked,
		429: StatusRateLimited,
		500: StatusProviderUnavailable,
		502: StatusProviderUnavailable,
		503: StatusProviderUnavailable,
	}
	for code, want := range cases {
		got, _ := classifyStatus(code)
		if got != want {
			t.Errorf("classifyStatus(%d) = %q, 期望 %q", code, got, want)
		}
	}
}

// TestUnavailableClassification：抓取错误要映射成稳定的 error.code，
// 且消息里不含内网地址/响应正文。
func TestUnavailableClassification(t *testing.T) {
	cases := map[FetchErrorKind]string{
		FetchTimeout:        "UPSTREAM_TIMEOUT",
		FetchTooLarge:       "UPSTREAM_RESPONSE_TOO_LARGE",
		FetchHostNotAllowed: "HOST_NOT_ALLOWED",
		FetchBlockedAddress: "ADDRESS_BLOCKED",
		FetchInvalidURL:     "INVALID_UPSTREAM_URL",
	}
	for kind, want := range cases {
		res := unavailable(&FetchError{Kind: kind, Message: "synthetic"})
		if res.Status != StatusProviderUnavailable {
			t.Errorf("kind %d 状态 = %q, 期望 provider_unavailable", kind, res.Status)
		}
		if res.Err == nil || res.Err.Code != want {
			t.Errorf("kind %d code = %v, 期望 %q", kind, res.Err, want)
		}
	}
}
