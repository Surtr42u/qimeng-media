package httpapi

// 上传端点测试：真 JPEG 流式上传入库、冲突自动重命名、四道校验
// （魔数不符/白名单外扩展名/大小上限 413）、libraryId 校验、401。

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
)

// uploadBytes 发起流式上传，返回响应（调用方负责 close）。
func (e *testEnv) uploadBytes(t *testing.T, libraryID, dir, filename string, body []byte) *http.Response {
	t.Helper()
	url := e.ts.URL + "/api/v1/assets/upload?libraryId=" + libraryID +
		"&dir=" + dir + "&filename=" + filename
	req, err := http.NewRequest(http.MethodPost, url, strings.NewReader(string(body)))
	if err != nil {
		t.Fatalf("构造上传请求失败: %v", err)
	}
	req.Header.Set("Authorization", "Bearer "+e.token)
	req.Header.Set("Content-Type", "application/octet-stream")
	return e.doRaw(t, req)
}

func (e *testEnv) doRaw(t *testing.T, req *http.Request) *http.Response {
	t.Helper()
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	return resp
}

func makeJPG(t *testing.T, dir string, w, h int) []byte {
	t.Helper()
	p := filepath.Join(t.TempDir(), "x.jpg")
	writeTestJPG(t, p, w, h)
	b, err := os.ReadFile(p)
	if err != nil {
		t.Fatalf("读测试图片失败: %v", err)
	}
	return b
}

func TestUploadJPEGToSubdir(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 320, 240)
	resp := env.uploadBytes(t, env.libID, "手机照片", "IMG_0001.jpg", jpg)
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("上传期望 201，得到 %d", resp.StatusCode)
	}
	var d gen.AssetDetail
	if err := json.NewDecoder(resp.Body).Decode(&d); err != nil {
		t.Fatalf("解析上传响应失败: %v", err)
	}
	if d.RelPath == nil || *d.RelPath != "手机照片/IMG_0001.jpg" {
		t.Errorf("relPath = %v, 期望 手机照片/IMG_0001.jpg", d.RelPath)
	}
	if d.OrigUrl == nil || !strings.Contains(*d.OrigUrl, "/media/orig/") {
		t.Errorf("上传响应应带签名原图直链: %v", d.OrigUrl)
	}
	// 磁盘真实存在
	if _, err := os.Stat(filepath.Join(env.media, "手机照片", "IMG_0001.jpg")); err != nil {
		t.Errorf("文件未落盘: %v", err)
	}
	// 列表可见
	if _, ok := env.assetIDByName(t, "IMG_0001.jpg"); !ok {
		t.Error("上传后列表未出现新资产")
	}
}

// TestUploadConflictRename：同名文件存在 → 自动 "基名 (2).ext"。
func TestUploadConflictRename(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 64, 64)
	_ = env.uploadBytes(t, env.libID, "", "same.jpg", jpg)
	resp := env.uploadBytes(t, env.libID, "", "same.jpg", jpg)
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("冲突上传期望 201（自动重命名），得到 %d", resp.StatusCode)
	}
	var d gen.AssetDetail
	if err := json.NewDecoder(resp.Body).Decode(&d); err != nil {
		t.Fatalf("解析响应失败: %v", err)
	}
	if d.RelPath == nil || *d.RelPath != "same (2).jpg" {
		t.Errorf("冲突重命名 relPath = %v, 期望 same (2).jpg", d.RelPath)
	}
}

// TestUploadMimeMismatch：.jpg 里装文本 → 400（魔数与扩展名不符）。
func TestUploadMimeMismatch(t *testing.T) {
	env := newTestEnv(t)
	resp := env.uploadBytes(t, env.libID, "", "evil.jpg", []byte("this is not a jpeg at all........"))
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("魔数不符期望 400，得到 %d", resp.StatusCode)
	}
	// 磁盘无残留
	if _, err := os.Stat(filepath.Join(env.media, "evil.jpg")); !os.IsNotExist(err) {
		t.Error("被拒上传不应留文件")
	}
}

// TestUploadBadExtension：白名单外扩展名 → 400。
func TestUploadBadExtension(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 32, 32)
	resp := env.uploadBytes(t, env.libID, "", "payload.exe", jpg)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("白名单外扩展名期望 400，得到 %d", resp.StatusCode)
	}
}

// TestUploadTooLarge：Content-Length 超限 → 413 且不落盘。
func TestUploadTooLarge(t *testing.T) {
	env := newTestEnv(t)
	env.cfg.Upload.MaxBytes = 1024 // 本用例限 1KB（Server 持有同一 cfg 指针）
	jpg := makeJPG(t, t.TempDir(), 320, 240)
	resp := env.uploadBytes(t, env.libID, "", "big.jpg", jpg)
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusRequestEntityTooLarge {
		t.Fatalf("超限上传期望 413，得到 %d", resp.StatusCode)
	}
	if _, err := os.Stat(filepath.Join(env.media, "big.jpg")); !os.IsNotExist(err) {
		t.Error("超限上传不应留文件")
	}
	env.cfg.Upload.MaxBytes = 0 // 恢复默认，避免影响同 env 的其他断言
}

// TestUploadBadLibrary：libraryId 不存在 → 400。
func TestUploadBadLibrary(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 32, 32)
	resp := env.uploadBytes(t, "00000000-0000-0000-0000-000000000000", "", "x.jpg", jpg)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("库不存在期望 400，得到 %d", resp.StatusCode)
	}
}

// TestUploadUnauthorized：无 token 401。
func TestUploadUnauthorized(t *testing.T) {
	env := newTestEnv(t)
	req, _ := http.NewRequest(http.MethodPost,
		env.ts.URL+"/api/v1/assets/upload?libraryId=x&dir=&filename=a.jpg",
		strings.NewReader("x"))
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("请求失败: %v", err)
	}
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("无 token 期望 401，得到 %d", resp.StatusCode)
	}
}
