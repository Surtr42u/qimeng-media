package httpapi

// sources 端点端到端测试：出处分组计数/降序/无出处归组/COS 隔离与
// includeCos 切换。口径与 GET /assets 列表一致（DOMAIN_RULES §3/§6）；
// users 自定义出处端点见 TestCustomSourcesEndpoints（§4）。

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
)

// TestSourcesEndpoint 四段覆盖：
//  1. 全部无出处 → null 桶单行计 3（显示层兜底"其他"）；
//  2. 分组计数 + fileCount 降序（铁拳×2 在 null×1 前）；
//  3. 默认排除 COS 作者关联文件（只剩铁拳）；
//  4. includeCos=true 重新包含（null 桶恢复）。
func TestSourcesEndpoint(t *testing.T) {
	env := newTestEnv(t)
	get := func(t *testing.T, query string) []gen.SourceCount {
		t.Helper()
		resp := env.do(t, http.MethodGet, "/api/v1/sources"+query, "")
		defer func() { _ = resp.Body.Close() }()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("sources 期望 200，得到 %d", resp.StatusCode)
		}
		var items []gen.SourceCount
		if err := json.NewDecoder(resp.Body).Decode(&items); err != nil {
			t.Fatalf("解析 sources 响应失败: %v", err)
		}
		return items
	}
	setSource := func(t *testing.T, assetID, src string) {
		t.Helper()
		if _, err := env.conn.Exec("UPDATE assets SET source = ? WHERE asset_id = ?", src, assetID); err != nil {
			t.Fatalf("设置出处失败: %v", err)
		}
	}

	// ① 无出处文件：单行 name=null、fileCount=3。
	items := get(t, "")
	if len(items) != 1 || items[0].Name != nil ||
		items[0].FileCount == nil || *items[0].FileCount != 3 {
		t.Fatalf("无出处文件应归入 null 桶且计数 3，得到 %+v", items)
	}

	// ② 分组计数 + 降序：a.jpg/b.jpg 归"铁拳"（2），c.mp4 保持无出处（1）。
	setSource(t, testFiles[0].id, "铁拳")
	setSource(t, testFiles[1].id, "铁拳")
	items = get(t, "")
	if len(items) != 2 ||
		items[0].Name == nil || *items[0].Name != "铁拳" || *items[0].FileCount != 2 ||
		items[1].Name != nil || *items[1].FileCount != 1 {
		t.Fatalf("按出处分组应 [铁拳:2, null:1]（fileCount 降序），得到 %+v", items)
	}

	// ③ 默认排除 COS：c.mp4（无出处）挂 COS 作者后从默认结果中消失。
	if _, err := env.conn.Exec(
		"INSERT INTO authors(id, display_name, type, created_at) VALUES ('cos-src', 'COS 作者', 'cos', '2026-01-01T00:00:00.000Z')"); err != nil {
		t.Fatalf("插入 COS 作者失败: %v", err)
	}
	if _, err := env.conn.Exec(
		"INSERT INTO asset_authors(asset_id, author_id) VALUES (?, 'cos-src')", testFiles[2].id); err != nil {
		t.Fatalf("关联 COS 作者失败: %v", err)
	}
	items = get(t, "")
	if len(items) != 1 || items[0].Name == nil || *items[0].Name != "铁拳" || *items[0].FileCount != 2 {
		t.Fatalf("默认应排除 COS 关联文件，得到 %+v", items)
	}

	// ④ includeCos=true：COS 关联文件重新计入（null 桶回到结果中）。
	items = get(t, "?includeCos=true")
	if len(items) != 2 || items[0].Name == nil || *items[0].Name != "铁拳" ||
		items[1].Name != nil || *items[1].FileCount != 1 {
		t.Fatalf("includeCos=true 应包含 COS 文件并归入 null 桶，得到 %+v", items)
	}
}

// TestCustomSourcesEndpoints：custom 端点闭环——初始空数组 → PUT 乱序/
// 重复/夹空白名单 → GET 回读规范化（trim+去空+去重+升序）且持久化形态
// 与回读一致（扫描器构造期按存储值装载）→ PUT 空数清空。
func TestCustomSourcesEndpoints(t *testing.T) {
	env := newTestEnv(t)
	get := func(t *testing.T) gen.CustomSources {
		t.Helper()
		resp := env.do(t, http.MethodGet, "/api/v1/sources/custom", "")
		defer func() { _ = resp.Body.Close() }()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("GET custom 期望 200，得到 %d", resp.StatusCode)
		}
		var body gen.CustomSources
		if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
			t.Fatalf("解析 custom 响应失败: %v", err)
		}
		return body
	}
	put := func(t *testing.T, payload string) int {
		t.Helper()
		resp := env.do(t, http.MethodPut, "/api/v1/sources/custom", payload)
		defer func() { _ = resp.Body.Close() }()
		return resp.StatusCode
	}

	// ① 初始无记录 = 空数组（匹配引擎侧无自定义出处即等价，非错误信号）。
	if got := get(t); len(got.Names) != 0 {
		t.Fatalf("初始自定义出处应为空数组，得到 %+v", got.Names)
	}

	// ② PUT 乱序 + 重复 + 夹空白 → 规范化为 [火影忍者, 钢之炼金术]（升序）。
	if code := put(t, `{"names":["  火影忍者 ","钢之炼金术","火影忍者","","钢之炼金术"]}`); code != http.StatusNoContent {
		t.Fatalf("PUT custom 期望 204，得到 %d", code)
	}
	got := get(t)
	if len(got.Names) != 2 || got.Names[0] != "火影忍者" || got.Names[1] != "钢之炼金术" {
		t.Fatalf("回读不规范：%+v, want [火影忍者 钢之炼金术]", got.Names)
	}
	stored, err := env.q.GetSetting(context.Background(), authoring.SettingKeyCustomSources)
	if err != nil {
		t.Fatalf("读取持久化自定义出处失败: %v", err)
	}
	if stored != `["火影忍者","钢之炼金术"]` {
		t.Errorf("持久化形态与回读不一致：%s", stored)
	}

	// ③ PUT 空数组 = 清空。
	if code := put(t, `{"names":[]}`); code != http.StatusNoContent {
		t.Fatalf("清空 PUT 期望 204，得到 %d", code)
	}
	if got := get(t); len(got.Names) != 0 {
		t.Fatalf("清空后应为空，得到 %+v", got.Names)
	}
}
