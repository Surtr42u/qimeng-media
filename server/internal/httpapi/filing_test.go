package httpapi

// 移动/重命名端点测试：身份保持（asset_id 不变、关联保留）、
// 重命名、冲突 409（文件系统占用）、路径穿越拒绝、404。

import (
	"bytes"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
)

// assetDetail 拉取资产详情（检查 relPath/fileId 的一致性口径）。
func (e *testEnv) assetDetail(t *testing.T, id string) gen.AssetDetail {
	t.Helper()
	resp := e.do(t, http.MethodGet, "/api/v1/assets/"+id, "")
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("详情请求期望 200，得到 %d", resp.StatusCode)
	}
	var d gen.AssetDetail
	if err := json.NewDecoder(resp.Body).Decode(&d); err != nil {
		t.Fatalf("解析详情失败: %v", err)
	}
	return d
}

func TestMoveToSubdir(t *testing.T) {
	env := newTestEnv(t)
	id, ok := env.assetIDByName(t, "a.jpg")
	if !ok {
		t.Fatal("测试前置失败：a.jpg 不在列表")
	}
	resp := env.do(t, http.MethodPost, "/api/v1/assets/"+id+"/move",
		`{"targetDir":"2026/夏"}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("移动期望 200，得到 %d", resp.StatusCode)
	}
	// 身份保持：asset_id 不变
	if _, ok := env.assetIDByName(t, "a.jpg"); !ok {
		t.Error("移动后 a.jpg 从列表消失（身份应保持）")
	}
	// 详情的 relPath 已指向新位置
	d := env.assetDetail(t, id)
	if d.RelPath == nil || *d.RelPath != "2026/夏/a.jpg" {
		t.Errorf("移动后 relPath = %v, 期望 2026/夏/a.jpg", d.RelPath)
	}
	// 磁盘与库一致
	if _, err := os.Stat(filepath.Join(env.media, "2026", "夏", "a.jpg")); err != nil {
		t.Errorf("新位置文件不存在: %v", err)
	}
	if _, err := os.Stat(filepath.Join(env.media, "a.jpg")); !os.IsNotExist(err) {
		t.Error("旧位置文件仍存在")
	}
}

func TestMoveRename(t *testing.T) {
	env := newTestEnv(t)
	id, _ := env.assetIDByName(t, "b.jpg")
	resp := env.do(t, http.MethodPost, "/api/v1/assets/"+id+"/move",
		`{"targetDir":"","newName":"改名后的图.jpg"}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("重命名期望 200，得到 %d", resp.StatusCode)
	}
	d := env.assetDetail(t, id)
	if d.RelPath == nil || *d.RelPath != "改名后的图.jpg" {
		t.Errorf("重命名后 relPath = %v, 期望 改名后的图.jpg", d.RelPath)
	}
}

// TestMoveConflict：目标位置已有同名文件 → 409，原文件原地不动。
func TestMoveConflict(t *testing.T) {
	env := newTestEnv(t)
	id, _ := env.assetIDByName(t, "a.jpg")
	resp := env.do(t, http.MethodPost, "/api/v1/assets/"+id+"/move",
		`{"targetDir":"","newName":"b.jpg"}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusConflict {
		t.Fatalf("目标冲突期望 409，得到 %d", resp.StatusCode)
	}
	if _, err := os.Stat(filepath.Join(env.media, "a.jpg")); err != nil {
		t.Errorf("冲突拒绝后原文件应原地不动: %v", err)
	}
}

// TestMovePathEscape：目标目录携带穿越段 → 400（不落到文件系统）。
func TestMovePathEscape(t *testing.T) {
	env := newTestEnv(t)
	id, _ := env.assetIDByName(t, "a.jpg")
	resp := env.do(t, http.MethodPost, "/api/v1/assets/"+id+"/move",
		`{"targetDir":"../../escape"}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("路径穿越期望 400，得到 %d", resp.StatusCode)
	}
	if _, err := os.Stat(filepath.Join(env.media, "a.jpg")); err != nil {
		t.Errorf("拒绝穿越后原文件应原地不动: %v", err)
	}
}

func TestMoveNotFound(t *testing.T) {
	env := newTestEnv(t)
	resp := env.do(t, http.MethodPost, "/api/v1/assets/00000000-0000-0000-0000-000000000000/move",
		`{"targetDir":"x"}`)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("移动不存在资产期望 404，得到 %d", resp.StatusCode)
	}
}

// 错误响应只映射协议错误，不向客户端透传底层路径或 SQLite 诊断。
func TestMoveDatabaseFailureResponseKeepsOriginal(t *testing.T) {
	env := newTestEnv(t)
	id, ok := env.assetIDByName(t, "a.jpg")
	if !ok {
		t.Fatal("缺少合成资产")
	}
	original, err := os.ReadFile(filepath.Join(env.media, "a.jpg"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := env.conn.Exec(`CREATE TRIGGER synthetic_move_fail BEFORE UPDATE OF rel_path ON assets BEGIN SELECT RAISE(ABORT,'synthetic private database diagnostic'); END`); err != nil {
		t.Fatal(err)
	}
	resp := env.do(t, http.MethodPost, "/api/v1/assets/"+id+"/move", `{"targetDir":"next"}`)
	var failure map[string]string
	if err := json.NewDecoder(resp.Body).Decode(&failure); err != nil {
		t.Fatal(err)
	}
	if err := resp.Body.Close(); err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusInternalServerError || failure["code"] != codeInternal || failure["message"] != "内部错误" {
		t.Fatalf("移动失败协议: %d %+v", resp.StatusCode, failure)
	}
	if got, err := os.ReadFile(filepath.Join(env.media, "a.jpg")); err != nil || !bytes.Equal(got, original) {
		t.Fatal("DB 失败后未回滚原件", err)
	}
	if _, err := os.Stat(filepath.Join(env.media, "next", "a.jpg")); !os.IsNotExist(err) {
		t.Fatal("回滚不应残留重复件", err)
	}
	if _, err := env.conn.Exec(`DROP TRIGGER synthetic_move_fail`); err != nil {
		t.Fatal(err)
	}
	resp = env.do(t, http.MethodPost, "/api/v1/assets/"+id+"/move", `{"targetDir":"next"}`)
	if err := resp.Body.Close(); err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusOK {
		t.Fatal("故障解除后可重试", resp.StatusCode)
	}
}

func TestMoveKeepsExistenceBeforeBodyValidation(t *testing.T) {
	env := newTestEnv(t)
	resp := env.do(t, http.MethodPost, "/api/v1/assets/00000000-0000-0000-0000-000000000000/move", "malformed JSON")
	if err := resp.Body.Close(); err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusNotFound {
		t.Fatal("不存在资产保持先 404", resp.StatusCode)
	}
	id, ok := env.assetIDByName(t, "a.jpg")
	if !ok {
		t.Fatal("缺少合成资产")
	}
	resp = env.do(t, http.MethodPost, "/api/v1/assets/"+id+"/move", "malformed JSON")
	if err := resp.Body.Close(); err != nil {
		t.Fatal(err)
	}
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatal("存在资产仍校验请求体", resp.StatusCode)
	}
}
