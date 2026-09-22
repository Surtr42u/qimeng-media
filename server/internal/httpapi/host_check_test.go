package httpapi

// Host 校验（DNS rebinding 防御，docs/SECURITY.md「Host 校验」）测试：
// 判定函数表驱动 + 中间件端到端（伪 Host 请求走真实鉴权链外的最外层）。

import (
	"net/http"
	"testing"

	"qimeng-media/server/internal/config"
)

// TestHostAllowed 判定函数表驱动：IP 直连/localhost/白名单域名放行，
// 其他域名（含"IP 前缀 + 攻击者后缀"的伪 IP 域名）拒绝。
func TestHostAllowed(t *testing.T) {
	s := &Server{cfg: &config.Config{TrustedHosts: []string{"nas.magic.ts.net"}}}
	cases := []struct {
		host string
		want bool
	}{
		{"127.0.0.1", true},
		{"127.0.0.1:8420", true},
		{"192.168.1.5:8420", true},
		{"[::1]:8420", true},       // 带端口的 IPv6 字面量
		{"::1", true},              // 裸 IPv6（SplitHostPort 失败，ParseIP 兜住）
		{"fe80::1%eth0", false},    // 带 zone 的链路本地地址不是普通 IP 字面量形态
		{"localhost", true},        // 本机名
		{"LOCALHOST:8420", true},   // 大小写不敏感
		{"nas.magic.ts.net", true}, // trusted_hosts 白名单
		{"NAS.Magic.TS.Net", true}, // 白名单大小写不敏感
		{"evil.example.com", false},
		{"evil.example.com:8420", false},
		{"192.168.1.5.evil.com", false}, // 伪 IP 域名（攻击者把 IP 段做子域）
		{"localhost.evil.com", false},   // localhost 前缀伪装
		{"", false},                     // 空串本身不走本函数（中间件层放行空 Host）
	}
	for _, c := range cases {
		if got := s.hostAllowed(c.host); got != c.want {
			t.Errorf("hostAllowed(%q) = %v, 期望 %v", c.host, got, c.want)
		}
	}
}

// TestHostCheckMiddleware 端到端：非白名单 Host 403；默认 IP 直连、
// localhost、配置白名单域名放行（以 /api/v1/healthz 免鉴权端点探活）。
func TestHostCheckMiddleware(t *testing.T) {
	env := newTestEnv(t)
	probe := func(host string) int {
		t.Helper()
		req, err := http.NewRequest(http.MethodGet, env.ts.URL+"/api/v1/healthz", nil)
		if err != nil {
			t.Fatalf("构造请求失败: %v", err)
		}
		if host != "" {
			req.Host = host // 覆写 Host 头（客户端仍拨 URL 的 127.0.0.1）
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatalf("请求失败: %v", err)
		}
		defer func() { _ = resp.Body.Close() }()
		return resp.StatusCode
	}

	if got := probe(""); got != http.StatusOK {
		t.Errorf("默认 Host（127.0.0.1:port）期望 200，得到 %d", got)
	}
	if got := probe("localhost"); got != http.StatusOK {
		t.Errorf("localhost 期望 200，得到 %d", got)
	}
	if got := probe("evil.example.com"); got != http.StatusForbidden {
		t.Errorf("非白名单域名期望 403，得到 %d", got)
	}
	env.cfg.TrustedHosts = append(env.cfg.TrustedHosts, "nas.magic.ts.net")
	if got := probe("nas.magic.ts.net"); got != http.StatusOK {
		t.Errorf("trusted_hosts 白名单域名期望 200，得到 %d", got)
	}
}
