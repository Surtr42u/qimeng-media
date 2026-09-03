package httpapi

// clientlogs.go（POST/GET /api/v1/client-logs）测试：
// 批量存入 + GET 新→旧序、环形覆盖（250 条后剩最新 200）、
// 批量/单条校验 400、无 token 401。

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// batchJSON 把条目序列化成请求体。
func batchJSON(t *testing.T, entries ...gen.ClientLogEntry) string {
	t.Helper()
	raw, err := json.Marshal(gen.ClientLogBatch{Events: entries})
	if err != nil {
		t.Fatalf("序列化批量请求失败: %v", err)
	}
	return string(raw)
}

// postLogs 带 token 上报一批，返回响应（调用方 close）。
func (e *testEnv) postLogs(t *testing.T, body string) *http.Response {
	t.Helper()
	return e.do(t, http.MethodPost, "/api/v1/client-logs", body)
}

// getLogs 带 token 读异常列表。
func getLogs(t *testing.T, env *testEnv) []gen.ClientLogEntry {
	t.Helper()
	resp := env.do(t, http.MethodGet, "/api/v1/client-logs", "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("GET /client-logs 期望 200，得到 %d", resp.StatusCode)
	}
	var page gen.ClientLogPage
	if err := json.NewDecoder(resp.Body).Decode(&page); err != nil {
		t.Fatalf("解析异常列表失败: %v", err)
	}
	return page.Items
}

// TestClientLogsGetEmpty：空库 GET 响应体必须含 "items":[] 而非 null——
// 生成类型 nil 切片会被序列化成 JSON null，前端 `const { data = [] }` 的
// 默认值只救 undefined 不救 null，会直接 TypeError 崩页（P1 修复锁行为）。
func TestClientLogsGetEmpty(t *testing.T) {
	env := newTestEnv(t)
	resp := env.do(t, http.MethodGet, "/api/v1/client-logs", "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("空库 GET 期望 200，得到 %d", resp.StatusCode)
	}
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("读响应体失败: %v", err)
	}
	if !strings.Contains(string(raw), `"items":[]`) {
		t.Fatalf(`空库 GET 响应体应含 "items":[]，得到 %s`, raw)
	}
}

// TestClientLogsCorruptKvSelfHeals：kv 值损坏（坏 JSON）→ GET 按空表返回
// （不 500），POST 后从空表追加覆盖写回自愈（与 config 侧回落口径一致）。
func TestClientLogsCorruptKvSelfHeals(t *testing.T) {
	env := newTestEnv(t)
	if err := env.q.UpsertSetting(context.Background(), db.UpsertSettingParams{
		Key:       authoring.SettingKeyClientLogs,
		Value:     `{"broken":`,
		UpdatedAt: store.FormatTimestamp(time.Now()),
	}); err != nil {
		t.Fatalf("写入损坏 kv 失败: %v", err)
	}
	resp := env.do(t, http.MethodGet, "/api/v1/client-logs", "")
	raw, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK || !strings.Contains(string(raw), `"items":[]`) {
		t.Fatalf("损坏 kv GET 期望 200+空表，得到 %d %s", resp.StatusCode, raw)
	}
	resp = env.postLogs(t, batchJSON(t,
		gen.ClientLogEntry{Ts: 1, Level: gen.ClientLogEntryLevelInfo, Message: "自愈"}))
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("损坏后 POST 期望 204，得到 %d", resp.StatusCode)
	}
	items := getLogs(t, env)
	if len(items) != 1 || items[0].Message != "自愈" {
		t.Fatalf("自愈后 GET = %+v, want 单条「自愈」", items)
	}
}

// TestClientLogsPostAndGet：两批上报后 GET 按新→旧返回，字段完整回读。
func TestClientLogsPostAndGet(t *testing.T) {
	env := newTestEnv(t)
	stack := "TypeError: x is not a function\n    at foo (bar.ts:1:1)"
	page := "/app/asset/123"
	resp := env.postLogs(t, batchJSON(t,
		gen.ClientLogEntry{Ts: 1000, Level: gen.ClientLogEntryLevelWarn, Message: "第一批-警告"},
		gen.ClientLogEntry{Ts: 2000, Level: gen.ClientLogEntryLevelError, Message: "第一批-崩溃", Stack: &stack, Page: &page},
	))
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("POST /client-logs 期望 204，得到 %d", resp.StatusCode)
	}
	resp = env.postLogs(t, batchJSON(t, gen.ClientLogEntry{Ts: 3000, Level: gen.ClientLogEntryLevelInfo, Message: "第二批-信息"}))
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("第二次 POST 期望 204，得到 %d", resp.StatusCode)
	}

	items := getLogs(t, env)
	if len(items) != 3 {
		t.Fatalf("GET 条数 = %d, want 3", len(items))
	}
	// 新→旧：最后上报的信息条排第一
	if items[0].Message != "第二批-信息" || items[0].Level != gen.ClientLogEntryLevelInfo {
		t.Errorf("items[0] = %+v, want 第二批-信息/info", items[0])
	}
	// 字段完整回读（含可选 stack/page）
	crash := items[1]
	if crash.Message != "第一批-崩溃" || crash.Ts != 2000 ||
		crash.Stack == nil || *crash.Stack != stack ||
		crash.Page == nil || *crash.Page != page {
		t.Errorf("可选字段回读不一致: %+v", crash)
	}
	if items[2].Message != "第一批-警告" || items[2].Level != gen.ClientLogEntryLevelWarn {
		t.Errorf("items[2] = %+v, want 第一批-警告/warn", items[2])
	}
}

// TestClientLogsRingBuffer：累计 250 条（5 批 × 50）→ 只剩最新 200 条，
// items[0]=第 250 条、items[199]=第 51 条（最旧被丢弃的是 1~50）。
func TestClientLogsRingBuffer(t *testing.T) {
	env := newTestEnv(t)
	for start := 1; start <= 250; start += 50 {
		batch := make([]gen.ClientLogEntry, 0, 50)
		for i := start; i < start+50; i++ {
			batch = append(batch, gen.ClientLogEntry{
				Ts: int64(i), Level: gen.ClientLogEntryLevelError, Message: fmt.Sprintf("m%d", i),
			})
		}
		resp := env.postLogs(t, batchJSON(t, batch...))
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusNoContent {
			t.Fatalf("第 %d 批 POST 期望 204，得到 %d", start, resp.StatusCode)
		}
	}
	items := getLogs(t, env)
	if len(items) != 200 {
		t.Fatalf("环形缓冲条数 = %d, want 200", len(items))
	}
	if items[0].Message != "m250" {
		t.Errorf("items[0] = %s, want m250（最新）", items[0].Message)
	}
	if items[199].Message != "m51" {
		t.Errorf("items[199] = %s, want m51（最旧保留边界）", items[199].Message)
	}
}

// TestClientLogsValidation：空批量/超 50 条/非法 level/message 超 2000 字 → 400；
// message 恰 2000 字放行（边界）。
func TestClientLogsValidation(t *testing.T) {
	env := newTestEnv(t)
	base := func(level gen.ClientLogEntryLevel, msg string) string {
		return batchJSON(t, gen.ClientLogEntry{Ts: 1, Level: level, Message: msg})
	}

	// 空批量（条数 0）
	resp := env.postLogs(t, `{"events":[]}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("空批量期望 400，得到 %d", resp.StatusCode)
	}
	// 51 条
	over := make([]gen.ClientLogEntry, 0, 51)
	for i := 0; i < 51; i++ {
		over = append(over, gen.ClientLogEntry{Ts: int64(i), Level: gen.ClientLogEntryLevelInfo, Message: "x"})
	}
	resp = env.postLogs(t, batchJSON(t, over...))
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("51 条批量期望 400，得到 %d", resp.StatusCode)
	}
	// 非法 level
	resp = env.postLogs(t, base("debug", "x"))
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("非法 level 期望 400，得到 %d", resp.StatusCode)
	}
	// message 2001 字（rune 计数）→ 400；2000 字 → 204（不截断、边界放行）
	resp = env.postLogs(t, base(gen.ClientLogEntryLevelError, strings.Repeat("字", 2001)))
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("message 2001 字期望 400，得到 %d", resp.StatusCode)
	}
	resp = env.postLogs(t, base(gen.ClientLogEntryLevelError, strings.Repeat("字", 2000)))
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Errorf("message 2000 字（边界）期望 204，得到 %d", resp.StatusCode)
	}
}

// TestClientLogsUnauthorized：无 token POST/GET 均 401。
func TestClientLogsUnauthorized(t *testing.T) {
	env := newTestEnv(t)
	resp, err := http.Post(env.ts.URL+"/api/v1/client-logs", "application/json",
		strings.NewReader(`{"events":[{"ts":1,"level":"error","message":"x"}]}`))
	if err != nil {
		t.Fatalf("POST 请求失败: %v", err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("无 token POST 期望 401，得到 %d", resp.StatusCode)
	}
	resp2, err := http.Get(env.ts.URL + "/api/v1/client-logs")
	if err != nil {
		t.Fatalf("GET 请求失败: %v", err)
	}
	_ = resp2.Body.Close()
	if resp2.StatusCode != http.StatusUnauthorized {
		t.Fatalf("无 token GET 期望 401，得到 %d", resp2.StatusCode)
	}
}
