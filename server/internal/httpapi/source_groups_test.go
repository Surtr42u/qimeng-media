package httpapi

// source_groups_test.go：检索词表维护端点（ADR-0033）端到端测试——
// 初始空数组 → PUT 夹空白/重复 canonical/空 canonical 混合载荷 → GET 回读
// 规范化（存储形态 = 生效形态）且引擎收到的就是持久化形态（fakeScanner
// 记录断言）→ PUT 空数组清空 → 超容量上限 400。真实合并/匹配语义由
// sourcematcher custom_test.go 锁定，本文件锁 HTTP 行为。

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/sourcematcher"
)

func TestCustomSourceGroupsEndpoints(t *testing.T) {
	env := newTestEnv(t)
	get := func(t *testing.T) gen.CustomSourceGroups {
		t.Helper()
		resp := env.do(t, http.MethodGet, "/api/v1/sources/custom-groups", "")
		defer func() { _ = resp.Body.Close() }()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("GET custom-groups 期望 200，得到 %d", resp.StatusCode)
		}
		var body gen.CustomSourceGroups
		if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
			t.Fatalf("解析 custom-groups 响应失败: %v", err)
		}
		return body
	}
	put := func(t *testing.T, payload string) int {
		t.Helper()
		resp := env.do(t, http.MethodPut, "/api/v1/sources/custom-groups", payload)
		defer func() { _ = resp.Body.Close() }()
		return resp.StatusCode
	}

	// ① 初始无记录 = 空数组（内置 130 组检索表完整可用，非配置缺失）。
	if got := get(t); len(got.Groups) != 0 {
		t.Fatalf("初始自定义出处组应为空数组，得到 %+v", got.Groups)
	}

	// ② PUT 混合载荷：夹空白、组内重复变体/别名、重复 canonical（取首个）、
	// 空 canonical（剔除）→ 规范化持久化 + 引擎收到同一形态。
	payload := `{"groups":[
		{"canonical":"  怪物猎人 ",
		 "variants":["怪物猎人","Monster Hunter","怪物猎人",""],
		 "characters":[{"canonical":"杰玛","aliases":["Gemma","杰玛","杰玛"]},
		               {"canonical":"杰玛","aliases":["重复取首"]},
		               {"canonical":"","aliases":["空角色剔除"]}]},
		{"canonical":"怪物猎人","characters":[{"canonical":"重复组取首"}]},
		{"canonical":"守望先锋","characters":[{"canonical":"Dmon"}]},
		{"canonical":""}]}`

	if code := put(t, payload); code != http.StatusNoContent {
		t.Fatalf("PUT custom-groups 期望 204，得到 %d", code)
	}
	got := get(t)
	if len(got.Groups) != 2 {
		t.Fatalf("规范化后应 2 组（重复 canonical 取首、空剔除），得到 %d: %+v", len(got.Groups), got.Groups)
	}
	mh, ow := got.Groups[0], got.Groups[1]
	if mh.Canonical != "怪物猎人" || ow.Canonical != "守望先锋" {
		t.Fatalf("组序/规范名 trim 失配: %q / %q", mh.Canonical, ow.Canonical)
	}
	if mh.Variants == nil || len(*mh.Variants) != 2 || (*mh.Variants)[0] != "怪物猎人" || (*mh.Variants)[1] != "Monster Hunter" {
		t.Fatalf("怪物猎人变体应 trim+去重为 [怪物猎人 Monster Hunter]: %v", mh.Variants)
	}
	if len(*mh.Characters) != 1 || (*mh.Characters)[0].Canonical != "杰玛" {
		t.Fatalf("怪物猎人角色应 1 个杰玛（重复取首、空剔除）: %+v", mh.Characters)
	}
	if aliases := *(*mh.Characters)[0].Aliases; len(aliases) != 2 || aliases[0] != "Gemma" || aliases[1] != "杰玛" {
		t.Fatalf("杰玛别名应保序去重 [Gemma 杰玛]: %v", aliases)
	}
	if ow.Variants == nil || len(*ow.Variants) != 1 || (*ow.Variants)[0] != "守望先锋" {
		t.Fatalf("未提交变体的组应只有 canonical 自身参与匹配: %v", ow.Variants)
	}
	if len(*ow.Characters) != 1 || (*ow.Characters)[0].Canonical != "Dmon" {
		t.Fatalf("守望先锋角色应为 Dmon: %+v", ow.Characters)
	}

	// ③ 引擎收到的 = 持久化的（fakeScanner 记录断言；真实合并/匹配在
	// sourcematcher custom_test.go）。
	recv := env.fscan.updatedGroups
	if len(recv) != 2 || recv[0].Canonical != "怪物猎人" || recv[1].Canonical != "守望先锋" {
		t.Fatalf("引擎收到的组失配: %+v", recv)
	}
	if len(recv[0].Characters) != 1 || recv[0].Characters[0].Canonical != "杰玛" {
		t.Fatalf("引擎收到的角色失配: %+v", recv[0].Characters)
	}
	stored, err := env.q.GetSetting(context.Background(), authoring.SettingKeyCustomSourceGroups)
	if err != nil {
		t.Fatalf("读取持久化出处组失败: %v", err)
	}
	var persisted []sourcematcher.SourceGroup
	if err := json.Unmarshal([]byte(stored), &persisted); err != nil {
		t.Fatalf("持久化形态非引擎输入形态 JSON: %v", err)
	}
	if len(persisted) != 2 || persisted[0].Canonical != "怪物猎人" || persisted[1].Canonical != "守望先锋" {
		t.Fatalf("持久化形态失配: %+v", persisted)
	}

	// ④ PUT 空数组 = 清空。
	if code := put(t, `{"groups":[]}`); code != http.StatusNoContent {
		t.Fatalf("清空 PUT 期望 204，得到 %d", code)
	}
	if got := get(t); len(got.Groups) != 0 {
		t.Fatalf("清空后应为空，得到 %+v", got.Groups)
	}

	// ⑤ 超容量上限 400 INVALID_PARAM（组数 257 > 256）。
	var overflow strings.Builder
	overflow.WriteString(`{"groups":[`)
	for i := 0; i < 257; i++ {
		if i > 0 {
			overflow.WriteString(",")
		}
		overflow.WriteString(`{"canonical":"组` + strings.Repeat("x", i%10) + `"}`)
	}
	overflow.WriteString(`]}`)
	if code := put(t, overflow.String()); code != http.StatusBadRequest {
		t.Fatalf("超上限 PUT 期望 400，得到 %d", code)
	}

	// ⑥ 单串超长 400（101 rune > 100）。
	long := strings.Repeat("长", 101)
	if code := put(t, `{"groups":[{"canonical":"`+long+`"}]}`); code != http.StatusBadRequest {
		t.Fatalf("超长规范名 PUT 期望 400，得到 %d", code)
	}

	// ⑦ 乱码替换符（U+FFFD）400（2026-10-03 事故加固：非 UTF-8 客户端载荷
	// 被 JSON 解码器静默替换成 U+FFFD 后落库 = 永不命中的死词条，且整体
	// 替换语义会覆盖掉此前的正确词表——必须在入口显式报错）。
	if code := put(t, `{"groups":[{"canonical":"守望先锋\ufffd","characters":[{"canonical":"D.Mon","aliases":["Dmon"]}]}]}`); code != http.StatusBadRequest {
		t.Fatalf("乱码 canonical PUT 期望 400，得到 %d", code)
	}
	if code := put(t, `{"groups":[{"canonical":"守望先锋","characters":[{"canonical":"D.Mon","aliases":["Dmo\ufffd"]}]}]}`); code != http.StatusBadRequest {
		t.Fatalf("乱码别名 PUT 期望 400，得到 %d", code)
	}
	if code := put(t, `{"groups":[{"canonical":"\ufffd\ufffd\ufffd"}]}`); code != http.StatusBadRequest {
		t.Fatalf("全乱码 canonical PUT 期望 400，得到 %d", code)
	}
}
