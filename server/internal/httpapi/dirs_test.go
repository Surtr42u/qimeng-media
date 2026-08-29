package httpapi

// 目录树端点测试：树结构（含新建目录后出现）、fileCount 只计直接文件、
// POST 新建目录幂等、穿越拒绝、参数缺失 400、库不存在 404。

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
)

func dirsTree(t *testing.T, env *testEnv, query string) gen.DirTree {
	t.Helper()
	resp := env.do(t, http.MethodGet, "/api/v1/dirs"+query, "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("目录树期望 200，得到 %d", resp.StatusCode)
	}
	var tree gen.DirTree
	if err := json.NewDecoder(resp.Body).Decode(&tree); err != nil {
		t.Fatalf("解析目录树失败: %v", err)
	}
	return tree
}

func TestDirsTreeStructure(t *testing.T) {
	env := newTestEnv(t)
	// testEnv 库根有 3 个直接文件（a.jpg/b.jpg/c.mp4），无子目录
	tree := dirsTree(t, env, "?libraryId="+env.libID)
	if tree.Path == nil || *tree.Path != "" {
		t.Errorf("根节点 path = %v, 期望空串", tree.Path)
	}
	if tree.FileCount == nil || *tree.FileCount != 3 {
		t.Errorf("根 fileCount = %v, 期望 3", tree.FileCount)
	}
	// 新建两级目录后再查：树里出现，父节点 fileCount 不变（目录不计文件）
	resp := env.do(t, http.MethodPost, "/api/v1/dirs",
		`{"path":"2026/夏","libraryId":"`+env.libID+`"}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("新建目录期望 201，得到 %d", resp.StatusCode)
	}
	tree = dirsTree(t, env, "?libraryId="+env.libID)
	if tree.Dirs == nil || len(*tree.Dirs) != 1 || *(*tree.Dirs)[0].Path != "2026" {
		t.Fatalf("目录树应含 2026 节点: %+v", tree.Dirs)
	}
	summer := (*tree.Dirs)[0]
	if summer.Dirs == nil || len(*summer.Dirs) != 1 || *(*summer.Dirs)[0].Path != "2026/夏" {
		t.Errorf("2026 下应有 夏 子节点: %+v", summer.Dirs)
	}
	if *summer.FileCount != 0 {
		t.Errorf("空目录 fileCount 应 0: %v", summer.FileCount)
	}
	// 磁盘真实存在
	if _, err := os.Stat(filepath.Join(env.media, "2026", "夏")); err != nil {
		t.Errorf("目录未真实落盘: %v", err)
	}
}

// TestDirsPostIdempotent：重复新建同一目录仍 201（MkdirAll 幂等）。
func TestDirsPostIdempotent(t *testing.T) {
	env := newTestEnv(t)
	for i := 0; i < 2; i++ {
		resp := env.do(t, http.MethodPost, "/api/v1/dirs",
			`{"path":"相册","libraryId":"`+env.libID+`"}`)
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusCreated {
			t.Fatalf("第 %d 次新建期望 201，得到 %d", i+1, resp.StatusCode)
		}
	}
}

func TestDirsBadRequests(t *testing.T) {
	env := newTestEnv(t)
	// 缺 libraryId
	resp := env.do(t, http.MethodGet, "/api/v1/dirs", "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("缺 libraryId 期望 400，得到 %d", resp.StatusCode)
	}
	// 库不存在
	resp = env.do(t, http.MethodGet, "/api/v1/dirs?libraryId=00000000-0000-0000-0000-000000000000", "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("库不存在期望 404，得到 %d", resp.StatusCode)
	}
	// 新建目录穿越
	resp = env.do(t, http.MethodPost, "/api/v1/dirs",
		`{"path":"../escape","libraryId":"`+env.libID+`"}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("穿越路径期望 400，得到 %d", resp.StatusCode)
	}
	// 新建目录缺 libraryId
	resp = env.do(t, http.MethodPost, "/api/v1/dirs", `{"path":"x"}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("POST 缺 libraryId 期望 400，得到 %d", resp.StatusCode)
	}
}
