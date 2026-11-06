package research

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"qimeng-media/server/internal/config"
)

// fakeSidecar 起一个 loopback 假 sidecar（/healthz + /lens），返回客户端。
func fakeSidecar(t *testing.T, healthStatus int) *SidecarClient {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/healthz":
			w.WriteHeader(healthStatus)
		case "/lens":
			if _, _, err := r.FormFile("file"); err != nil {
				t.Errorf("sidecar 未收到 file: %v", err)
			}
			w.Header().Set("Content-Type", "application/json")
			_, _ = io.WriteString(w, `{"ok":true,"status":"ok","text_sha256":"t","elapsed_ms":10,"topTitles":[{"title":"T","domain":"example.com"}],"links":[]}`)
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(server.Close)
	client, err := NewSidecarClient(server.URL, "synthetic-token")
	if err != nil {
		t.Fatalf("构造假 sidecar 客户端失败: %v", err)
	}
	return client
}

// newLensService 用注入的 sidecar 构造服务（研究默认启用）。
func newLensService(t *testing.T, sidecar *SidecarClient, tweak func(*config.ResearchConfig)) *Service {
	t.Helper()
	cfg := config.ResearchConfig{
		Enabled: true, LensPerHour: 20, LensPerDay: 100, ProviderPerMinute: 20,
		EvidenceDir: t.TempDir(),
	}
	if tweak != nil {
		tweak(&cfg)
	}
	svc, err := New(Options{
		Config: cfg, DataDir: t.TempDir(), Sidecar: sidecar,
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	if err != nil {
		t.Fatalf("构造研究服务失败: %v", err)
	}
	return svc
}

// TestServiceContextReportsProvidersAndSidecar：context 的 provider 矩阵、
// 限额与 sidecar 状态（含 pending）。
func TestServiceContextReportsProvidersAndSidecar(t *testing.T) {
	svc := newLensService(t, fakeSidecar(t, http.StatusOK), nil)
	info := svc.Context(context.Background())
	if !info.Enabled {
		t.Fatal("enabled 应为 true")
	}
	if len(info.Providers) != 7 {
		t.Fatalf("provider 数 = %d, 期望 7", len(info.Providers))
	}
	for _, provider := range info.Providers {
		if !provider.Enabled {
			t.Errorf("%s 未启用", provider.Name)
		}
		if provider.Limits.PerMinute != 20 || provider.Limits.PerDay != 0 {
			t.Errorf("%s 限额 = %+v", provider.Name, provider.Limits)
		}
	}
	if info.Providers[0].Name != NameFxTwitter || info.Providers[6].Name != NameBing {
		t.Errorf("provider 顺序 = %v", info.Providers)
	}
	if !info.Sidecar.Configured || !info.Sidecar.Available {
		t.Errorf("sidecar = %+v, 期望 configured+available", info.Sidecar)
	}
	if info.LensLimits.PerHour != 20 || info.LensLimits.PerDay != 100 {
		t.Errorf("Lens 限额 = %+v", info.LensLimits)
	}
	if info.FetchedAt.IsZero() {
		t.Error("FetchedAt 不应为零值")
	}
}

// TestServiceLensBudget：Lens 每日预算耗尽返回 ErrLensRateLimited。
func TestServiceLensBudget(t *testing.T) {
	sidecar := fakeSidecar(t, http.StatusOK)
	svc := newLensService(t, sidecar, func(c *config.ResearchConfig) {
		c.LensPerHour = 5
		c.LensPerDay = 1
	})
	ctx := context.Background()
	if _, err := svc.Lens(ctx, []byte("img"), "image/png"); err != nil {
		t.Fatalf("第一次 Lens 失败: %v", err)
	}
	if _, err := svc.Lens(ctx, []byte("img"), "image/png"); err != ErrLensRateLimited {
		t.Fatalf("第二次 err = %v, 期望 ErrLensRateLimited", err)
	}
}

// TestDisabledService：研究关闭时 context 可读，查询/Lens 显式禁用。
func TestDisabledService(t *testing.T) {
	svc := newLensService(t, fakeSidecar(t, http.StatusOK), func(c *config.ResearchConfig) { c.Enabled = false })
	if svc.Enabled() {
		t.Fatal("enabled 应为 false")
	}
	info := svc.Context(context.Background())
	if info.Enabled {
		t.Error("context.enabled 应为 false")
	}
	if info.Sidecar.Available {
		t.Error("关闭时不应探测 sidecar")
	}
	if _, err := svc.Query(context.Background(), NameFxTwitter, Query{Operation: OperationAuthor, Target: "a"}); err != ErrDisabled {
		t.Fatalf("查询 err = %v, 期望 ErrDisabled", err)
	}
	if _, err := svc.Lens(context.Background(), []byte("img"), "image/png"); err != ErrSidecarDisabled {
		t.Fatalf("Lens err = %v, 期望 ErrSidecarDisabled", err)
	}
}

// TestLensWithoutSidecar：启用研究但未配置 sidecar 时 Lens 返回禁用错误。
func TestLensWithoutSidecar(t *testing.T) {
	svc := newLensService(t, nil, nil)
	if svc.SidecarConfigured() {
		t.Fatal("未注入 sidecar 时不应视为已配置")
	}
	if _, err := svc.Lens(context.Background(), []byte("img"), "image/png"); err != ErrSidecarDisabled {
		t.Fatalf("err = %v, 期望 ErrSidecarDisabled", err)
	}
}

// TestTargetValidation：空串、超长与控制字符在 service 层拒绝。
func TestTargetValidation(t *testing.T) {
	svc := newLensService(t, fakeSidecar(t, http.StatusOK), nil)
	cases := []string{"", "   ", strings.Repeat("a", MaxTargetRunes+1), "bad\x00target"}
	for _, target := range cases {
		if _, err := svc.Query(context.Background(), NameBing, Query{Operation: OperationSearch, Target: target}); err != ErrInvalidTarget {
			t.Errorf("target %q err = %v, 期望 ErrInvalidTarget", target, err)
		}
	}
}
