package httpapi

// 媒体库注册白名单（config.AllowedLibraryRoots）测试：
// 空 = 不限制（本地零配置向后兼容）；非空 = 白名单外拒绝、白名单内放行。

import (
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

// TestLibraryAllowedRootsEmptyWhitelist 空白名单 = 不限制：任意存在的
// 绝对目录都能注册（向后兼容本字段引入前的行为）。
func TestLibraryAllowedRootsEmptyWhitelist(t *testing.T) {
	env := newTestEnv(t)
	// 默认 cfg.AllowedLibraryRoots 为 nil
	if len(env.cfg.AllowedLibraryRoots) != 0 {
		t.Fatalf("默认配置不应带白名单，得到 %v", env.cfg.AllowedLibraryRoots)
	}
	lib2 := filepath.Join(filepath.Dir(env.dataDir), "lib2-empty-wl")
	if err := os.MkdirAll(lib2, 0o755); err != nil {
		t.Fatalf("建目录失败: %v", err)
	}
	resp := env.do(t, "POST", "/api/v1/libraries",
		`{"name":"空白名单库","rootPath":`+quote(lib2)+`}`)
	defer closeBody(resp)
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("空白名单下注册存在的绝对目录期望 201，得到 %d", resp.StatusCode)
	}
}

// TestLibraryAllowedRootsEnforced 非空白名单：白名单外 → 400 INVALID_PARAM；
// 白名单内（含子目录）→ 201。
func TestLibraryAllowedRootsEnforced(t *testing.T) {
	env := newTestEnv(t)
	root := filepath.Dir(env.dataDir) // 与 media/、data/ 同级的临时根
	allowed := filepath.Join(root, "allowed-media")
	inside := filepath.Join(allowed, "photos")
	outside := filepath.Join(root, "outside-media")
	for _, d := range []string{inside, outside} {
		if err := os.MkdirAll(d, 0o755); err != nil {
			t.Fatalf("建目录失败: %v", err)
		}
	}
	env.cfg.AllowedLibraryRoots = []string{allowed}

	// 白名单外：存在目录仍拒绝（不探测文件系统细节，只报白名单）
	resp := env.do(t, "POST", "/api/v1/libraries",
		`{"name":"越界库","rootPath":`+quote(outside)+`}`)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("白名单外注册期望 400，得到 %d", resp.StatusCode)
	}
	if code := errorCode(t, resp); code != codeInvalidParam {
		t.Fatalf("白名单外错误码期望 %s，得到 %s", codeInvalidParam, code)
	}
	closeBody(resp)

	// 白名单根本身
	resp = env.do(t, "POST", "/api/v1/libraries",
		`{"name":"白名单根","rootPath":`+quote(allowed)+`}`)
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("白名单根本身注册期望 201，得到 %d", resp.StatusCode)
	}
	closeBody(resp)

	// 白名单内子目录
	resp = env.do(t, "POST", "/api/v1/libraries",
		`{"name":"白名单子目录","rootPath":`+quote(inside)+`}`)
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("白名单内子目录注册期望 201，得到 %d", resp.StatusCode)
	}
	closeBody(resp)
}

// TestPathWithinAnyAllowedRoot 锁定前缀边界：空列表放行；根本身/子目录
// 放行；兄弟前缀（/media vs /mediax）拒绝；大小写不敏感。
func TestPathWithinAnyAllowedRoot(t *testing.T) {
	if !pathWithinAnyAllowedRoot(filepath.Join("C:", "media"), nil) {
		t.Error("空白名单应放行")
	}
	allowed := []string{filepath.Join("C:", "Media"), filepath.Join("D:", "photos")}
	if !pathWithinAnyAllowedRoot(filepath.Join("C:", "Media"), allowed) {
		t.Error("白名单根本身应放行")
	}
	if !pathWithinAnyAllowedRoot(filepath.Join("C:", "media", "a", "b"), allowed) {
		t.Error("子目录应放行（大小写不敏感）")
	}
	if pathWithinAnyAllowedRoot(filepath.Join("C:", "MediaX", "evil"), allowed) {
		t.Error("兄弟前缀 /MediaX 不应被 /Media 命中")
	}
	if pathWithinAnyAllowedRoot(filepath.Join("E:", "other"), allowed) {
		t.Error("盘符外路径应拒绝")
	}
}

// errorCode 从错误响应里取出 code 字段（writeErr 输出扁平 {"code","message"}）。
func errorCode(t *testing.T, resp *http.Response) string {
	t.Helper()
	var body struct {
		Code string `json:"code"`
	}
	if err := decodeBody(resp, &body); err != nil {
		t.Fatalf("解析错误响应失败: %v", err)
	}
	return body.Code
}
