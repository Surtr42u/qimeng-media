package httpapi

// 资产编辑端到端测试（ADR-0024 + REQ-上传指定作者与来源 §3.4）：上传不再
// 携带挂靠参数（上传减法），作者关联/来源经编辑端点写入片段真相；通用
// 来源词表 CRUD 与校验矩阵、单作者来源区读写、资产作者关联整体替换、
// 统一重建保留、自动镜像。复用 browse_test.go 的 newTestEnv（setup +
// 假扫描入库 a.jpg/b.jpg/c.mp4）。

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// uploadAttach 发起流式上传（extra 为额外 query 参数）。
func (e *testEnv) uploadAttach(t *testing.T, libraryID, filename string, body []byte, extra url.Values) *http.Response {
	t.Helper()
	q := url.Values{}
	q.Set("libraryId", libraryID)
	q.Set("dir", "") // 生成物绑定要求 dir 显式出现（uploadReader 同款）
	q.Set("filename", filename)
	for k, vs := range extra {
		for _, v := range vs {
			q.Add(k, v)
		}
	}
	req, err := http.NewRequest(http.MethodPost, e.ts.URL+"/api/v1/assets/upload?"+q.Encode(), strings.NewReader(string(body)))
	if err != nil {
		t.Fatalf("构造上传请求失败: %v", err)
	}
	req.Header.Set("Authorization", "Bearer "+e.token)
	req.Header.Set("Content-Type", "application/octet-stream")
	return e.doRaw(t, req)
}

// uploadAttach201 断言上传 201 并解出资产详情（失败时打印响应体定位）。
func uploadAttach201(t *testing.T, resp *http.Response) gen.AssetDetail {
	t.Helper()
	defer closeBody(resp)
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("读上传响应失败: %v", err)
	}
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("上传期望 201，得到 %d: %s", resp.StatusCode, body)
	}
	var d gen.AssetDetail
	if err := json.Unmarshal(body, &d); err != nil {
		t.Fatalf("解析上传响应失败: %v", err)
	}
	return d
}

// uploadOne 上传一张随机 JPG 并返回资产详情（编辑用例的资产供给助手）。
func uploadOne(t *testing.T, e *testEnv, filename string) gen.AssetDetail {
	t.Helper()
	jpg := makeJPG(t, t.TempDir(), 64, 64)
	return uploadAttach201(t, e.uploadAttach(t, e.libID, filename, jpg, nil))
}

// putAssetAuthors PUT /assets/{assetId}/authors（authorIds 编码为 JSON 数组，
// nil=空数组），返回响应（200 载荷为 AssetDetail）。
func (e *testEnv) putAssetAuthors(t *testing.T, assetID string, authorIDs []string) *http.Response {
	t.Helper()
	if authorIDs == nil {
		authorIDs = []string{}
	}
	ids, err := json.Marshal(authorIDs)
	if err != nil {
		t.Fatalf("编码 authorIds 失败: %v", err)
	}
	return e.do(t, "PUT", "/api/v1/assets/"+assetID+"/authors", `{"authorIds":`+string(ids)+`}`)
}

// putAuthorSources PUT /authors/{authorId}/sources，返回响应。
func (e *testEnv) putAuthorSources(t *testing.T, authorID string, sources []string) *http.Response {
	t.Helper()
	return e.do(t, "PUT", "/api/v1/authors/"+authorID+"/sources", `{"sources":`+stringJSONArray(sources)+`}`)
}

// getAuthorSources GET /authors/{authorId}/sources →（状态码, 来源列表）。
func getAuthorSources(t *testing.T, e *testEnv, authorID string) (int, []string) {
	t.Helper()
	resp := e.do(t, "GET", "/api/v1/authors/"+authorID+"/sources", "")
	defer closeBody(resp)
	var body gen.SourceVocabulary
	if err := decodeBody(resp, &body); err != nil {
		t.Fatalf("解析来源区失败: %v", err)
	}
	return resp.StatusCode, body.Sources
}

// getVocabulary GET /authors/source-vocabulary →（状态码, 词表）。
func getVocabulary(t *testing.T, e *testEnv) (int, []string) {
	t.Helper()
	resp := e.do(t, "GET", "/api/v1/authors/source-vocabulary", "")
	defer closeBody(resp)
	var body gen.SourceVocabulary
	if err := decodeBody(resp, &body); err != nil {
		t.Fatalf("解析词表失败: %v", err)
	}
	return resp.StatusCode, body.Sources
}

// stringJSONArray 编码 JSON 字符串数组（nil → []）。
func stringJSONArray(items []string) string {
	if items == nil {
		items = []string{}
	}
	b, err := json.Marshal(items)
	if err != nil {
		panic(err) // []string 的 Marshal 永不失败
	}
	return string(b)
}

// seedUploadEntry 直写 kv 的上传条目（上传挂靠已退役，条目只剩存量；测试
// 用它模拟存量数据以驱动重导入保护路径）。
func seedUploadEntry(t *testing.T, e *testEnv, fragment string, entry authoring.UploadEntry) {
	t.Helper()
	entries, err := authorattach.LoadUploadEntries(context.Background(), e.q)
	if err != nil {
		t.Fatalf("读取上传条目失败: %v", err)
	}
	entries[fragment] = append(entries[fragment], entry)
	if err := authorattach.SaveUploadEntries(context.Background(), e.q, time.Now(), entries); err != nil {
		t.Fatalf("写上传条目失败: %v", err)
	}
}

// fragmentOf 读取指定片段原文（库中无该片段 → found=false）。
func fragmentOf(t *testing.T, e *testEnv, filename string) (string, bool) {
	t.Helper()
	sources, err := authorattach.LoadSources(context.Background(), e.q)
	if err != nil {
		t.Fatalf("读取已导入片段失败: %v", err)
	}
	for _, src := range sources {
		if src.Filename == filename {
			return src.Content, true
		}
	}
	return "", false
}

// blockWorksOf 解析片段并返回指定作者块的作品行/来源行（REQ §3.3 第 9 条：
// 编辑写入必须能被统一重建的同一套解析器读回）。
func blockWorksOf(t *testing.T, content, authorID string) (works, sources []string) {
	t.Helper()
	for _, b := range authoring.ParseAuthorBlocks(content) {
		if len(b.AuthorNames) > 0 && authoring.GenerateAuthorID(b.AuthorNames[0]) == authorID {
			return b.Works, b.Sources
		}
	}
	t.Fatalf("片段中不存在作者 %s 的块:\n%s", authorID, content)
	return nil, nil
}

// TestUploadNoAttachParamsUnchanged：上传（无挂靠参数可传）→ 行为不变：
// 201、不建片段、响应 Authors 空数组、无作者行。
func TestUploadNoAttachParamsUnchanged(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 64, 64)

	d := uploadAttach201(t, env.uploadAttach(t, env.libID, "plain.jpg", jpg, nil))
	if d.RelPath == nil || *d.RelPath != "plain.jpg" {
		t.Fatalf("relPath=%v, want plain.jpg", d.RelPath)
	}
	if sources, err := authorattach.LoadSources(context.Background(), env.q); err != nil || len(sources) != 0 {
		t.Fatalf("上传不应产生片段：sources=%v err=%v", sources, err)
	}
	if d.Authors == nil || len(*d.Authors) != 0 {
		t.Fatalf("响应 Authors 应为空数组：%v", d.Authors)
	}
	if list := listAuthors(t, env); len(list) != 0 {
		t.Fatalf("不应产生作者行：%v", list)
	}
}

// TestAuthorsSuggest：别名片段与大小写变体命中同一作者；不存在的词 → 空列表；
// limit 越界 400、limit 截断生效；COS 作者与零关联常规作者的口径（regular
// 全集、含零关联）。
func TestAuthorsSuggest(t *testing.T) {
	env := newTestEnv(t)
	kami := authoring.GenerateAuthorID("kamihikoki_mmd")
	importTXT(t, env, "a.txt", "1  kamihikoki_mmd  紙飛行機(site-b资源出处)\n作品\na.jpg\n")
	importTXT(t, env, "c.txt", "纯粹零关联\n") // 格式 C：零关联常规作者
	if err := env.q.UpsertAuthor(context.Background(), db.UpsertAuthorParams{
		ID: authoring.GenerateCosAuthorID("COS酱"), DisplayName: "COS酱",
		Type: authoring.AuthorTypeCos, CreatedAt: store.FormatTimestamp(env.clock.Now()),
	}); err != nil {
		t.Fatalf("造 COS 作者失败: %v", err)
	}

	suggest := func(t *testing.T, query string) []gen.AuthorSuggest {
		t.Helper()
		resp := env.do(t, "GET", "/api/v1/authors/suggest?q="+url.QueryEscape(query), "")
		defer closeBody(resp)
		if resp.StatusCode != http.StatusOK {
			t.Fatalf("suggest %q 期望 200，得到 %d", query, resp.StatusCode)
		}
		var out []gen.AuthorSuggest
		if err := decodeBody(resp, &out); err != nil {
			t.Fatalf("解析 suggest 失败: %v", err)
		}
		return out
	}

	// 别名片段（紙飛）与身份名大小写变体（KAMI）命中同一作者。
	for _, q := range []string{"紙飛", "KAMI", "kamihikoki"} {
		got := suggest(t, q)
		if len(got) != 1 || got[0].Id == nil || *got[0].Id != kami {
			t.Fatalf("suggest %q 应唯一命中 kami（别名/大小写归并），得到 %+v", q, got)
		}
	}
	// 零关联常规作者可命中（联想含零关联者）。
	if got := suggest(t, "零关联"); len(got) != 1 {
		t.Fatalf("零关联作者应可命中：%v", got)
	}
	// 不存在的词 → 空列表（非 nil 数组）。
	if got := suggest(t, "绝不存在的词"); len(got) != 0 {
		t.Fatalf("无命中应空列表：%v", got)
	}
	// COS 作者不参与联想。
	if got := suggest(t, "COS"); len(got) != 0 {
		t.Fatalf("COS 作者不应出现在联想：%v", got)
	}
	// limit 截断。
	resp := env.do(t, "GET", "/api/v1/authors/suggest?q="+url.QueryEscape("作者")+"&limit=1", "")
	var limited []gen.AuthorSuggest
	_ = decodeBody(resp, &limited)
	closeBody(resp)
	if len(limited) > 1 {
		t.Fatalf("limit=1 应至多 1 条：%v", limited)
	}
	// limit 越界（0 / 51+）→ 400。
	for _, l := range []string{"0", "51", "999"} {
		resp := env.do(t, "GET", "/api/v1/authors/suggest?q=a&limit="+l, "")
		closeBody(resp)
		if resp.StatusCode != http.StatusBadRequest {
			t.Fatalf("limit=%s 期望 400，得到 %d", l, resp.StatusCode)
		}
	}
}

// TestAssetAuthorsReplaceBasic（原上传挂靠主链路改造）：两资产经编辑端点
// 挂到同一作者 → 200；零片段库自动创建承载片段并写入作品行；作者
// fileCount=2；响应与 asset_authors 各带回全集；统一重建后保留不翻倍。
func TestAssetAuthorsReplaceBasic(t *testing.T) {
	env := newTestEnv(t)
	aid := authoring.GenerateAuthorID("作者甲")
	importTXT(t, env, "c.txt", "作者甲\n") // 格式 C：零关联常规作者（无片段）

	d1 := uploadOne(t, env, "f1.jpg")
	d2 := uploadOne(t, env, "f2.jpg")

	resp := env.putAssetAuthors(t, d1.Id.String(), []string{aid})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("替换资产作者期望 200，得到 %d", resp.StatusCode)
	}
	var detail gen.AssetDetail
	if err := decodeBody(resp, &detail); err != nil {
		t.Fatalf("解析详情失败: %v", err)
	}
	closeBody(resp)
	if detail.Authors == nil || len(*detail.Authors) != 1 || *(*detail.Authors)[0].Id != aid {
		t.Fatalf("响应 authors 应为替换后全集：%v", detail.Authors)
	}
	closeBody(env.putAssetAuthors(t, d2.Id.String(), []string{aid}))

	// 格式 C 导入不存片段：零片段库首次编辑 → 自动创建承载片段。
	if _, ok := fragmentOf(t, env, "c.txt"); ok {
		t.Fatal("格式 C 导入不应存片段")
	}
	content, ok := fragmentOf(t, env, authoring.AutoFragmentFilename)
	if !ok {
		t.Fatal("无任何片段时应自动创建承载片段")
	}
	works, _ := blockWorksOf(t, content, aid)
	if len(works) != 2 || works[0] != "f1.jpg" || works[1] != "f2.jpg" {
		t.Fatalf("片段作品行=%v, want [f1.jpg f2.jpg]（资产文件名）", works)
	}
	if a := findAuthor(t, listAuthors(t, env), aid); a.FileCount == nil || *a.FileCount != 2 {
		t.Fatalf("挂靠后 fileCount=%v, want 2", a.FileCount)
	}
	for _, d := range []gen.AssetDetail{d1, d2} {
		refs, err := env.q.ListAssetAuthorRefs(context.Background(), d.Id.String())
		if err != nil {
			t.Fatalf("查询关联失败: %v", err)
		}
		if len(refs) != 1 || refs[0].ID != aid || refs[0].Type != authoring.AuthorTypeRegular {
			t.Fatalf("asset_authors 关联=%v, want [{%s regular}]", refs, aid)
		}
	}
	// 统一重建：关联保留且不翻倍（幂等）。
	for i := 0; i < 2; i++ {
		if _, _, code := rebuildTxt(t, env); code != 200 {
			t.Fatalf("重建期望 200，得到 %d", code)
		}
		if a := findAuthor(t, listAuthors(t, env), aid); a.FileCount == nil || *a.FileCount != 2 {
			t.Fatalf("第 %d 次重建后 fileCount=%v, want 2（保留不翻倍）", i+1, a.FileCount)
		}
	}
}

// TestAssetAuthorsReplaceDiff：整体替换的 diff 语义——新增并作品行、移除
// 遍历全部片段删作品行；空数组=解除全部常规作者关联。
func TestAssetAuthorsReplaceDiff(t *testing.T) {
	env := newTestEnv(t)
	a := authoring.GenerateAuthorID("作者A")
	b := authoring.GenerateAuthorID("作者B")
	importTXT(t, env, "清单.txt", "1  作者A\n作品\na.jpg\n\n2  作者B\n作品\nb.jpg\n")

	d := uploadOne(t, env, "f.jpg")
	// 挂到 A：片段 A 块作品行追加 f.jpg。
	if resp := env.putAssetAuthors(t, d.Id.String(), []string{a}); resp.StatusCode != http.StatusOK {
		t.Fatalf("挂到 A 期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}
	content, _ := fragmentOf(t, env, "清单.txt")
	if works, _ := blockWorksOf(t, content, a); len(works) != 2 || works[1] != "f.jpg" {
		t.Fatalf("A 块作品行=%v, want [a.jpg f.jpg]", works)
	}
	// 改挂到 B：A 块删 f.jpg、B 块增 f.jpg。
	if resp := env.putAssetAuthors(t, d.Id.String(), []string{b}); resp.StatusCode != http.StatusOK {
		t.Fatalf("改挂到 B 期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}
	content, _ = fragmentOf(t, env, "清单.txt")
	if works, _ := blockWorksOf(t, content, a); len(works) != 1 || works[0] != "a.jpg" {
		t.Fatalf("A 块作品行=%v, want [a.jpg]（移除生效）", works)
	}
	if works, _ := blockWorksOf(t, content, b); len(works) != 2 || works[1] != "f.jpg" {
		t.Fatalf("B 块作品行=%v, want [b.jpg f.jpg]", works)
	}
	// 空数组=解除全部关联。
	resp := env.putAssetAuthors(t, d.Id.String(), []string{})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("清空关联期望 200，得到 %d", resp.StatusCode)
	}
	var detail gen.AssetDetail
	if err := decodeBody(resp, &detail); err != nil {
		t.Fatalf("解析详情失败: %v", err)
	}
	closeBody(resp)
	if detail.Authors == nil || len(*detail.Authors) != 0 {
		t.Fatalf("响应 authors 应为空数组：%v", detail.Authors)
	}
	content, _ = fragmentOf(t, env, "清单.txt")
	if works, _ := blockWorksOf(t, content, b); len(works) != 1 || works[0] != "b.jpg" {
		t.Fatalf("清空后 B 块作品行=%v, want [b.jpg]", works)
	}
	if ba := findAuthor(t, listAuthors(t, env), b); ba.FileCount == nil || *ba.FileCount != 1 {
		t.Fatalf("清空后 B fileCount=%v, want 1（b.jpg 既有关联不动）", ba.FileCount)
	}
}

// TestAssetAuthorsEditPrunesUploadEntries：编辑移除后同步修剪上传条目
// 元数据——同文件名旧版重导入不再对被编辑移除的行误报 409（ADR-0024）。
func TestAssetAuthorsEditPrunesUploadEntries(t *testing.T) {
	env := newTestEnv(t)
	x := authoring.GenerateAuthorID("作者X")
	stale := "1  作者X\n作品\na.jpg\n"
	importTXT(t, env, "清单A.txt", stale)

	d := uploadOne(t, env, "f.jpg")
	if resp := env.putAssetAuthors(t, d.Id.String(), []string{x}); resp.StatusCode != http.StatusOK {
		t.Fatalf("挂靠期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}
	// 模拟存量上传条目（挂靠退役后条目只剩历史数据）。
	seedUploadEntry(t, env, "清单A.txt", authoring.UploadEntry{
		AuthorID: x, DisplayName: "作者X", Names: []string{"作者X"}, Works: []string{"f.jpg"},
	})
	// 编辑移除关联 → f.jpg 行从片段删除、条目同步修剪。
	if resp := env.putAssetAuthors(t, d.Id.String(), []string{}); resp.StatusCode != http.StatusOK {
		t.Fatalf("移除期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}
	entries, err := authorattach.LoadUploadEntries(context.Background(), env.q)
	if err != nil {
		t.Fatalf("读条目失败: %v", err)
	}
	if got := entries["清单A.txt"]; len(got) != 0 {
		t.Fatalf("编辑移除后上传条目应被修剪：%+v", got)
	}
	// 缺省重导旧版（本就缺 f.jpg）→ 不再 409。
	if code, _, _ := importTXTRes(t, env, "清单A.txt", stale, ""); code != http.StatusOK {
		t.Fatalf("修剪后缺省重导期望 200，得到 %d", code)
	}
}

// TestAuthorSourcesReplace：单作者来源区读写——替换既有来源区（整体替换
// 语义非追加）、清空后标记行不残留、作品行不受影响。
func TestAuthorSourcesReplace(t *testing.T) {
	env := newTestEnv(t)
	aid := authoring.GenerateAuthorID("来源作者")
	importTXT(t, env, "s.txt", "1  来源作者\n来源\nsite-a\n作品\na.jpg\n")

	if code, _ := getAuthorSources(t, env, aid); code != http.StatusOK {
		t.Fatalf("GET 来源区期望 200，得到 %d", code)
	}
	resp := env.putAuthorSources(t, aid, []string{"新站点x"})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("PUT 来源区期望 200，得到 %d", resp.StatusCode)
	}
	var saved gen.SourceVocabulary
	if err := decodeBody(resp, &saved); err != nil {
		t.Fatalf("解析响应失败: %v", err)
	}
	closeBody(resp)
	if len(saved.Sources) != 1 || saved.Sources[0] != "新站点x" {
		t.Fatalf("回显=%v, want [新站点x]", saved.Sources)
	}
	content, _ := fragmentOf(t, env, "s.txt")
	works, sources := blockWorksOf(t, content, aid)
	if len(sources) != 1 || sources[0] != "新站点x" {
		t.Fatalf("片段来源区=%v, want [新站点x]（整体替换）", sources)
	}
	if len(works) != 1 || works[0] != "a.jpg" {
		t.Fatalf("作品行不得受影响：%v", works)
	}

	// 清空：来源区整段移除、标记行不残留。
	resp = env.putAuthorSources(t, aid, nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("清空来源区期望 200，得到 %d", resp.StatusCode)
	}
	closeBody(resp)
	content, _ = fragmentOf(t, env, "s.txt")
	if _, sources = blockWorksOf(t, content, aid); len(sources) != 0 {
		t.Fatalf("清空后来源区=%v, want 空", sources)
	}
	// 标记行不得残留：逐行断言（作者名「来源作者」含"来源"字样，不能整串
	// Contains 判定）。
	for _, l := range strings.Split(content, "\n") {
		tl := strings.TrimSpace(l)
		if tl == "来源" || tl == "出处" || strings.HasPrefix(tl, "来源  ") || strings.HasPrefix(tl, "出处  ") {
			t.Fatalf("清空后来源标记行不得残留:\n%s", content)
		}
	}
	if code, got := getAuthorSources(t, env, aid); code != http.StatusOK || len(got) != 0 {
		t.Fatalf("清空后 GET=（%d, %v）, want（200, 空）", code, got)
	}
}

// TestAuthorSourcesNewBlockInRecentFragment：无块作者 → 最近导入片段新建
// 作者块（不自动建片段）；库中无片段 → 自动创建承载片段。
func TestAuthorSourcesNewBlockInRecentFragment(t *testing.T) {
	env := newTestEnv(t)
	z := authoring.GenerateAuthorID("Z")
	importTXT(t, env, "c.txt", "Z\n") // 格式 C：不存片段
	importTXT(t, env, "清单.txt", "1  别人\n作品\na.jpg\n")

	resp := env.putAuthorSources(t, z, []string{"forum-c"})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("PUT 来源区期望 200，得到 %d", resp.StatusCode)
	}
	closeBody(resp)
	if _, ok := fragmentOf(t, env, authoring.AutoFragmentFilename); ok {
		t.Fatal("已有片段时不应自动创建承载片段")
	}
	content, _ := fragmentOf(t, env, "清单.txt")
	if _, srcs := blockWorksOf(t, content, z); len(srcs) != 1 || srcs[0] != "forum-c" {
		t.Fatalf("新建块来源区=%v, want [forum-c]", srcs)
	}
	if code, got := getAuthorSources(t, env, z); code != http.StatusOK || len(got) != 1 || got[0] != "forum-c" {
		t.Fatalf("GET=（%d, %v）, want（200, [forum-c]）", code, got)
	}

	// 库中无片段（删掉清单片段后；作者行不级联删除，Z 仍是常规作者）：
	// 自动创建承载片段。
	resp = env.do(t, "DELETE", "/api/v1/authors/import-txt?filename="+url.QueryEscape("清单.txt"), "")
	closeBody(resp)
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("删片段期望 204，得到 %d", resp.StatusCode)
	}
	resp = env.putAuthorSources(t, z, []string{"新站点"})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("PUT 来源区期望 200，得到 %d", resp.StatusCode)
	}
	closeBody(resp)
	content, ok := fragmentOf(t, env, authoring.AutoFragmentFilename)
	if !ok {
		t.Fatal("库中无片段时应自动创建承载片段")
	}
	if _, srcs := blockWorksOf(t, content, z); len(srcs) != 1 || srcs[0] != "新站点" {
		t.Fatalf("承载片段来源区=%v, want [新站点]", srcs)
	}
}

// TestSourceVocabularyCRUD：通用来源词表读写——无记录空数组、trim+去重+
// 剔空、整体替换、空数组清空。
func TestSourceVocabularyCRUD(t *testing.T) {
	env := newTestEnv(t)
	if code, got := getVocabulary(t, env); code != http.StatusOK || len(got) != 0 {
		t.Fatalf("无记录 GET=（%d, %v）, want（200, 空）", code, got)
	}
	resp := env.do(t, "PUT", "/api/v1/authors/source-vocabulary",
		`{"sources":[" forum-c ","site-a","","forum-c"]}`)
	var saved gen.SourceVocabulary
	if err := decodeBody(resp, &saved); err != nil {
		t.Fatalf("解析响应失败: %v", err)
	}
	closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("PUT 词表期望 200，得到 %d", resp.StatusCode)
	}
	if len(saved.Sources) != 2 || saved.Sources[0] != "forum-c" || saved.Sources[1] != "site-a" {
		t.Fatalf("回显=%v, want [forum-c site-a]（trim+去重+剔空）", saved.Sources)
	}
	if code, got := getVocabulary(t, env); code != http.StatusOK || len(got) != 2 {
		t.Fatalf("回读=（%d, %v）, want（200, 2 项）", code, got)
	}
	// 空数组=清空。
	resp = env.do(t, "PUT", "/api/v1/authors/source-vocabulary", `{"sources":[]}`)
	closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("清空词表期望 200，得到 %d", resp.StatusCode)
	}
	if code, got := getVocabulary(t, env); code != http.StatusOK || len(got) != 0 {
		t.Fatalf("清空后 GET=（%d, %v）, want（200, 空）", code, got)
	}
}

// TestAuthorEditParamMatrix：编辑端点校验矩阵——词表超 32 项/控制字符/超长
// 400；作者不存在 404；COS 作者 400；资产作者替换的资产/作者不存在 404；
// cos 库资产 400（能力边界随编辑入口迁移；能力声明卡保留）。
func TestAuthorEditParamMatrix(t *testing.T) {
	env := newTestEnv(t)
	aid := authoring.GenerateAuthorID("作者甲")
	importTXT(t, env, "c.txt", "作者甲\n")
	cosID := authoring.GenerateCosAuthorID("COS酱")
	if err := env.q.UpsertAuthor(context.Background(), db.UpsertAuthorParams{
		ID: cosID, DisplayName: "COS酱",
		Type: authoring.AuthorTypeCos, CreatedAt: store.FormatTimestamp(env.clock.Now()),
	}); err != nil {
		t.Fatalf("造 COS 作者失败: %v", err)
	}
	over := make([]string, maxSourceVocabularyItems+1)
	for i := range over {
		over[i] = fmt.Sprintf("站点%d", i)
	}
	overJSON, _ := json.Marshal(over)

	cases := []struct {
		name string
		do   func() *http.Response
		want int
	}{
		{"词表超 32 项", func() *http.Response {
			return env.do(t, "PUT", "/api/v1/authors/source-vocabulary", `{"sources":`+string(overJSON)+`}`)
		}, http.StatusBadRequest},
		{"词表项含换行", func() *http.Response {
			return env.do(t, "PUT", "/api/v1/authors/source-vocabulary", `{"sources":["x\n作品\n恶意行.png"]}`)
		}, http.StatusBadRequest},
		{"词表项含回车", func() *http.Response {
			return env.do(t, "PUT", "/api/v1/authors/source-vocabulary", `{"sources":["x\ry"]}`)
		}, http.StatusBadRequest},
		{"词表项超长", func() *http.Response {
			return env.do(t, "PUT", "/api/v1/authors/source-vocabulary",
				`{"sources":[`+jsonQuote(strings.Repeat("x", authoring.MaxSourceWordRunes+1))+`]}`)
		}, http.StatusBadRequest},
		{"来源区作者不存在", func() *http.Response {
			return env.putAuthorSources(t, "ghost-author", []string{"x"})
		}, http.StatusNotFound},
		{"来源区 COS 作者 PUT", func() *http.Response {
			return env.putAuthorSources(t, cosID, []string{"x"})
		}, http.StatusBadRequest},
		{"来源区 COS 作者 GET", func() *http.Response {
			return env.do(t, "GET", "/api/v1/authors/"+cosID+"/sources", "")
		}, http.StatusBadRequest},
		{"资产作者替换：资产不存在", func() *http.Response {
			return env.putAssetAuthors(t, "00000000-0000-0000-0000-000000000000", []string{aid})
		}, http.StatusNotFound},
	}
	for _, c := range cases {
		resp := c.do()
		closeBody(resp)
		if resp.StatusCode != c.want {
			t.Fatalf("%s：期望 %d，得到 %d", c.name, c.want, resp.StatusCode)
		}
	}
	// 资产作者替换：authorIds 含未知作者 → 404（事务回滚，无半更新）。
	d := uploadOne(t, env, "m.jpg")
	resp := env.putAssetAuthors(t, d.Id.String(), []string{aid, "ghost-author"})
	closeBody(resp)
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("未知作者替换期望 404，得到 %d", resp.StatusCode)
	}
	refs, err := env.q.ListAssetAuthorRefs(context.Background(), d.Id.String())
	if err != nil || len(refs) != 0 {
		t.Fatalf("被拒替换不应产生关联：refs=%v err=%v", refs, err)
	}
	// cos 库资产替换 → 400。
	cosLib, err := env.q.CreateLibrary(context.Background(), db.CreateLibraryParams{
		ID: "cos-lib-0001", Name: "cos库", RootPath: t.TempDir(),
		Kind: "cos", CreatedAt: store.FormatTimestamp(env.clock.Now()),
	})
	if err != nil {
		t.Fatalf("建 cos 库失败: %v", err)
	}
	cosAsset := uploadAttach201(t, env.uploadAttach(t, cosLib.ID, "shot.jpg", makeJPG(t, t.TempDir(), 32, 32), nil))
	resp = env.putAssetAuthors(t, cosAsset.Id.String(), []string{aid})
	closeBody(resp)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("cos 库资产替换期望 400，得到 %d", resp.StatusCode)
	}
	// 能力声明 authorAttach normal=true cos=false。
	resp = env.do(t, "GET", "/api/v1/libraries", "")
	var libs []gen.Library
	if err := decodeBody(resp, &libs); err != nil {
		t.Fatalf("解析库列表失败: %v", err)
	}
	closeBody(resp)
	caps := map[string]bool{}
	for _, l := range libs {
		if l.Kind != nil && l.Capabilities != nil && l.Capabilities.AuthorAttach != nil {
			caps[string(*l.Kind)] = *l.Capabilities.AuthorAttach
		}
	}
	if !caps["normal"] || caps["cos"] {
		t.Fatalf("能力声明 authorAttach 应 normal=true cos=false：%v", caps)
	}
}

// TestAuthorSourcesInjectionRejected（阻断审查 2 的编辑端迁移）：来源词含
// 控制字符（换行/回车）→ 400——否则可向 TXT 真相注入任意行；被拒请求无
// 任何片段副作用。
func TestAuthorSourcesInjectionRejected(t *testing.T) {
	env := newTestEnv(t)
	aid := authoring.GenerateAuthorID("作者甲")
	importTXT(t, env, "c.txt", "1  作者甲\n作品\na.jpg\n")
	for _, bad := range []string{"x\n作品\n恶意行.png", "x\ry"} {
		resp := env.putAuthorSources(t, aid, []string{bad})
		closeBody(resp)
		if resp.StatusCode != http.StatusBadRequest {
			t.Fatalf("来源 %q 期望 400，得到 %d", bad, resp.StatusCode)
		}
	}
	content, _ := fragmentOf(t, env, "c.txt")
	if works, _ := blockWorksOf(t, content, aid); len(works) != 1 || works[0] != "a.jpg" {
		t.Fatalf("被拒请求不得改动片段：%v", works)
	}
}

// assertMirrorFile 断言镜像文件内容与期望逐字一致（REQ §3.4：解析镜像
// 文件的结果 = 解析服务端片段）。
func assertMirrorFile(t *testing.T, path, want string) {
	t.Helper()
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("读镜像文件失败: %v", err)
	}
	if string(got) != want {
		t.Fatalf("镜像内容与片段不一致：\n--- 镜像 ---\n%s\n--- 片段 ---\n%s", got, want)
	}
}

// TestAuthorEditMirror：编辑端点与重导入都把片段最新原文写进镜像文件
// （逐字一致；镜像不可写不影响编辑 200）；匿名片段导出文件名=作者清单.txt。
func TestAuthorEditMirror(t *testing.T) {
	env := newTestEnv(t)
	mirrorPath := filepath.Join(t.TempDir(), "m.txt")

	resp := env.do(t, "PUT", "/api/v1/authors/mirror", `{"path":`+jsonQuote(mirrorPath)+`}`)
	var saved gen.AuthorMirrorConfig
	if err := decodeBody(resp, &saved); err != nil {
		t.Fatalf("解析镜像配置响应失败: %v", err)
	}
	closeBody(resp)
	if resp.StatusCode != http.StatusOK || saved.Path == nil || *saved.Path != mirrorPath {
		t.Fatalf("保存镜像配置期望 200 且回显 path：%d %+v", resp.StatusCode, saved)
	}

	// 导入片段 → 镜像同步；编辑来源区 → 镜像跟随更新（REQ §3.4 挂点迁移）。
	imported := "1  镜像作者\n作品\na.jpg\n"
	importTXT(t, env, "清单.txt", imported)
	assertMirrorFile(t, mirrorPath, imported)

	aid := authoring.GenerateAuthorID("镜像作者")
	resp = env.putAuthorSources(t, aid, []string{"forum-c"})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("编辑来源区期望 200，得到 %d", resp.StatusCode)
	}
	closeBody(resp)
	frag, _ := fragmentOf(t, env, "清单.txt")
	assertMirrorFile(t, mirrorPath, frag)

	// 镜像不可写（path 指向目录）：编辑仍 200 不报错（镜像是尽力而为投影）。
	resp = env.do(t, "PUT", "/api/v1/authors/mirror", `{"path":`+jsonQuote(t.TempDir())+`}`)
	closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("保存不可写镜像配置期望 200，得到 %d", resp.StatusCode)
	}
	resp = env.putAuthorSources(t, aid, []string{"新站点"})
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("镜像不可写不应影响编辑，得到 %d", resp.StatusCode)
	}
	closeBody(resp)

	// 匿名片段导出：Content-Disposition 文件名 = 作者清单.txt（协议约定）。
	if code, _, _ := importTXTRes(t, env, "", "1  匿名作者\n作品\nb.jpg\n", ""); code != http.StatusOK {
		t.Fatalf("匿名导入期望 200，得到 %d", code)
	}
	resp = env.do(t, "GET", "/api/v1/authors/import-txt/export", "")
	defer closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("匿名导出期望 200，得到 %d", resp.StatusCode)
	}
	if cd := resp.Header.Get("Content-Disposition"); !strings.Contains(cd, url.PathEscape(exportAnonymousName)) {
		t.Fatalf("匿名导出文件名应为 %q（Content-Disposition=%q）", exportAnonymousName, cd)
	}
}
