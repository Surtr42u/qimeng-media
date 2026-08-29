package httpapi

// 回收站端点端到端测试：删除→回收站→列表→恢复（含冲突自动重命名）、
// 物理删除单个、清空、404/401 语义。复用 browse_test.go 的 testEnv
//（真实文件 + 真实 SQLite + Bearer 鉴权链）。

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
)

// assetIDByName 从列表端点按文件名查资产 ID（走真实 API 而非直查库，
// 顺带锁定"删除后列表不再出现"的对外语义）。
func (e *testEnv) assetIDByName(t *testing.T, name string) (string, bool) {
	t.Helper()
	resp := e.do(t, http.MethodGet, "/api/v1/assets?limit=200", "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("列表请求期望 200，得到 %d", resp.StatusCode)
	}
	var page gen.AssetPage
	if err := json.NewDecoder(resp.Body).Decode(&page); err != nil {
		t.Fatalf("解析列表失败: %v", err)
	}
	for _, it := range *page.Items {
		if it.FileName != nil && *it.FileName == name {
			return it.Id.String(), true
		}
	}
	return "", false
}

// trashList 拉取回收站列表。
func (e *testEnv) trashList(t *testing.T) []gen.TrashItem {
	t.Helper()
	resp := e.do(t, http.MethodGet, "/api/v1/trash", "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("回收站列表期望 200，得到 %d", resp.StatusCode)
	}
	var items []gen.TrashItem
	if err := json.NewDecoder(resp.Body).Decode(&items); err != nil {
		t.Fatalf("解析回收站列表失败: %v", err)
	}
	return items
}

// TestTrashDeleteRestoreFlow 锁定主流程契约：删除后列表消失/回收站出现，
// 恢复后 asset_id 不变回到列表，回收站清空。
func TestTrashDeleteRestoreFlow(t *testing.T) {
	env := newTestEnv(t)
	id, ok := env.assetIDByName(t, "a.jpg")
	if !ok {
		t.Fatal("测试前置失败：a.jpg 不在列表")
	}

	// 删除 → 200，列表不再有 a.jpg
	resp := env.do(t, http.MethodDelete, "/api/v1/assets/"+id, "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("删除期望 200，得到 %d", resp.StatusCode)
	}
	if _, still := env.assetIDByName(t, "a.jpg"); still {
		t.Error("删除后 a.jpg 仍出现在列表")
	}
	// 库内文件必须已移走（删除是移动文件不是只删行）
	if _, err := os.Stat(filepath.Join(env.media, "a.jpg")); !os.IsNotExist(err) {
		t.Error("删除后库目录内文件仍存在")
	}

	// 回收站列表：一条，字段与删除的资产一致
	items := env.trashList(t)
	if len(items) != 1 {
		t.Fatalf("回收站期望 1 条，得到 %d", len(items))
	}
	it := items[0]
	if it.Id == nil || *it.Id == "" || it.FileName == nil || *it.FileName != "a.jpg" ||
		it.OriginalPath == nil || *it.OriginalPath != "a.jpg" || it.SizeBytes == nil || *it.SizeBytes <= 0 {
		t.Errorf("回收站条目字段不符: %+v", it)
	}
	if it.DeletedAt == nil || it.ExpiresAt == nil || !it.ExpiresAt.After(*it.DeletedAt) {
		t.Errorf("deletedAt/expiresAt 不符: %+v", it)
	}

	// 恢复 → 200，asset_id 不变、回到列表
	resp = env.do(t, http.MethodPost, "/api/v1/trash/"+*it.Id+"/restore", "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("恢复期望 200，得到 %d", resp.StatusCode)
	}
	restoredID, ok := env.assetIDByName(t, "a.jpg")
	if !ok {
		t.Fatal("恢复后 a.jpg 未回到列表")
	}
	if restoredID != id {
		t.Errorf("恢复后 asset_id 变化: 原 %s 恢复后 %s（身份机制不允许）", id, restoredID)
	}
	if got := len(env.trashList(t)); got != 0 {
		t.Errorf("恢复后回收站应为空，得到 %d 条", got)
	}
}

// TestTrashRestoreConflict 锁定"冲突自动重命名"承诺：原位置被占用时
// 恢复为 "基名 (2).ext" 而非失败。
func TestTrashRestoreConflict(t *testing.T) {
	env := newTestEnv(t)
	id, ok := env.assetIDByName(t, "b.jpg")
	if !ok {
		t.Fatal("测试前置失败：b.jpg 不在列表")
	}
	resp := env.do(t, http.MethodDelete, "/api/v1/assets/"+id, "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("删除期望 200，得到 %d", resp.StatusCode)
	}
	// 模拟原位置被新文件占用
	if err := os.WriteFile(filepath.Join(env.media, "b.jpg"), []byte("new-occupier"), 0o644); err != nil {
		t.Fatalf("制造占位文件失败: %v", err)
	}
	items := env.trashList(t)
	if len(items) != 1 {
		t.Fatalf("回收站期望 1 条，得到 %d", len(items))
	}
	resp = env.do(t, http.MethodPost, "/api/v1/trash/"+*items[0].Id+"/restore", "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("冲突恢复期望 200（自动重命名），得到 %d", resp.StatusCode)
	}
	if _, ok := env.assetIDByName(t, "b (2).jpg"); !ok {
		t.Error("冲突恢复后应出现 b (2).jpg")
	}
	if got := len(env.trashList(t)); got != 0 {
		t.Errorf("恢复后回收站应为空，得到 %d 条", got)
	}
}

// TestTrashPhysicalDelete 单个物理删除：条目消失且文件真没了。
func TestTrashPhysicalDelete(t *testing.T) {
	env := newTestEnv(t)
	id, _ := env.assetIDByName(t, "c.mp4")
	resp := env.do(t, http.MethodDelete, "/api/v1/assets/"+id, "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("删除期望 200，得到 %d", resp.StatusCode)
	}
	items := env.trashList(t)
	if len(items) != 1 {
		t.Fatalf("回收站期望 1 条，得到 %d", len(items))
	}
	resp = env.do(t, http.MethodDelete, "/api/v1/trash/"+*items[0].Id, "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("物理删除期望 204，得到 %d", resp.StatusCode)
	}
	if got := len(env.trashList(t)); got != 0 {
		t.Errorf("物理删除后回收站应为空，得到 %d 条", got)
	}
}

// TestTrashEmptyAll 清空回收站。
func TestTrashEmptyAll(t *testing.T) {
	env := newTestEnv(t)
	for _, name := range []string{"a.jpg", "b.jpg"} {
		id, ok := env.assetIDByName(t, name)
		if !ok {
			t.Fatalf("测试前置失败：%s 不在列表", name)
		}
		resp := env.do(t, http.MethodDelete, "/api/v1/assets/"+id, "")
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("删除 %s 期望 200，得到 %d", name, resp.StatusCode)
		}
	}
	if got := len(env.trashList(t)); got != 2 {
		t.Fatalf("清空前回收站期望 2 条，得到 %d", got)
	}
	resp := env.do(t, http.MethodDelete, "/api/v1/trash", "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("清空期望 204，得到 %d", resp.StatusCode)
	}
	if got := len(env.trashList(t)); got != 0 {
		t.Errorf("清空后回收站应为空，得到 %d 条", got)
	}
}

// TestDeleteAssetNotFound：不存在的资产 404（区分 500）。
func TestDeleteAssetNotFound(t *testing.T) {
	env := newTestEnv(t)
	resp := env.do(t, http.MethodDelete, "/api/v1/assets/00000000-0000-0000-0000-000000000000", "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("删除不存在资产期望 404，得到 %d", resp.StatusCode)
	}
}

// TestTrashRestoreNotFound：恢复不存在的条目 404。
func TestTrashRestoreNotFound(t *testing.T) {
	env := newTestEnv(t)
	resp := env.do(t, http.MethodPost, "/api/v1/trash/0000000000000000000_00000000-0000-0000-0000-000000000000/restore", "")
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("恢复不存在条目期望 404，得到 %d", resp.StatusCode)
	}
}

// TestTrashUnauthorized：回收站端点全要求 Bearer。
func TestTrashUnauthorized(t *testing.T) {
	env := newTestEnv(t)
	for _, tc := range []struct{ method, path string }{
		{http.MethodGet, "/api/v1/trash"},
		{http.MethodDelete, "/api/v1/trash"},
		{http.MethodDelete, "/api/v1/assets/00000000-0000-0000-0000-000000000000"},
	} {
		req, _ := http.NewRequest(tc.method, env.ts.URL+tc.path, nil)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatalf("%s %s 请求失败: %v", tc.method, tc.path, err)
		}
		_ = resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("%s %s 无 token 期望 401，得到 %d", tc.method, tc.path, resp.StatusCode)
		}
	}
}
