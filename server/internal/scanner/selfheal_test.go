package scanner

// 引擎版本自愈重算测试（DOMAIN_RULES §4）：kv 无标记/标记落后时启动重算补齐
// 存量富化并落新标记；标记持平只做一次 kv 读跳过（不重算）；cos 库跳过不报错。
// 文件名用内置检索表真实组（守望先锋）+ 表零命中的兜底可提取词（新角色），
// 与 enrich_test.go 的取材口径一致。

import (
	"context"
	"strconv"
	"testing"

	"qimeng-media/server/internal/authoring"
)

// kvEngineVersion 读引擎版本标记（无记录返回空串，由调用方断言）。
func kvEngineVersion(t *testing.T, e *testEnv) string {
	t.Helper()
	v, err := e.q.GetSetting(context.Background(), authoring.SettingKeyEnrichmentEngineVersion)
	if err != nil {
		t.Fatalf("读引擎版本标记失败: %v", err)
	}
	return v
}

// TestSelfHealRecomputesWhenNoMarker：存量部署（kv 无标记）启动自愈——
// 「表零命中+兜底可提取」文件的资产被删掉角色行模拟旧引擎存量后，
// SelfHealEnrichmentIfNeeded 必须经兜底层补回角色并落版本标记。
func TestSelfHealRecomputesWhenNoMarker(t *testing.T) {
	probe := newProbeStub(nil, nil)
	env := newTestEnv(t, probe.call)
	env.writeFile(t, "守望先锋  新角色 1.jpg", 100)
	if _, err := env.s.Scan(context.Background(), env.lib); err != nil {
		t.Fatalf("Scan 失败: %v", err)
	}
	a := env.assetByPath(t, "守望先锋  新角色 1.jpg")
	// 现引擎（含兜底层）扫描应直接产出角色——测试前提自检。
	if got := characterNames(t, env, a.AssetID); len(got) != 1 || got[0] != "新角色" {
		t.Fatalf("首扫角色=%v, want [新角色]（兜底层前提不成立）", got)
	}

	// 模拟旧引擎存量：删干净角色行（旧引擎无兜底层时的落库形态）。
	if err := env.q.DeleteAssetCharacters(context.Background(), a.AssetID); err != nil {
		t.Fatalf("删除角色行失败: %v", err)
	}
	if got := characterNames(t, env, a.AssetID); len(got) != 0 {
		t.Fatalf("删除后角色=%v, want 空", got)
	}

	// 无标记 → 触发全库重算并落标记。
	if err := env.s.SelfHealEnrichmentIfNeeded(context.Background()); err != nil {
		t.Fatalf("SelfHealEnrichmentIfNeeded 失败: %v", err)
	}
	if got := characterNames(t, env, a.AssetID); len(got) != 1 || got[0] != "新角色" {
		t.Errorf("自愈后角色=%v, want [新角色]（兜底层补回）", got)
	}
	if want := strconv.Itoa(EnrichmentEngineVersion); kvEngineVersion(t, env) != want {
		t.Errorf("引擎版本标记=%q, want %q", kvEngineVersion(t, env), want)
	}
}

// TestSelfHealSkipsWhenMarkerCurrent：标记已持平 → 只做 kv 读直接返回，
// 不再重算（删掉的角色行保持缺失即证明跳过），标记不变。
func TestSelfHealSkipsWhenMarkerCurrent(t *testing.T) {
	probe := newProbeStub(nil, nil)
	env := newTestEnv(t, probe.call)
	env.writeFile(t, "守望先锋  新角色 1.jpg", 100)
	if _, err := env.s.Scan(context.Background(), env.lib); err != nil {
		t.Fatalf("Scan 失败: %v", err)
	}
	a := env.assetByPath(t, "守望先锋  新角色 1.jpg")
	// 首次调用：无标记 → 重算 + 落标记（与上一用例同路径，此处只作前置）。
	if err := env.q.DeleteAssetCharacters(context.Background(), a.AssetID); err != nil {
		t.Fatalf("删除角色行失败: %v", err)
	}
	if err := env.s.SelfHealEnrichmentIfNeeded(context.Background()); err != nil {
		t.Fatalf("首次 SelfHealEnrichmentIfNeeded 失败: %v", err)
	}
	if got := characterNames(t, env, a.AssetID); len(got) != 1 || got[0] != "新角色" {
		t.Fatalf("首次自愈角色=%v, want [新角色]（前置不成立）", got)
	}

	// 标记已持平：再删角色行后调用，必须跳过重算（角色保持缺失）。
	if err := env.q.DeleteAssetCharacters(context.Background(), a.AssetID); err != nil {
		t.Fatalf("二次删除角色行失败: %v", err)
	}
	if err := env.s.SelfHealEnrichmentIfNeeded(context.Background()); err != nil {
		t.Fatalf("二次 SelfHealEnrichmentIfNeeded 失败: %v", err)
	}
	if got := characterNames(t, env, a.AssetID); len(got) != 0 {
		t.Errorf("标记持平仍重算了：角色=%v, want 空（必须跳过）", got)
	}
	if want := strconv.Itoa(EnrichmentEngineVersion); kvEngineVersion(t, env) != want {
		t.Errorf("引擎版本标记=%q, want 不变 %q", kvEngineVersion(t, env), want)
	}
}

// TestSelfHealSkipsCosLibrary：cos 库无来源匹配语义（enrich.go 隔离口径），
// 自愈对其跳过 RecomputeEnrichment、不报错，标记照常落库。
func TestSelfHealSkipsCosLibrary(t *testing.T) {
	probe := newProbeStub(nil, nil)
	env := newTestEnv(t, probe.call)
	if _, err := env.conn.Exec(`UPDATE libraries SET kind = 'cos' WHERE id = ?`, env.lib.ID); err != nil {
		t.Fatalf("置 cos kind 失败: %v", err)
	}
	env.lib.Kind = LibraryKindCos
	env.writeFile(t, "作者A/1.jpg", 100)
	if _, err := env.s.Scan(context.Background(), env.lib); err != nil {
		t.Fatalf("Scan 失败: %v", err)
	}

	if err := env.s.SelfHealEnrichmentIfNeeded(context.Background()); err != nil {
		t.Fatalf("SelfHealEnrichmentIfNeeded 失败: %v", err)
	}
	if want := strconv.Itoa(EnrichmentEngineVersion); kvEngineVersion(t, env) != want {
		t.Errorf("引擎版本标记=%q, want %q", kvEngineVersion(t, env), want)
	}
}
