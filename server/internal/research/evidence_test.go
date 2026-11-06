package research

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// TestEvidenceStripsSessionParams：证据落盘的 URL 必须剥掉会话类参数。
func TestEvidenceStripsSessionParams(t *testing.T) {
	dir := t.TempDir()
	now := time.Date(2026, 11, 6, 12, 34, 56, 0, time.UTC)
	store := NewEvidenceStore(dir, func() time.Time { return now })
	err := store.SaveProvider(NameBing, Query{Operation: OperationSearch, Target: "query"},
		ProviderResult{
			Status: StatusOk, FetchedAt: now, LatencyMs: 12,
			Items: []Item{{
				Title: "t", Domain: "example.com",
				URL:   "https://example.com/a?token=secret&x=1#frag",
				ID:    "1",
				Extra: map[string]string{"k": "v"},
			}},
		})
	if err != nil {
		t.Fatalf("SaveProvider 失败: %v", err)
	}
	files, err := filepath.Glob(filepath.Join(dir, "2026-11-06", "*.json"))
	if err != nil || len(files) != 1 {
		t.Fatalf("证据文件 = %v, err=%v", files, err)
	}
	data, err := os.ReadFile(files[0])
	if err != nil {
		t.Fatalf("读取证据失败: %v", err)
	}
	text := string(data)
	if strings.Contains(text, "token=secret") || strings.Contains(text, "#frag") {
		t.Errorf("会话参数未剥离: %s", text)
	}
	if !strings.Contains(text, "x=1") || !strings.Contains(text, `"provider": "bing"`) {
		t.Errorf("证据内容缺失: %s", text)
	}
	if strings.Contains(text, "Authorization") {
		t.Errorf("证据不应包含请求头: %s", text)
	}
}

// TestEvidenceLensKeepsOnlyHashes：Lens 证据只记哈希/摘要，不含图片字节。
func TestEvidenceLensKeepsOnlyHashes(t *testing.T) {
	dir := t.TempDir()
	now := time.Date(2026, 11, 6, 1, 2, 3, 0, time.UTC)
	store := NewEvidenceStore(dir, func() time.Time { return now })
	fetched := now.Add(-time.Second)
	err := store.SaveLens(LensResult{
		Status: LensStatusOk, FrameSHA256: "frame", TextSHA256: "text",
		FetchedAt: &fetched, ElapsedMs: 17000,
		TopTitles:  []LensTitle{{Title: "T", Domain: "example.com"}},
		TopDomains: []string{"example.com"},
		Cites:      []LensCite{{Title: "c", URL: "https://example.com/c?session=abc", Domain: "example.com"}},
		Links:      []LensLink{{URL: "https://example.com/l", Domain: "example.com"}},
	})
	if err != nil {
		t.Fatalf("SaveLens 失败: %v", err)
	}
	files, _ := filepath.Glob(filepath.Join(dir, "2026-11-06", "lens-*.json"))
	if len(files) != 1 {
		t.Fatalf("证据文件 = %v", files)
	}
	data, _ := os.ReadFile(files[0])
	text := string(data)
	if !strings.Contains(text, `"frameSha256": "frame"`) || !strings.Contains(text, `"elapsedMs": 17000`) {
		t.Errorf("Lens 证据内容缺失: %s", text)
	}
	if strings.Contains(text, "session=abc") {
		t.Errorf("会话参数未剥离: %s", text)
	}
	if strings.Contains(text, "base64") {
		t.Errorf("证据不应包含图片字节")
	}
}

// TestEvidenceDisabled：未配置目录时落盘是 no-op。
func TestEvidenceDisabled(t *testing.T) {
	store := NewEvidenceStore("", time.Now)
	if store.Enabled() {
		t.Fatal("空目录应视为禁用")
	}
	if err := store.SaveProvider(NameBing, Query{}, ProviderResult{}); err != nil {
		t.Fatalf("禁用时不应报错: %v", err)
	}
}
