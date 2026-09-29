package httpapi

// 系统面板与 /metrics 端点测试：注入假采集函数/假 handler，锁定
// 鉴权（401）、未装配（503）、部分采集失败仍 200（sysmon 错误哲学）、
// 响应 JSON 的协议字段形状（含 perCore）。
// 不依赖媒体文件/扫描器/ffmpeg——系统端点只依赖鉴权体系与两个注入点。

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"qimeng-media/server/internal/config"
	"qimeng-media/server/internal/events"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
	"qimeng-media/server/internal/sysmon"
	"qimeng-media/server/internal/thumbnail"
)

// newSystemEnv 建系统端点最小环境并完成 setup 拿 Bearer token，
// 返回测试服务器与 token。cfgMods 按需调整服务端配置（如 devMode
// 两态用例改 AuthDevMode）；不传 = 全默认配置，既有用例零感知。
func newSystemEnv(t *testing.T, sysStatus func(context.Context) (sysmon.SystemStatus, error), metrics http.HandlerFunc, cfgMods ...func(*config.Config)) (*httptest.Server, string) {
	t.Helper()
	dataDir := t.TempDir()
	conn, err := store.Open(filepath.Join(dataDir, "test.db"))
	if err != nil {
		t.Fatalf("打开测试库失败: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	if err := store.Migrate(conn); err != nil {
		t.Fatalf("迁移失败: %v", err)
	}
	cfg := &config.Config{DataDir: dataDir}
	for _, mod := range cfgMods {
		mod(cfg)
	}
	apisrv, err := New(Deps{
		Conn: conn, Queries: db.New(conn), Bus: events.NewBus(nil, 0),
		Cfg:       cfg,
		Thumbs:    thumbnail.NewGenerator(dataDir, nil, thumbnail.Options{}),
		SysStatus: sysStatus, Metrics: metrics,
		MediaSecret: []byte("test-secret-0123456789abcdef0123456789"),
	})
	if err != nil {
		t.Fatalf("组装服务失败: %v", err)
	}
	ts := httptest.NewServer(apisrv.Handler())
	t.Cleanup(ts.Close)

	body := `{"password":"system-test-pass"}`
	resp, err := http.Post(ts.URL+"/api/v1/auth/setup", "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatalf("setup 请求失败: %v", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("setup 期望 201，得到 %d", resp.StatusCode)
	}
	var tok struct {
		Token string `json:"token"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&tok); err != nil || tok.Token == "" {
		t.Fatalf("setup 未返回 token: %v", err)
	}
	return ts, tok.Token
}

// fakeSystemStatus 是固定假快照：覆盖全部协议字段（含 perCore 三核），
// 字段形状断言以它为准。
func fakeSystemStatus() sysmon.SystemStatus {
	return sysmon.SystemStatus{
		CPUPercent:   12.5,
		PerCore:      []float64{10, 20, 7.5},
		MemUsedBytes: 100, MemTotalBytes: 200,
		Disks:      []sysmon.DiskUsage{{Mount: "/media", UsedBytes: 1, TotalBytes: 2}},
		NetRxBytes: 300, NetTxBytes: 400,
		UptimeSeconds: 5,
		Version:       "test-v1",
	}
}

func TestSystemStatusOK(t *testing.T) {
	ts, token := newSystemEnv(t, func(context.Context) (sysmon.SystemStatus, error) {
		return fakeSystemStatus(), nil
	}, nil)
	req, _ := http.NewRequest(http.MethodGet, ts.URL+"/api/v1/system/status", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("状态码 = %d, 期望 200", resp.StatusCode)
	}
	var got map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&got); err != nil {
		t.Fatalf("解析响应失败: %v", err)
	}
	// 字段名与 api/openapi.yaml SystemStatus 逐一对齐；perCore 是本次
	// 接线新增字段，形状（数组、逐核数值）在此锁定。
	if v, ok := got["cpuPercent"].(float64); !ok || v != 12.5 {
		t.Errorf("cpuPercent = %v, 期望 12.5", got["cpuPercent"])
	}
	perCore, ok := got["perCore"].([]any)
	if !ok || len(perCore) != 3 || perCore[0].(float64) != 10 || perCore[2].(float64) != 7.5 {
		t.Errorf("perCore = %v, 期望 [10 20 7.5]", got["perCore"])
	}
	disks, ok := got["disks"].([]any)
	if !ok || len(disks) != 1 || disks[0].(map[string]any)["mount"] != "/media" {
		t.Errorf("disks = %v, 期望一项 mount=/media", got["disks"])
	}
	if got["version"] != "test-v1" || got["uptimeSeconds"].(float64) != 5 {
		t.Errorf("version/uptimeSeconds = %v/%v, 期望 test-v1/5", got["version"], got["uptimeSeconds"])
	}
}

// TestSystemStatusDevMode：devMode 布尔透出服务端 cfg.AuthDevMode
// （开/关两态都必须出现在响应里，不能只透 true）——Web 维护页
// 「开发模式未关」提醒条的数据源。
func TestSystemStatusDevMode(t *testing.T) {
	for _, tc := range []struct {
		name    string
		devMode bool
	}{
		{"开启", true},
		{"关闭", false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			ts, token := newSystemEnv(t, func(context.Context) (sysmon.SystemStatus, error) {
				return fakeSystemStatus(), nil
			}, nil, func(cfg *config.Config) { cfg.AuthDevMode = tc.devMode })
			req, err := http.NewRequest(http.MethodGet, ts.URL+"/api/v1/system/status", nil)
			if err != nil {
				t.Fatalf("构造请求失败: %v", err)
			}
			req.Header.Set("Authorization", "Bearer "+token)
			resp, err := http.DefaultClient.Do(req)
			if err != nil {
				t.Fatalf("请求失败: %v", err)
			}
			defer func() { _ = resp.Body.Close() }()
			if resp.StatusCode != http.StatusOK {
				t.Fatalf("状态码 = %d, 期望 200", resp.StatusCode)
			}
			var got struct {
				DevMode *bool `json:"devMode"`
			}
			if err := json.NewDecoder(resp.Body).Decode(&got); err != nil {
				t.Fatalf("解析响应失败: %v", err)
			}
			if got.DevMode == nil || *got.DevMode != tc.devMode {
				t.Errorf("devMode = %v, 期望 %v（指针缺失同样算失败：字段必须透出）", got.DevMode, tc.devMode)
			}
		})
	}
}

// TestSystemStatusPartialError 锁定"部分采集失败仍 200"契约：
// sysmon 快照的聚合错误（单项失败）不应让面板整体 500。
func TestSystemStatusPartialError(t *testing.T) {
	ts, token := newSystemEnv(t, func(context.Context) (sysmon.SystemStatus, error) {
		return fakeSystemStatus(), errors.New("采集磁盘 /media: 失联")
	}, nil)
	req, _ := http.NewRequest(http.MethodGet, ts.URL+"/api/v1/system/status", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("部分失败仍应 200，得到 %d", resp.StatusCode)
	}
}

// TestSystemStatusUnauthorized：系统面板走 Bearer（协议未标 security: []）。
func TestSystemStatusUnauthorized(t *testing.T) {
	ts, _ := newSystemEnv(t, func(context.Context) (sysmon.SystemStatus, error) {
		return fakeSystemStatus(), nil
	}, nil)
	resp, err := http.Get(ts.URL + "/api/v1/system/status")
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("无 token 期望 401，得到 %d", resp.StatusCode)
	}
}

// TestSystemStatusUnavailable：未装配（SysStatus nil）显式 503 而非 panic。
func TestSystemStatusUnavailable(t *testing.T) {
	ts, token := newSystemEnv(t, nil, nil)
	req, _ := http.NewRequest(http.MethodGet, ts.URL+"/api/v1/system/status", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("未装配期望 503，得到 %d", resp.StatusCode)
	}
}

// TestMetricsOK：/metrics 透传注入的 handler 输出（内容零加工）。
func TestMetricsOK(t *testing.T) {
	ts, token := newSystemEnv(t, nil, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("http_requests_total 42\n"))
	}))
	req, _ := http.NewRequest(http.MethodGet, ts.URL+"/metrics", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("状态码 = %d, 期望 200", resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("读响应失败: %v", err)
	}
	if !strings.Contains(string(body), "http_requests_total 42") {
		t.Errorf("指标输出被篡改: %q", body)
	}
}

func TestMetricsUnauthorized(t *testing.T) {
	ts, _ := newSystemEnv(t, nil, http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte("x"))
	}))
	resp, err := http.Get(ts.URL + "/metrics")
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("无 token 期望 401，得到 %d", resp.StatusCode)
	}
}

func TestMetricsUnavailable(t *testing.T) {
	ts, token := newSystemEnv(t, nil, nil)
	req, _ := http.NewRequest(http.MethodGet, ts.URL+"/metrics", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusServiceUnavailable {
		t.Fatalf("未装配期望 503，得到 %d", resp.StatusCode)
	}
}
