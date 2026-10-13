package httpapi

// authors_manage_test.go：作者管理端点（POST /authors、PUT/DELETE
// /authors/{authorId}，ADR-0024 修订 5，2026-10-13）的端到端测试。复用
// authors_test.go / upload_attach_test.go 的夹具与辅助函数。核心断言：
//   - 新建=复用/新行（不写片段不关联）；非法名 400 带原因；COS 撞名 409；
//   - 改名：块编号行重写 + 身份迁移（列表/片段/关联三面同口径）；
//   - 删除：三清（列表/片段/关联），文件与资产本体保留。

import (
	"net/http"
	"strings"
	"testing"
	"time"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// createAuthor POST /authors 按名新建（返回状态码与解出的 Author）。
func (e *testEnv) createAuthor(t *testing.T, displayName string) (int, gen.Author) {
	t.Helper()
	resp := e.do(t, "POST", "/api/v1/authors", `{"displayName":`+jsonQuote(displayName)+`}`)
	defer closeBody(resp)
	var out gen.Author
	if resp.StatusCode == http.StatusOK {
		if err := decodeBody(resp, &out); err != nil {
			t.Fatalf("解析新建作者响应失败: %v", err)
		}
	}
	return resp.StatusCode, out
}

// renameAuthor PUT /authors/{authorId} 改名（返回状态码与解出的 Author）。
func (e *testEnv) renameAuthor(t *testing.T, authorID, displayName string) (int, gen.Author) {
	t.Helper()
	resp := e.do(t, "PUT", "/api/v1/authors/"+authorID, `{"displayName":`+jsonQuote(displayName)+`}`)
	defer closeBody(resp)
	var out gen.Author
	if resp.StatusCode == http.StatusOK {
		if err := decodeBody(resp, &out); err != nil {
			t.Fatalf("解析改名响应失败: %v", err)
		}
	}
	return resp.StatusCode, out
}

// TestCreateAuthorEndpoint：新建（不写片段不关联）、规范身份复用（别名与
// 大小写变体归一）、非法名 400 带原因、COS 行撞名 409。
func TestCreateAuthorEndpoint(t *testing.T) {
	env := newTestEnv(t)

	code, a := env.createAuthor(t, "新作者甲")
	if code != http.StatusOK {
		t.Fatalf("新建期望 200，得到 %d", code)
	}
	if a.Id == nil || *a.Id != "新作者甲" || a.Type == nil || *a.Type != "regular" {
		t.Fatalf("新建响应异常: %+v", a)
	}
	if a.FileCount == nil || *a.FileCount != 0 {
		t.Fatalf("新建行 fileCount=%v, want 0", a.FileCount)
	}
	// 不写片段（库中无任何片段）。
	if _, ok := fragmentOf(t, env, authoring.AutoFragmentFilename); ok {
		t.Fatal("新建作者不应写片段")
	}

	// 复用：多别名/大小写变体归一到同一身份 → 不重复建行。
	for _, name := range []string{"新作者甲", "新作者甲 / 别名乙"} {
		code, a = env.createAuthor(t, name)
		if code != http.StatusOK || a.Id == nil || *a.Id != "新作者甲" {
			t.Fatalf("复用 %q 期望 200 且 id=新作者甲，得到 %d %+v", name, code, a)
		}
	}
	created := 0
	for _, row := range listAuthors(t, env) {
		if row.Id != nil && strings.Contains(*row.Id, "新作者") {
			created++
		}
	}
	if created != 1 {
		t.Fatalf("同身份重复创建应只有 1 行，得到 %d", created)
	}

	// 非法名：空 / 纯数字 / 媒体扩展名 → 400（message 含原因）。
	for _, name := range []string{"", "12345", "x.jpg"} {
		if code, _ := env.createAuthor(t, name); code != http.StatusBadRequest {
			t.Fatalf("非法名 %q 期望 400，得到 %d", name, code)
		}
	}

	// COS 行撞名 → 409（不静默复用也不覆盖）。
	if err := env.q.UpsertAuthor(t.Context(), upsertCosAuthorParams("cos_y2", "COS酱", env.clock.Now())); err != nil {
		t.Fatalf("预置 COS 行失败: %v", err)
	}
	if code, _ := env.createAuthor(t, "cos_y2"); code != http.StatusConflict {
		t.Fatalf("COS 撞名期望 409，得到 %d", code)
	}
}

// TestRenameAuthorEndpoint：改名端到端——同身份（块重写 + 显示名更新）、
// 身份迁移（新 id 上列表/片段/关联三面一致）、404/400/409。
func TestRenameAuthorEndpoint(t *testing.T) {
	env := newTestEnv(t)
	x := authoring.GenerateAuthorID("作者X")
	importTXT(t, env, "改名清单.txt", "1  作者X\n来源\nsite-a\n作品\na.jpg\n")
	d := uploadOne(t, env, "f.jpg")
	if resp := env.putAssetAuthors(t, d.Id.String(), []string{x}); resp.StatusCode != http.StatusOK {
		t.Fatalf("挂靠期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}

	// 同身份改名：加别名（首别名不变）→ 200、编号行重写、二行显示名更新。
	code, a := env.renameAuthor(t, x, "作者X / 别名乙")
	if code != http.StatusOK || a.Id == nil || *a.Id != x {
		t.Fatalf("同身份改名期望 200 id=%s，得到 %d %+v", x, code, a)
	}
	content, ok := fragmentOf(t, env, "改名清单.txt")
	if !ok {
		t.Fatal("片段缺失")
	}
	if !strings.Contains(content, "1  作者X  别名乙\n") {
		t.Fatalf("编号行未重写:\n%s", content)
	}
	// 作品行 = 导入的 a.jpg + 挂靠补写的 f.jpg（编辑新增关联的自愈口径），
	// 来源区零动。
	if works, sources := blockWorksOf(t, content, x); len(works) != 2 || len(sources) != 1 {
		t.Fatalf("作品/来源被误动: works=%v sources=%v", works, sources)
	}
	if row := findAuthor(t, listAuthors(t, env), x); row.DisplayName == nil || *row.DisplayName != "作者X / 别名乙" {
		t.Fatalf("列表显示名异常: %+v", row)
	}

	// 身份迁移：改成首别名不同的名字 → 新 id 全面接管（列表去旧、片段块
	// 改身份、资产关联改挂）。
	y := authoring.GenerateAuthorID("作者Y")
	code, a = env.renameAuthor(t, x, "作者Y")
	if code != http.StatusOK || a.Id == nil || *a.Id != y {
		t.Fatalf("身份迁移期望 200 id=%s，得到 %d %+v", y, code, a)
	}
	haystack := listAuthors(t, env)
	if _, found := findAuthorOptional(haystack, x); found {
		t.Fatalf("旧身份 %s 仍在作者列表", x)
	}
	// 关联随迁：a.jpg（导入匹配）+ f.jpg（挂靠补写）共 2 条。
	if row := findAuthor(t, haystack, y); row.FileCount == nil || *row.FileCount != 2 {
		t.Fatalf("新身份 fileCount=%v, want 2", row.FileCount)
	}
	content, _ = fragmentOf(t, env, "改名清单.txt")
	if works, _ := blockWorksOf(t, content, y); len(works) != 2 {
		t.Fatalf("改名后块身份/作品异常:\n%s", content)
	}
	detail := env.detail(t, d.Id.String())
	if authors := deref(detail.Authors); len(authors) != 1 || authors[0].Id == nil || *authors[0].Id != y {
		t.Fatalf("资产关联未改挂: %+v", detail.Authors)
	}

	// 404 / 400（非法名）/ 400（COS）/ 409（撞既有身份）。
	if code, _ := env.renameAuthor(t, "不存在", "x"); code != http.StatusNotFound {
		t.Fatalf("不存在期望 404，得到 %d", code)
	}
	if code, _ := env.renameAuthor(t, y, "x.jpg"); code != http.StatusBadRequest {
		t.Fatalf("非法名期望 400，得到 %d", code)
	}
	if err := env.q.UpsertAuthor(t.Context(), upsertCosAuthorParams("cos_y3", "COS酱", env.clock.Now())); err != nil {
		t.Fatalf("预置 COS 行失败: %v", err)
	}
	if code, _ := env.renameAuthor(t, "cos_y3", "随便"); code != http.StatusBadRequest {
		t.Fatalf("COS 期望 400，得到 %d", code)
	}
	z := authoring.GenerateAuthorID("作者Z")
	// 新内容保留已登记的作品行 f.jpg（否则重导入保护 409）——正好顺带锁定
	// 改名后台并条目的保护语义仍指向新身份。
	importTXT(t, env, "改名清单.txt", "1  作者Y\n作品\na.jpg\nf.jpg\n\n1  作者Z\n作品\nb.jpg\n")
	if code, _ := env.renameAuthor(t, y, "作者Z"); code != http.StatusConflict {
		t.Fatalf("撞既有身份期望 409（%s vs %s），得到 %d", y, z, code)
	}
}

// TestDeleteAuthorEndpoint：删除端到端——作者行/片段块/资产关联三清，
// 文件与资产本体保留；404/400（COS）。
func TestDeleteAuthorEndpoint(t *testing.T) {
	env := newTestEnv(t)
	x := authoring.GenerateAuthorID("删除甲")
	importTXT(t, env, "删除清单.txt", "1  删除甲\n来源\nsite-a\n作品\na.jpg\n\n\n\n2  保留乙\n作品\na.jpg\n")
	d := uploadOne(t, env, "f.jpg")
	if resp := env.putAssetAuthors(t, d.Id.String(), []string{x}); resp.StatusCode != http.StatusOK {
		t.Fatalf("挂靠期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}

	resp := env.do(t, "DELETE", "/api/v1/authors/"+x, "")
	closeBody(resp)
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("删除期望 204，得到 %d", resp.StatusCode)
	}
	// 作者行没了；片段无其块；资产关联解除；资产本体与文件仍可访问。
	if _, found := findAuthorOptional(listAuthors(t, env), x); found {
		t.Fatalf("删除后作者列表仍有 %s", x)
	}
	content, _ := fragmentOf(t, env, "删除清单.txt")
	if strings.Contains(content, "删除甲") {
		t.Fatalf("删除后片段仍有作者名:\n%s", content)
	}
	if works, _ := blockWorksOf(t, content, authoring.GenerateAuthorID("保留乙")); len(works) != 1 {
		t.Fatalf("相邻作者块被误伤:\n%s", content)
	}
	detail := env.detail(t, d.Id.String())
	for _, a := range deref(detail.Authors) {
		if a.Id != nil && *a.Id == x {
			t.Fatalf("删除后资产仍关联 %s", x)
		}
	}
	// 幂等边界：再删 → 404。
	resp = env.do(t, "DELETE", "/api/v1/authors/"+x, "")
	closeBody(resp)
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("重复删除期望 404，得到 %d", resp.StatusCode)
	}
	// COS → 400。
	if err := env.q.UpsertAuthor(t.Context(), upsertCosAuthorParams("cos_y4", "COS酱", env.clock.Now())); err != nil {
		t.Fatalf("预置 COS 行失败: %v", err)
	}
	resp = env.do(t, "DELETE", "/api/v1/authors/cos_y4", "")
	closeBody(resp)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("COS 删除期望 400，得到 %d", resp.StatusCode)
	}
}

// findAuthorOptional 宽松查（findAuthor 未命中即 Fatal，本文件需要「不存在」
// 作为合法断言）。
func findAuthorOptional(list []genAuthor, id string) (genAuthor, bool) {
	for _, a := range list {
		if a.Id != nil && *a.Id == id {
			return a, true
		}
	}
	return genAuthor{}, false
}

// upsertCosAuthorParams 预置 COS 作者行的参数（撞名/边界矩阵用）。
func upsertCosAuthorParams(id, display string, now time.Time) db.UpsertAuthorParams {
	return db.UpsertAuthorParams{
		ID: id, DisplayName: display, Type: authoring.AuthorTypeCos,
		CreatedAt: store.FormatTimestamp(now),
	}
}
