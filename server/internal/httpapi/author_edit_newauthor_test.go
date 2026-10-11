package httpapi

// 资产作者替换端点的「按名新建作者」通道（2026-10-11 协议修订）与两处系统性
// 修复的端到端测试：newAuthorNames 新建/复用、校验矩阵（逐名原因 400、超
// 20 项 400）、自愈补行（关联在 DB、片段缺行 → 再保存补回）、编辑写入登记
// （防覆盖式重导入静默冲掉编辑行）。复用 upload_attach_test.go /
// authors_attach_test.go 的夹具与辅助函数。

import (
	"context"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
)

// putAssetAuthorsWithNewNames PUT /assets/{assetId}/authors 带 newAuthorNames
// （nil=省略字段——旧客户端兼容输入的回归形态），返回响应（200 载荷为
// AssetDetail）。
func (e *testEnv) putAssetAuthorsWithNewNames(t *testing.T, assetID string, authorIDs, newNames []string) *http.Response {
	t.Helper()
	body := `{"authorIds":` + stringJSONArray(authorIDs)
	if newNames != nil {
		body += `,"newAuthorNames":` + stringJSONArray(newNames)
	}
	return e.do(t, "PUT", "/api/v1/assets/"+assetID+"/authors", body+`}`)
}

// damageFragment 直改片段原文（测试手术刀：模拟用户手工编辑/事故损坏片段，
// ImportedAt 原样保留）。
func damageFragment(t *testing.T, e *testEnv, filename, from, to string) {
	t.Helper()
	sources, err := authorattach.LoadSources(context.Background(), e.q)
	if err != nil {
		t.Fatalf("读取片段失败: %v", err)
	}
	found := false
	for i := range sources {
		if sources[i].Filename == filename {
			sources[i].Content = strings.Replace(sources[i].Content, from, to, 1)
			found = true
		}
	}
	if !found {
		t.Fatalf("片段 %q 不存在", filename)
	}
	if err := authorattach.PersistSources(context.Background(), e.q, e.clock.Now(), sources); err != nil {
		t.Fatalf("写回片段失败: %v", err)
	}
}

// authorOriginOf 裸查 authors 行的溯源章（ListAuthors 不暴露该列）。
func authorOriginOf(t *testing.T, e *testEnv, authorID string) string {
	t.Helper()
	var origin string
	if err := e.conn.QueryRowContext(context.Background(),
		"SELECT origin FROM authors WHERE id = ?", authorID).Scan(&origin); err != nil {
		t.Fatalf("查作者 %s 的 origin 失败: %v", authorID, err)
	}
	return origin
}

// TestAssetAuthorsReplaceCreateByName：authorIds 空 + newAuthorNames 一名 →
// 200、详情含新作者（origin=client）、片段出现新块（与上一块空三行 + 作品
// 行）、关联成立、authors 行 origin=client、既有块不受影响。
func TestAssetAuthorsReplaceCreateByName(t *testing.T) {
	env := newTestEnv(t)
	old := authoring.GenerateAuthorID("既有作者")
	importTXT(t, env, "清单.txt", "1  既有作者\n作品\na.jpg\n")
	d := uploadOne(t, env, "f.jpg")

	resp := env.putAssetAuthorsWithNewNames(t, d.Id.String(), nil, []string{"新作者甲"})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("按名新建期望 200，得到 %d", resp.StatusCode)
	}
	var detail gen.AssetDetail
	if err := decodeBody(resp, &detail); err != nil {
		t.Fatalf("解析详情失败: %v", err)
	}
	closeBody(resp)
	newID := authoring.GenerateAuthorID("新作者甲")
	if detail.Authors == nil || len(*detail.Authors) != 1 || *(*detail.Authors)[0].Id != newID {
		t.Fatalf("响应 authors 应为新作者全集：%v", detail.Authors)
	}
	if a := (*detail.Authors)[0]; a.Origin == nil || *a.Origin != store.OriginClient {
		t.Fatalf("新作者关联 origin=%v, want client", a.Origin)
	}

	// 片段：新块贴在既有块之后（空三行分隔）、作品行 = 资产文件名、解析
	// 回读 id 不漂移；既有块原样。
	content, ok := fragmentOf(t, env, "清单.txt")
	if !ok {
		t.Fatal("既有片段应保留")
	}
	if !strings.Contains(content, "\n\n\n\n2  新作者甲\n作品\nf.jpg") {
		t.Fatalf("新块应空三行分隔并含作品行:\n%s", content)
	}
	if works, _ := blockWorksOf(t, content, newID); len(works) != 1 || works[0] != "f.jpg" {
		t.Fatalf("新块作品行=%v, want [f.jpg]", works)
	}
	if works, _ := blockWorksOf(t, content, old); len(works) != 1 || works[0] != "a.jpg" {
		t.Fatalf("既有块作品行=%v, want [a.jpg]（不受影响）", works)
	}

	// 作者行：显示名与溯源章（authors 行章=client）。
	if a := findAuthor(t, listAuthors(t, env), newID); a.DisplayName == nil || *a.DisplayName != "新作者甲" {
		t.Fatalf("新作者行 displayName=%v, want 新作者甲", a.DisplayName)
	}
	if got := authorOriginOf(t, env, newID); got != store.OriginClient {
		t.Fatalf("authors 行 origin=%q, want %q", got, store.OriginClient)
	}
	// 关联成立（asset_authors）。
	refs, err := env.q.ListAssetAuthorRefs(context.Background(), d.Id.String())
	if err != nil || len(refs) != 1 || refs[0].ID != newID || refs[0].Type != authoring.AuthorTypeRegular {
		t.Fatalf("asset_authors=%v err=%v, want [{%s regular}]", refs, err, newID)
	}
}

// TestAssetAuthorsReplaceReuseExistingName：newAuthorNames 命中已有常规作者
// （大小写/别名拆分变体归一）→ 复用不重复建行，作品行并入既有块（不新建
// 块），无幻影作者。
func TestAssetAuthorsReplaceReuseExistingName(t *testing.T) {
	env := newTestEnv(t)
	night := authoring.GenerateAuthorID("Night") // "night"
	importTXT(t, env, "清单.txt", "1  Night  Cry\n作品\na.jpg\n")
	if before := listAuthors(t, env); len(before) != 1 {
		t.Fatalf("预置作者数=%d, want 1", len(before))
	}
	d := uploadOne(t, env, "f.jpg")

	// "night  cry"（双空格=别名分隔 + 大小写变体）→ 身份 night → 复用。
	resp := env.putAssetAuthorsWithNewNames(t, d.Id.String(), nil, []string{"night  cry"})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("重名复用期望 200，得到 %d", resp.StatusCode)
	}
	var detail gen.AssetDetail
	if err := decodeBody(resp, &detail); err != nil {
		t.Fatalf("解析详情失败: %v", err)
	}
	closeBody(resp)
	if detail.Authors == nil || len(*detail.Authors) != 1 || *(*detail.Authors)[0].Id != night {
		t.Fatalf("响应 authors 应复用 id=%s：%v", night, detail.Authors)
	}
	if after := listAuthors(t, env); len(after) != 1 {
		t.Fatalf("复用不得重复建行：%+v", after)
	}
	content, _ := fragmentOf(t, env, "清单.txt")
	if works, _ := blockWorksOf(t, content, night); len(works) != 2 || works[1] != "f.jpg" {
		t.Fatalf("既有块作品行=%v, want [a.jpg f.jpg]（并入非新建块）", works)
	}
	if n := len(authoring.ParseAuthorBlocks(content)); n != 1 {
		t.Fatalf("块数=%d, want 1（不新建块）:\n%s", n, content)
	}
	for _, a := range listAuthors(t, env) {
		if a.Id != nil && *a.Id == "night__cry" {
			t.Fatalf("出现幻影作者 night__cry：%+v", listAuthors(t, env))
		}
	}
}

// TestAssetAuthorsReplaceNewNameValidation：逐个非法名 400（消息含具体
// 原因）、超 20 项 400；全部被拒请求无片段/作者副作用。
func TestAssetAuthorsReplaceNewNameValidation(t *testing.T) {
	env := newTestEnv(t)
	importTXT(t, env, "清单.txt", "1  作者甲\n作品\na.jpg\n")
	d := uploadOne(t, env, "f.jpg")
	beforeContent, _ := fragmentOf(t, env, "清单.txt")
	beforeAuthors := len(listAuthors(t, env))

	longName := strings.Repeat("x", authoring.MaxNewAuthorNameRunes+1)
	cases := []struct {
		name string // 非法名
		want string // 400 消息应含的原因关键词
	}{
		{"", "为空"},
		{"   ", "为空"},
		{"x\ny", "含控制字符"},
		{"12345", "纯数字"},
		{"evil.jpg", "带媒体扩展名"},
		{longName, "超过 200"},
		{"1  foo", "编号行形态"},
		{"作品", "标记行形态"},
		{"来源", "标记行形态"},
		{"[3D]", "规范化后无有效别名"},
	}
	for _, c := range cases {
		resp := env.putAssetAuthorsWithNewNames(t, d.Id.String(), nil, []string{c.name})
		body := readAll(t, resp)
		if resp.StatusCode != http.StatusBadRequest {
			t.Fatalf("名 %q 期望 400，得到 %d: %s", c.name, resp.StatusCode, body)
		}
		if !strings.Contains(string(body), c.want) {
			t.Fatalf("名 %q 的 400 消息应含 %q：%s", c.name, c.want, body)
		}
	}
	// 超 20 项（项本身合法）→ 400。
	over := make([]string, maxNewAuthorNames+1)
	for i := range over {
		over[i] = "批作者" + string(rune('A'+i))
	}
	resp := env.putAssetAuthorsWithNewNames(t, d.Id.String(), nil, over)
	body := readAll(t, resp)
	if resp.StatusCode != http.StatusBadRequest || !strings.Contains(string(body), "20") {
		t.Fatalf("超项期望 400 且消息含上限，得到 %d: %s", resp.StatusCode, body)
	}
	// 无副作用：片段逐字不变、作者表不增、关联未建立。
	if after, _ := fragmentOf(t, env, "清单.txt"); after != beforeContent {
		t.Fatalf("被拒请求不得改动片段:\n%s", after)
	}
	if after := len(listAuthors(t, env)); after != beforeAuthors {
		t.Fatalf("被拒请求不得建作者行：%d → %d", beforeAuthors, after)
	}
	if refs, err := env.q.ListAssetAuthorRefs(context.Background(), d.Id.String()); err != nil || len(refs) != 0 {
		t.Fatalf("被拒请求不得建关联：refs=%v err=%v", refs, err)
	}
}

// TestAssetAuthorsReplaceSelfHealsMissingWorkLine：关联已在 DB、片段缺作品
// 行（用户真实事故）→ 再保存同作者集把行补回（幂等不重复），登记保护随
// 之保持。
func TestAssetAuthorsReplaceSelfHealsMissingWorkLine(t *testing.T) {
	env := newTestEnv(t)
	a := authoring.GenerateAuthorID("作者甲")
	importTXT(t, env, "清单.txt", "1  作者甲\n作品\na.jpg\n")
	d := uploadOne(t, env, "f.jpg")
	if resp := env.putAssetAuthors(t, d.Id.String(), []string{a}); resp.StatusCode != http.StatusOK {
		t.Fatalf("首次挂靠期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}
	// 事故：片段缺 f.jpg 行（关联仍在 DB）。
	damageFragment(t, env, "清单.txt", "f.jpg\n", "")
	if content, _ := fragmentOf(t, env, "清单.txt"); strings.Contains(content, "f.jpg") {
		t.Fatalf("手术刀应删掉作品行:\n%s", content)
	}

	// 编辑页再次保存（同作者集）→ 自愈补行。
	resp := env.putAssetAuthors(t, d.Id.String(), []string{a})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("再保存期望 200，得到 %d", resp.StatusCode)
	}
	closeBody(resp)
	content, _ := fragmentOf(t, env, "清单.txt")
	if works, _ := blockWorksOf(t, content, a); len(works) != 2 || works[1] != "f.jpg" {
		t.Fatalf("自愈后作品行=%v, want [a.jpg f.jpg]", works)
	}
	if n := strings.Count(content, "f.jpg"); n != 1 {
		t.Fatalf("自愈不得重复写行：f.jpg 出现 %d 次:\n%s", n, content)
	}
	// 登记保护仍在（行重新登记/保留）。
	entries, err := authorattach.LoadUploadEntries(context.Background(), env.q)
	if err != nil {
		t.Fatalf("读取条目失败: %v", err)
	}
	if got := entries["清单.txt"]; len(got) != 1 || len(got[0].Works) != 1 || got[0].Works[0] != "f.jpg" {
		t.Fatalf("自愈后条目元数据=%+v, want f.jpg 登记在册", got)
	}
}

// TestAuthorEditRegisteredEntriesProtectReimport：编辑写入的作品行被登记为
// 上传条目（无 seedUploadEntry，纯生产路径）→ 同文件名重导缺该行 → 409
// 二选一；keep 并回不丢行、关联保留。
func TestAuthorEditRegisteredEntriesProtectReimport(t *testing.T) {
	env := newTestEnv(t)
	x := authoring.GenerateAuthorID("作者X")
	stale := "1  作者X\n作品\na.jpg\n"
	importTXT(t, env, "清单B.txt", stale)
	d := uploadOne(t, env, "h.jpg")
	if resp := env.putAssetAuthors(t, d.Id.String(), []string{x}); resp.StatusCode != http.StatusOK {
		t.Fatalf("编辑挂靠期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}
	// 编辑路径已登记 h.jpg（重导入保护的比对输入）。
	entries, err := authorattach.LoadUploadEntries(context.Background(), env.q)
	if err != nil {
		t.Fatalf("读取条目失败: %v", err)
	}
	if got := entries["清单B.txt"]; len(got) != 1 || len(got[0].Works) != 1 || got[0].Works[0] != "h.jpg" {
		t.Fatalf("编辑写入的 h.jpg 应登记在册：%+v", got)
	}

	// 缺省重导（内容缺 h.jpg 行）→ 409，载荷含作者 x 与 h.jpg。
	code, _, conf := importTXTRes(t, env, "清单B.txt", stale, "")
	if code != http.StatusConflict {
		t.Fatalf("缺省重导期望 409，得到 %d", code)
	}
	authors := deref(conf.Authors)
	if len(authors) != 1 || authors[0].AuthorId == nil || *authors[0].AuthorId != x {
		t.Fatalf("409 载荷应含作者 %s：%+v", x, conf.Authors)
	}
	if w := deref(authors[0].Works); len(w) != 1 || w[0] != "h.jpg" {
		t.Fatalf("409 载荷缺失作品行应为 [h.jpg]：%v", w)
	}

	// keep：200、行并回、关联保留。
	code, res, _ := importTXTRes(t, env, "清单B.txt", stale, "keep")
	if code != http.StatusOK {
		t.Fatalf("keep 期望 200，得到 %d", code)
	}
	if res.merged() < 1 {
		t.Fatalf("keep 应报告并回行数≥1，得到 %d", res.merged())
	}
	content, _ := fragmentOf(t, env, "清单B.txt")
	if works, _ := blockWorksOf(t, content, x); len(works) != 2 || works[1] != "h.jpg" {
		t.Fatalf("keep 后片段作品行=%v, want [a.jpg h.jpg]", works)
	}
	if a := findAuthor(t, listAuthors(t, env), x); a.FileCount == nil || *a.FileCount != 2 {
		t.Fatalf("keep 后 fileCount=%v, want 2（关联保留）", a.FileCount)
	}
}

// TestLocalSyncKeepsRegisteredEditLines：本机同步通道恒 keep（ADR-0030）——
// 编辑路径登记的作品行在同文件名 TXT 经同步通道重导时被并回，不静默冲掉
// （与手动通道 409 二选一相对的保护形态）。
func TestLocalSyncKeepsRegisteredEditLines(t *testing.T) {
	env := newTestEnv(t)
	root := lsSetupSyncRoot(t, env)
	x := authoring.GenerateAuthorID("作者X")
	stale := "1  作者X\n作品\na.jpg\n"
	importTXT(t, env, "清单C.txt", stale)
	d := uploadOne(t, env, "h.jpg")
	if resp := env.putAssetAuthors(t, d.Id.String(), []string{x}); resp.StatusCode != http.StatusOK {
		t.Fatalf("编辑挂靠期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}
	// 同文件名旧版 TXT 落入同步根：导入（恒 keep）应并回编辑登记的 h.jpg。
	src := filepath.Join(root, "清单C.txt")
	if err := os.WriteFile(src, []byte(stale), 0o644); err != nil {
		t.Fatalf("写 TXT 失败: %v", err)
	}
	lsChtimesOld(t, src)
	lsRunCycles(t, env, 2)

	content, ok := fragmentOf(t, env, "清单C.txt")
	if !ok {
		t.Fatal("片段应存在")
	}
	if works, _ := blockWorksOf(t, content, x); len(works) != 2 || works[1] != "h.jpg" {
		t.Fatalf("同步通道 keep 后作品行=%v, want [a.jpg h.jpg]（登记行并回）", works)
	}
	if a := findAuthor(t, listAuthors(t, env), x); a.FileCount == nil || *a.FileCount != 2 {
		t.Fatalf("同步通道 keep 后 fileCount=%v, want 2（关联保留）", a.FileCount)
	}
}
