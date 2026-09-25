package httpapi

// assets_namesuggest_test.go：作品名序号联想端点端到端（GET
// /assets/name-suggestions，ADR-0024 修订）。纯函数口径（规范化匹配/序号
// 风格/跨扩展名/族排序）由 filing 包表驱动测试锁定；本文件锁定 HTTP 接线：
// 参数校验（libraryId 空 400 / 库不存在 404）、q 规范化空 → 200 空列表、
// cos 库可用、建议不含扩展名。

import (
	"bytes"
	"context"
	"image"
	"image/png"
	"net/http"
	"net/url"
	"reflect"
	"testing"

	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// makePNGBytes 生成真 PNG 字节（上传魔数校验要求扩展名与内容一致）。
func makePNGBytes(t *testing.T) []byte {
	t.Helper()
	var buf bytes.Buffer
	if err := png.Encode(&buf, image.NewRGBA(image.Rect(0, 0, 8, 8))); err != nil {
		t.Fatalf("生成测试 PNG 失败: %v", err)
	}
	return buf.Bytes()
}

// nameSuggestions GET /assets/name-suggestions →（状态码, 建议列表）。
func nameSuggestions(t *testing.T, e *testEnv, libraryID, q string) (int, []string) {
	t.Helper()
	resp := e.do(t, "GET", "/api/v1/assets/name-suggestions?libraryId="+
		url.QueryEscape(libraryID)+"&q="+url.QueryEscape(q), "")
	defer closeBody(resp)
	var body gen.NameSuggestions
	if err := decodeBody(resp, &body); err != nil {
		t.Fatalf("解析联想响应失败: %v", err)
	}
	return resp.StatusCode, body.Suggestions
}

// TestAssetsNameSuggestionsE2E：上传三个文件（含跨扩展名同系列与全角括号
// 无序号族）→ 联想返回协议示例结果；参数矩阵（空 q/空 libraryId/未知库/
// 无命中）。
func TestAssetsNameSuggestionsE2E(t *testing.T) {
	env := newTestEnv(t)
	pngBytes := makePNGBytes(t)
	jpgBytes := makeJPG(t, t.TempDir(), 32, 32)
	for _, up := range []struct {
		name string
		body []byte
	}{
		{"守望先锋 DVA 11.png", pngBytes},
		{"守望先锋 DVA 12.jpg", jpgBytes},  // 跨扩展名：png/jpg 同一系列
		{"守望先锋 DVA（特写）.jpg", jpgBytes}, // 全角括号族：无序号不产生建议
	} {
		d := uploadAttach201(t, env.uploadAttach(t, env.libID, up.name, up.body, nil))
		if d.FileName == nil || *d.FileName != up.name {
			t.Fatalf("上传 %q 落库名=%v", up.name, d.FileName)
		}
	}

	// 协议示例：少空格小写输入吸附库内规范写法，建议不含扩展名。
	if code, got := nameSuggestions(t, env, env.libID, "守望先锋dva"); code != http.StatusOK ||
		!reflect.DeepEqual(got, []string{"守望先锋 DVA 13"}) {
		t.Fatalf("联想=（%d, %#v）, want（200, [守望先锋 DVA 13]）", code, got)
	}
	// 规范输入同命中。
	if code, got := nameSuggestions(t, env, env.libID, "守望先锋 DVA"); code != http.StatusOK ||
		!reflect.DeepEqual(got, []string{"守望先锋 DVA 13"}) {
		t.Fatalf("规范输入联想=（%d, %#v）", code, got)
	}
	// q 规范化空 → 200 空列表（非 nil）。
	for _, q := range []string{"", "   "} {
		if code, got := nameSuggestions(t, env, env.libID, q); code != http.StatusOK || got == nil || len(got) != 0 {
			t.Fatalf("空 q %q=（%d, %#v）, want（200, 空列表）", q, code, got)
		}
	}
	// 无命中 → 200 空列表。
	if code, got := nameSuggestions(t, env, env.libID, "绝不存在的名"); code != http.StatusOK || len(got) != 0 {
		t.Fatalf("无命中=（%d, %#v）", code, got)
	}
	// libraryId 空 → 400；库不存在 → 404。
	if code, _ := nameSuggestions(t, env, "", "x"); code != http.StatusBadRequest {
		t.Fatalf("空 libraryId 期望 400，得到 %d", code)
	}
	if code, _ := nameSuggestions(t, env, "ghost-lib", "x"); code != http.StatusNotFound {
		t.Fatalf("未知库期望 404，得到 %d", code)
	}
}

// TestAssetsNameSuggestionsCosLibrary：cos 库同样可用（文件名联想是上传
// 通道能力，不受作者挂靠 normal-only 边界限制）。
func TestAssetsNameSuggestionsCosLibrary(t *testing.T) {
	env := newTestEnv(t)
	cosLib, err := env.q.CreateLibrary(context.Background(), db.CreateLibraryParams{
		ID: "cos-lib-namens", Name: "cos库", RootPath: t.TempDir(),
		Kind: "cos", CreatedAt: store.FormatTimestamp(env.clock.Now()),
	})
	if err != nil {
		t.Fatalf("建 cos 库失败: %v", err)
	}
	uploadAttach201(t, env.uploadAttach(t, cosLib.ID, "返图 1.jpg", makeJPG(t, t.TempDir(), 32, 32), nil))
	if code, got := nameSuggestions(t, env, cosLib.ID, "返图"); code != http.StatusOK ||
		!reflect.DeepEqual(got, []string{"返图 2"}) {
		t.Fatalf("cos 库联想=（%d, %#v）, want（200, [返图 2]）", code, got)
	}
}
