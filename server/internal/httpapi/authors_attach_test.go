package httpapi

// TXT 重导入保护 + 导出往返端到端测试（REQ-上传指定作者与来源 §3.3
// 第 10/11/12 条、§3.4）：同文件名重导缺上传条目 → 409 载荷（只列缺失项）；
// keep 并回 / remove 明示移除；导出内容原样可再导入（不触发保护）；
// 列表带 importedAt。复用 authors_test.go 的夹具与辅助函数。

import (
	"net/http"
	"strings"
	"testing"

	"qimeng-media/server/internal/authoring"
)

// genTxtImportResultFull 是 gen.TxtImportResult 的宽松投影（含 keep 并回
// 计数）。
type genTxtImportResultFull struct {
	AuthorsImported     *int `json:"authorsImported"`
	FilesMatched        *int `json:"filesMatched"`
	MergedUploadEntries *int `json:"mergedUploadEntries"`
}

func (r genTxtImportResultFull) merged() int {
	if r.MergedUploadEntries == nil {
		return 0
	}
	return *r.MergedUploadEntries
}

// genTxtConflictBody 是 409 TXT_CONFLICT 载荷的宽松投影。
type genTxtConflictBody struct {
	Filename *string `json:"filename"`
	Authors  *[]struct {
		AuthorId    *string   `json:"authorId"`
		DisplayName *string   `json:"displayName"`
		Works       *[]string `json:"works"`
		Sources     *[]string `json:"sources"`
	} `json:"authors"`
}

// importTXTRes 导入 TXT 并返回（状态码, 成功载荷, 409 载荷）；filename 为
// 空串时省略（匿名导入），resolution 空串时不带 conflictResolution。
func importTXTRes(t *testing.T, e *testEnv, filename, content, resolution string) (int, genTxtImportResultFull, genTxtConflictBody) {
	t.Helper()
	body := "{"
	if filename != "" {
		body += `"filename":` + jsonQuote(filename) + `,`
	}
	body += `"content":` + jsonQuote(content)
	if resolution != "" {
		body += `,"conflictResolution":` + jsonQuote(resolution)
	}
	body += `}`
	resp := e.do(t, "POST", "/api/v1/authors/import-txt", body)
	defer closeBody(resp)
	var res genTxtImportResultFull
	var conf genTxtConflictBody
	switch resp.StatusCode {
	case http.StatusOK:
		if err := decodeBody(resp, &res); err != nil {
			t.Fatalf("解析导入结果失败: %v", err)
		}
	case http.StatusConflict:
		if err := decodeBody(resp, &conf); err != nil {
			t.Fatalf("解析 409 载荷失败: %v", err)
		}
	}
	return resp.StatusCode, res, conf
}

// TestImportTxtConflictFlow（验收 #11）：编辑挂靠 + 存量上传条目后重导同
// 文件名旧版清单——缺省 409（载荷含作者与缺失作品行）；keep 并回（片段
// 完整、关联保留、mergedUploadEntries≥1）；remove 明示移除（行与关联消失、
// 元数据清除后不再 409）；非法 resolution 400。
// 上传挂靠已退役（ADR-0024 上传减法）：片段行经编辑端点写入，上传条目
// 用 seedUploadEntry 模拟存量数据。
func TestImportTxtConflictFlow(t *testing.T) {
	env := newTestEnv(t)
	x := authoring.GenerateAuthorID("作者X")
	stale := "1  作者X\n作品\na.jpg\n"
	importTXT(t, env, "清单A.txt", stale)

	d := uploadOne(t, env, "f.jpg")
	if resp := env.putAssetAuthors(t, d.Id.String(), []string{x}); resp.StatusCode != http.StatusOK {
		t.Fatalf("编辑挂靠期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}
	seedUploadEntry(t, env, "清单A.txt", authoring.UploadEntry{
		AuthorID: x, DisplayName: "作者X", Names: []string{"作者X"}, Works: []string{"f.jpg"},
	})
	if a := findAuthor(t, listAuthors(t, env), x); a.FileCount == nil || *a.FileCount != 2 {
		t.Fatalf("挂靠后 fileCount=%v, want 2", a.FileCount)
	}

	// 缺省重导（内容缺 f.jpg 行）→ 409，载荷含作者 id 与 f.jpg。
	code, _, conf := importTXTRes(t, env, "清单A.txt", stale, "")
	if code != http.StatusConflict {
		t.Fatalf("缺省重导期望 409，得到 %d", code)
	}
	if conf.Filename == nil || *conf.Filename != "清单A.txt" {
		t.Fatalf("409 载荷 filename=%v", conf.Filename)
	}
	authors := deref(conf.Authors)
	if len(authors) != 1 || authors[0].AuthorId == nil || *authors[0].AuthorId != x {
		t.Fatalf("409 载荷应含作者 %s：%+v", x, conf.Authors)
	}
	if w := deref(authors[0].Works); len(w) != 1 || w[0] != "f.jpg" {
		t.Fatalf("409 载荷缺失作品行应为 [f.jpg]：%v", w)
	}

	// keep：200、片段含 f.jpg、并回计数≥1、关联保留。
	code, res, _ := importTXTRes(t, env, "清单A.txt", stale, "keep")
	if code != http.StatusOK {
		t.Fatalf("keep 期望 200，得到 %d", code)
	}
	if res.merged() < 1 {
		t.Fatalf("keep 应报告并回行数≥1，得到 %d", res.merged())
	}
	content, _ := fragmentOf(t, env, "清单A.txt")
	if works, _ := blockWorksOf(t, content, x); len(works) != 2 {
		t.Fatalf("keep 后片段作品行=%v, want [a.jpg f.jpg]", works)
	}
	if a := findAuthor(t, listAuthors(t, env), x); a.FileCount == nil || *a.FileCount != 2 {
		t.Fatalf("keep 后 fileCount=%v, want 2（关联保留）", a.FileCount)
	}

	// remove：200、f.jpg 行与关联消失、元数据清除 → 再次缺省重导 200。
	code, _, _ = importTXTRes(t, env, "清单A.txt", stale, "remove")
	if code != http.StatusOK {
		t.Fatalf("remove 期望 200，得到 %d", code)
	}
	content, _ = fragmentOf(t, env, "清单A.txt")
	if works, _ := blockWorksOf(t, content, x); len(works) != 1 || works[0] != "a.jpg" {
		t.Fatalf("remove 后片段作品行=%v, want [a.jpg]", works)
	}
	if a := findAuthor(t, listAuthors(t, env), x); a.FileCount == nil || *a.FileCount != 1 {
		t.Fatalf("remove 后 fileCount=%v, want 1（明示移除生效）", a.FileCount)
	}
	if code, _, _ = importTXTRes(t, env, "清单A.txt", stale, ""); code != http.StatusOK {
		t.Fatalf("remove 后再次缺省重导期望 200（保护已清除），得到 %d", code)
	}

	// 非法 resolution → 400。
	if code, _, _ = importTXTRes(t, env, "清单A.txt", stale, "bogus"); code != http.StatusBadRequest {
		t.Fatalf("非法 resolution 期望 400，得到 %d", code)
	}
}

// TestImportTxtConflictMultiAuthor（验收 #12）：keep 下多作者多文件——
// 409 只列缺失项（既有行不进载荷），来源缺失也列出；keep 后全部并回。
func TestImportTxtConflictMultiAuthor(t *testing.T) {
	env := newTestEnv(t)
	m := authoring.GenerateAuthorID("作者M")
	n := authoring.GenerateAuthorID("作者N")
	base := "1  作者M\n作品\na.jpg\nb.jpg\n\n2  作者N\n作品\nc.mp4\n"
	importTXT(t, env, "多作者.txt", base)

	// 编辑挂靠两资产到两位作者，M 追加来源行（编辑端点写入片段真相）。
	g1 := uploadOne(t, env, "g1.jpg")
	g2 := uploadOne(t, env, "g2.jpg")
	if resp := env.putAssetAuthors(t, g1.Id.String(), []string{m}); resp.StatusCode != http.StatusOK {
		t.Fatalf("M 挂靠期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}
	if resp := env.putAuthorSources(t, m, []string{"forum-c"}); resp.StatusCode != http.StatusOK {
		t.Fatalf("M 来源区写入期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}
	if resp := env.putAssetAuthors(t, g2.Id.String(), []string{n}); resp.StatusCode != http.StatusOK {
		t.Fatalf("N 挂靠期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}
	// 存量上传条目（挂靠退役后条目只剩历史数据；重导入保护的比对输入）。
	seedUploadEntry(t, env, "多作者.txt", authoring.UploadEntry{
		AuthorID: m, DisplayName: "作者M", Names: []string{"作者M"}, Works: []string{"g1.jpg"}, Sources: []string{"forum-c"},
	})
	seedUploadEntry(t, env, "多作者.txt", authoring.UploadEntry{
		AuthorID: n, DisplayName: "作者N", Names: []string{"作者N"}, Works: []string{"g2.jpg"},
	})

	code, _, conf := importTXTRes(t, env, "多作者.txt", base, "")
	if code != http.StatusConflict {
		t.Fatalf("期望 409，得到 %d", code)
	}
	authors := deref(conf.Authors)
	if len(authors) != 2 {
		t.Fatalf("409 载荷应只含 2 位有缺失的作者：%+v", conf.Authors)
	}
	byID := map[string]int{}
	for i, a := range authors {
		if a.AuthorId == nil {
			t.Fatal("409 载荷缺 authorId")
		}
		byID[*a.AuthorId] = i
	}
	// 作者M：只缺 g1.jpg（a.jpg/b.jpg 在旧版清单里，不进载荷）+ 来源行。
	if w := deref(authors[byID[m]].Works); len(w) != 1 || w[0] != "g1.jpg" {
		t.Fatalf("作者M 缺失作品应只 [g1.jpg]：%v", w)
	}
	if s := deref(authors[byID[m]].Sources); len(s) != 1 || s[0] != "forum-c" {
		t.Fatalf("作者M 缺失来源应只 [forum-c]：%v", s)
	}
	if w := deref(authors[byID[n]].Works); len(w) != 1 || w[0] != "g2.jpg" {
		t.Fatalf("作者N 缺失作品应只 [g2.jpg]：%v", w)
	}

	// keep：并回 3 行（2 作品 + 1 来源），两位作者关联完整。
	code, res, _ := importTXTRes(t, env, "多作者.txt", base, "keep")
	if code != http.StatusOK {
		t.Fatalf("keep 期望 200，得到 %d", code)
	}
	if res.merged() != 3 {
		t.Fatalf("keep 并回行数=%d, want 3（2 作品行 + 1 来源行）", res.merged())
	}
	content, _ := fragmentOf(t, env, "多作者.txt")
	if works, srcs := blockWorksOf(t, content, m); len(works) != 3 || len(srcs) != 1 {
		t.Fatalf("作者M keep 后 works=%v sources=%v, want 3/1", works, srcs)
	}
	if works, _ := blockWorksOf(t, content, n); len(works) != 2 {
		t.Fatalf("作者N keep 后 works=%v, want 2", works)
	}
	for id, want := range map[string]int{m: 3, n: 2} {
		if a := findAuthor(t, listAuthors(t, env), id); a.FileCount == nil || *a.FileCount != want {
			t.Fatalf("keep 后 %s fileCount=%v, want %d", id, a.FileCount, want)
		}
	}
}

// TestExportTxtRoundTrip（验收 #13）：导出 body 与服务端片段逐字一致；
// 导出内容不做修改直接重导（不带 resolution）→ 200 不触发 409；不存在的
// 片段 404。
func TestExportTxtRoundTrip(t *testing.T) {
	env := newTestEnv(t)
	aid := authoring.GenerateAuthorID("导出作者")
	importTXT(t, env, "E.txt", "1  导出作者\n作品\na.jpg\n")
	d := uploadOne(t, env, "e1.jpg")
	if resp := env.putAssetAuthors(t, d.Id.String(), []string{aid}); resp.StatusCode != http.StatusOK {
		t.Fatalf("编辑挂靠期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}

	resp := env.do(t, "GET", "/api/v1/authors/import-txt/export?filename=E.txt", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("导出期望 200，得到 %d", resp.StatusCode)
	}
	if ct := resp.Header.Get("Content-Type"); ct != "text/plain; charset=utf-8" {
		t.Fatalf("导出 Content-Type=%q", ct)
	}
	exported := string(readAll(t, resp))
	frag, _ := fragmentOf(t, env, "E.txt")
	if exported != frag {
		t.Fatalf("导出内容与片段不一致：\n--- 导出 ---\n%s\n--- 片段 ---\n%s", exported, frag)
	}
	if !containsLine(exported, "e1.jpg") {
		t.Fatal("导出内容应包含上传挂靠条目（往返一致的前提）")
	}

	// 导出内容原样重导 → 200 不触发保护（上传条目都在导出内容里）。
	if code, _, _ := importTXTRes(t, env, "E.txt", exported, ""); code != http.StatusOK {
		t.Fatalf("导出内容重导期望 200 不触发 409，得到 %d", code)
	}
	if a := findAuthor(t, listAuthors(t, env), aid); a.FileCount == nil || *a.FileCount != 2 {
		t.Fatalf("往返后 fileCount=%v, want 2（无丢失无重复）", a.FileCount)
	}

	resp = env.do(t, "GET", "/api/v1/authors/import-txt/export?filename=ghost.txt", "")
	closeBody(resp)
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("导出不存在的片段期望 404，得到 %d", resp.StatusCode)
	}
}

// TestImportTxtListImportedAt：GET /authors/import-txt 条目带 importedAt
// （RFC3339）；格式 C 导入不存片段（列表不变）。
func TestImportTxtListImportedAt(t *testing.T) {
	env := newTestEnv(t)
	importTXT(t, env, "t.txt", "1  作者甲\n作品\na.jpg\n")

	resp := env.do(t, "GET", "/api/v1/authors/import-txt", "")
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("列表期望 200，得到 %d", resp.StatusCode)
	}
	var items []struct {
		Filename   string  `json:"filename"`
		ImportedAt *string `json:"importedAt"`
	}
	if err := decodeBody(resp, &items); err != nil {
		t.Fatalf("解析列表失败: %v", err)
	}
	closeBody(resp)
	if len(items) != 1 || items[0].Filename != "t.txt" {
		t.Fatalf("列表=%+v, want 1 份 t.txt", items)
	}
	if items[0].ImportedAt == nil || *items[0].ImportedAt == "" {
		t.Fatalf("列表条目应带 importedAt：%+v", items[0])
	}

	// 格式 C：只建作者不存片段（列表长度不变）。
	importTXT(t, env, "c.txt", "纯粹作者丙\n")
	if got := listTxt(t, env); len(got) != 1 {
		t.Fatalf("格式 C 不应存片段，列表=%v", got)
	}
}

// containsLine 判断整行存在（测试侧简易断言，非解析器语义）。
func containsLine(content, line string) bool {
	for _, l := range strings.Split(content, "\n") {
		if strings.TrimSpace(l) == line {
			return true
		}
	}
	return false
}
