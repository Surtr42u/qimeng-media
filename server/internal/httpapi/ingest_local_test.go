package httpapi

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
)

// TestIngestLocalEndpoint 覆盖 POST /libraries/{id}/ingest-local 的协议面：
// 真移动入库（201）、重复内容幂等（200 already_present，源不动）、撞名冲突（409）、
// 未配置读根（403）。库与源都在隔离临时目录，不接触真实库。
func TestIngestLocalEndpoint(t *testing.T) {
	env := newTestEnv(t)
	srcDir := t.TempDir()
	env.cfg.IngestLocal.ReadRoots = []string{srcDir}

	src := filepath.Join(srcDir, "incoming.jpg")
	writeTestJPG(t, src, 8, 8)

	post := func(body string) (*http.Response, gen.IngestLocalResult) {
		t.Helper()
		resp := env.do(t, http.MethodPost, "/api/v1/libraries/"+env.libID+"/ingest-local", body)
		var out gen.IngestLocalResult
		if resp.StatusCode == http.StatusCreated || resp.StatusCode == http.StatusOK {
			if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
				t.Fatalf("解析响应失败: %v", err)
			}
		}
		closeBody(resp)
		return resp, out
	}

	req := `{"sourcePath":"` + jsonPath(src) + `","fileName":"本地 1.jpg","directory":"合集 (1)","move":true}`
	resp, first := post(req)
	if resp.StatusCode != http.StatusCreated || first.Status != gen.Ingested || first.SourceKept {
		t.Fatalf("首次入库应 201 ingested: status=%d body=%+v", resp.StatusCode, first)
	}
	if first.RelPath != "合集 (1)/本地 1.jpg" || first.SizeBytes == 0 || first.AssetId == "" {
		t.Fatalf("响应字段不符: %+v", first)
	}
	if _, err := os.Stat(src); !os.IsNotExist(err) {
		t.Fatalf("move=true 源应已移走: %v", err)
	}
	// 索引可见：走真实列表端点（不直查库）
	if _, ok := env.assetIDByName(t, "本地 1.jpg"); !ok {
		t.Fatal("入库后列表应能查到新资产")
	}

	// 同内容副本：already_present，源保留，资产 ID 与首次一致
	dup := filepath.Join(srcDir, "dup.jpg")
	body, err := os.ReadFile(filepath.Join(env.media, "合集 (1)", "本地 1.jpg"))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(dup, body, 0o644); err != nil {
		t.Fatal(err)
	}
	dupReq := `{"sourcePath":"` + jsonPath(dup) + `","fileName":"本地 1.jpg","directory":"合集 (1)","move":true}`
	resp, again := post(dupReq)
	if resp.StatusCode != http.StatusOK || again.Status != gen.AlreadyPresent || !again.SourceKept {
		t.Fatalf("重复内容应 200 already_present: status=%d body=%+v", resp.StatusCode, again)
	}
	if again.AssetId != first.AssetId || again.Sha256 == nil || *again.Sha256 == "" {
		t.Fatalf("幂等应返回既有资产与摘要: %+v", again)
	}
	if _, err := os.Stat(dup); err != nil {
		t.Fatalf("already_present 不得动源: %v", err)
	}

	// 不同内容撞名：409，源与目标都不动
	other := filepath.Join(srcDir, "other.jpg")
	writeTestJPG(t, other, 16, 16)
	conflictReq := `{"sourcePath":"` + jsonPath(other) + `","fileName":"本地 1.jpg","directory":"合集 (1)","move":true}`
	resp, _ = post(conflictReq)
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("撞名不同内容应 409，得到 %d", resp.StatusCode)
	}
	if _, err := os.Stat(other); err != nil {
		t.Fatalf("冲突时源不应被动: %v", err)
	}

	// 未配置读根：403
	env.cfg.IngestLocal.ReadRoots = nil
	resp, _ = post(conflictReq)
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("未配置读根应 403，得到 %d", resp.StatusCode)
	}
}

// jsonPath 把 Windows 路径转成 JSON 字符串字面量（反斜杠转义由 json.Marshal 负责）。
func jsonPath(p string) string {
	b, _ := json.Marshal(p)
	return string(b[1 : len(b)-1])
}
