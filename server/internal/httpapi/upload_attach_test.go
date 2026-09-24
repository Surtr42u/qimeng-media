package httpapi

// 上传挂靠端到端测试（REQ-上传指定作者与来源 §3.3/§6）：
// 参数向后兼容、authorId/authorName 挂靠、联想、来源词表、cos 拒绝与
// 能力声明、参数校验矩阵、重建保留、冲突重命名记最终名、自动镜像。
// 复用 browse_test.go 的 newTestEnv（setup + 假扫描入库 a.jpg/b.jpg/c.mp4）。

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// uploadAttach 发起带挂靠参数的流式上传（extra 为额外 query 参数，
// authorId/authorName 单值、source 多值）。
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
	req, err := http.NewRequest(http.MethodPost, e.ts.URL+"/api/v1/assets/upload?"+q.Encode(), bytes.NewReader(body))
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
// 挂靠写入必须能被统一重建的同一套解析器读回）。
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

// TestUploadNoAttachParamsUnchanged（验收 #1）：不传新参数上传 → 行为与
// 现状完全一致：201、不建片段、响应 Authors 空数组、无作者行。
func TestUploadNoAttachParamsUnchanged(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 64, 64)

	d := uploadAttach201(t, env.uploadAttach(t, env.libID, "plain.jpg", jpg, nil))
	if d.RelPath == nil || *d.RelPath != "plain.jpg" {
		t.Fatalf("relPath=%v, want plain.jpg", d.RelPath)
	}
	if sources, err := authorattach.LoadSources(context.Background(), env.q); err != nil || len(sources) != 0 {
		t.Fatalf("不传挂靠参数不应产生片段：sources=%v err=%v", sources, err)
	}
	if d.Authors == nil || len(*d.Authors) != 0 {
		t.Fatalf("响应 Authors 应为空数组：%v", d.Authors)
	}
	if list := listAuthors(t, env); len(list) != 0 {
		t.Fatalf("不应产生作者行：%v", list)
	}
}

// TestUploadAttachAuthorId（验收 #2）：authorId 挂靠两文件 → 201；
// 片段作品区两行最终名；作者 fileCount=2；asset_authors 各一条。
// 作者经格式 C 导入（无片段、零关联）——覆盖「作者不在任何片段 →
// 自动创建承载片段」路径（REQ §3.3 第 2 条）。
func TestUploadAttachAuthorId(t *testing.T) {
	env := newTestEnv(t)
	aid := authoring.GenerateAuthorID("作者甲")
	importTXT(t, env, "c.txt", "作者甲\n")

	jpg := makeJPG(t, t.TempDir(), 64, 64)
	d1 := uploadAttach201(t, env.uploadAttach(t, env.libID, "f1.jpg", jpg, url.Values{"authorId": {aid}}))
	d2 := uploadAttach201(t, env.uploadAttach(t, env.libID, "f2.jpg", jpg, url.Values{"authorId": {aid}}))

	content, ok := fragmentOf(t, env, authoring.AutoFragmentFilename)
	if !ok {
		t.Fatal("无任何片段时应自动创建承载片段")
	}
	works, _ := blockWorksOf(t, content, aid)
	if len(works) != 2 || works[0] != "f1.jpg" || works[1] != "f2.jpg" {
		t.Fatalf("片段作品行=%v, want [f1.jpg f2.jpg]（最终落盘名）", works)
	}
	if a := findAuthor(t, listAuthors(t, env), aid); a.FileCount == nil || *a.FileCount != 2 {
		t.Fatalf("挂靠后 fileCount=%v, want 2（挂靠即时可见）", a.FileCount)
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
	if d1.Authors == nil || len(*d1.Authors) != 1 || *(*d1.Authors)[0].Id != aid {
		t.Fatalf("响应 Authors 应带回挂靠作者：%v", d1.Authors)
	}
}

// TestAuthorsSuggest（验收 #3）：别名片段与大小写变体命中同一作者；
// 不存在的词 → 空列表；limit 越界 400、limit 截断生效；COS 作者与
// 零关联常规作者的口径（regular 全集、含零关联）。
func TestAuthorsSuggest(t *testing.T) {
	env := newTestEnv(t)
	kami := authoring.GenerateAuthorID("kamihikoki_mmd")
	importTXT(t, env, "a.txt", "1  kamihikoki_mmd  紙飛行機(小红车资源出处)\n作品\na.jpg\n")
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

// TestUploadAttachNewAuthorName（验收 #4）：authorName 新建——作者创建、
// 关联建立、片段开新块一次完成；大小写变体二次上传归并同一作者（不裂
// 分身、不产生第二个同名块）。
func TestUploadAttachNewAuthorName(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 64, 64)
	aid := authoring.GenerateAuthorID("NewAuthor")

	uploadAttach201(t, env.uploadAttach(t, env.libID, "n1.jpg", jpg, url.Values{"authorName": {"NewAuthor"}}))
	if a := findAuthor(t, listAuthors(t, env), aid); a.DisplayName == nil || *a.DisplayName != "NewAuthor" {
		t.Fatalf("新建作者显示名=%v, want NewAuthor", a.DisplayName)
	}
	uploadAttach201(t, env.uploadAttach(t, env.libID, "n2.jpg", jpg, url.Values{"authorName": {"NEWAUTHOR"}}))

	list := listAuthors(t, env)
	if len(list) != 1 {
		t.Fatalf("大小写变体应归并同一作者，作者总数=%d, want 1", len(list))
	}
	if a := findAuthor(t, list, aid); a.FileCount == nil || *a.FileCount != 2 {
		t.Fatalf("归并后 fileCount=%v, want 2", a.FileCount)
	}
	content, _ := fragmentOf(t, env, authoring.AutoFragmentFilename)
	blocks := authoring.ParseAuthorBlocks(content)
	same := 0
	for _, b := range blocks {
		if len(b.AuthorNames) > 0 && authoring.GenerateAuthorID(b.AuthorNames[0]) == aid {
			same++
		}
	}
	if same != 1 {
		t.Fatalf("同名作者块数=%d, want 1（归并进既有块）:\n%s", same, content)
	}
}

// TestUploadAttachSources（验收 #5/#6）：source 并入片段来源区且不重复、
// 词表含新来源且 authorCount 正确；只传 source 不传作者 400；后续不传
// source 的上传不动既有来源。
func TestUploadAttachSources(t *testing.T) {
	env := newTestEnv(t)
	aid := authoring.GenerateAuthorID("来源作者")
	importTXT(t, env, "s.txt", "1  来源作者\n来源\nkemono\n作品\na.jpg\n")
	jpg := makeJPG(t, t.TempDir(), 64, 64)

	uploadAttach201(t, env.uploadAttach(t, env.libID, "f1.jpg", jpg,
		url.Values{"authorId": {aid}, "source": {"kemono", "新站点x"}}))
	content, _ := fragmentOf(t, env, "s.txt")
	_, sources := blockWorksOf(t, content, aid)
	if len(sources) != 2 || sources[0] != "kemono" || sources[1] != "新站点x" {
		t.Fatalf("片段来源区=%v, want [kemono 新站点x]（既有保留、新来源并入）", sources)
	}

	// 重复勾选同一来源 → 不产生重复行。
	uploadAttach201(t, env.uploadAttach(t, env.libID, "f2.jpg", jpg,
		url.Values{"authorId": {aid}, "source": {"新站点x"}}))
	content, _ = fragmentOf(t, env, "s.txt")
	_, sources = blockWorksOf(t, content, aid)
	if len(sources) != 2 {
		t.Fatalf("重复来源不应翻倍：%v", sources)
	}

	// 词表（GET /authors/sources）：两词各被 1 位作者引用。
	resp := env.do(t, "GET", "/api/v1/authors/sources", "")
	var vocab gen.AuthorSourceVocabulary
	if err := decodeBody(resp, &vocab); err != nil {
		t.Fatalf("解析词表失败: %v", err)
	}
	closeBody(resp)
	counts := map[string]int{}
	for _, st := range deref(vocab.Sources) {
		if st.Name != nil && st.AuthorCount != nil {
			counts[*st.Name] = *st.AuthorCount
		}
	}
	for _, want := range []string{"kemono", "新站点x"} {
		if counts[want] != 1 {
			t.Fatalf("词表 %s 的 authorCount=%d, want 1（全表 %v）", want, counts[want], counts)
		}
	}

	// 只传 source 不传作者 → 400。
	resp = env.uploadAttach(t, env.libID, "f3.jpg", jpg, url.Values{"source": {"孤立来源"}})
	closeBody(resp)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("只传 source 期望 400，得到 %d", resp.StatusCode)
	}

	// 不传 source 的后续上传：既有来源分毫不动（验收 #6）。
	uploadAttach201(t, env.uploadAttach(t, env.libID, "f3.jpg", jpg, url.Values{"authorId": {aid}}))
	content, _ = fragmentOf(t, env, "s.txt")
	if _, sources = blockWorksOf(t, content, aid); len(sources) != 2 || sources[0] != "kemono" || sources[1] != "新站点x" {
		t.Fatalf("不传 source 不应改动既有来源：%v", sources)
	}
}

// TestUploadAttachCosRejectedAndCapabilities（验收 #7）：cos 库上传带挂靠
// 参数 → 400；GET /libraries 能力声明 normal=true、cos=false。
func TestUploadAttachCosRejectedAndCapabilities(t *testing.T) {
	env := newTestEnv(t)
	aid := authoring.GenerateAuthorID("作者甲")
	importTXT(t, env, "c.txt", "作者甲\n")
	cosRoot := t.TempDir()
	cosLib, err := env.q.CreateLibrary(context.Background(), db.CreateLibraryParams{
		ID: uuid.NewString(), Name: "cos库", RootPath: cosRoot,
		Kind: "cos", CreatedAt: store.FormatTimestamp(env.clock.Now()),
	})
	if err != nil {
		t.Fatalf("建 cos 库失败: %v", err)
	}

	jpg := makeJPG(t, t.TempDir(), 64, 64)
	resp := env.uploadAttach(t, cosLib.ID, "shot.jpg", jpg, url.Values{"authorId": {aid}})
	closeBody(resp)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("cos 库挂靠期望 400，得到 %d", resp.StatusCode)
	}

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

// TestUploadAttachParamMatrix：挂靠参数校验矩阵——互斥 400、作者名带
// 媒体扩展名 400、作者不存在 404、source 超 32 项 400（校验都在收流前，
// 小请求体即可触发）。
func TestUploadAttachParamMatrix(t *testing.T) {
	env := newTestEnv(t)
	aid := authoring.GenerateAuthorID("作者甲")
	importTXT(t, env, "c.txt", "作者甲\n")

	over := url.Values{"authorId": {aid}}
	for i := 0; i < maxAttachSources+1; i++ {
		over.Add("source", fmt.Sprintf("站点%d", i))
	}
	cases := []struct {
		name  string
		extra url.Values
		want  int
	}{
		{"authorId 与 authorName 同传", url.Values{"authorId": {aid}, "authorName": {"某人"}}, http.StatusBadRequest},
		{"authorName 是文件名", url.Values{"authorName": {"a.png"}}, http.StatusBadRequest},
		{"authorId 不存在", url.Values{"authorId": {"ghost-author-id"}}, http.StatusNotFound},
		{"source 超上限", over, http.StatusBadRequest},
		{"单个 source 超长", url.Values{"authorId": {aid}, "source": {strings.Repeat("x", maxAttachSourceLen+1)}}, http.StatusBadRequest},
	}
	jpg := makeJPG(t, t.TempDir(), 32, 32)
	for _, c := range cases {
		resp := env.uploadAttach(t, env.libID, "m.jpg", jpg, c.extra)
		closeBody(resp)
		if resp.StatusCode != c.want {
			t.Fatalf("%s：期望 %d，得到 %d", c.name, c.want, resp.StatusCode)
		}
	}
}

// TestUploadAttachRebuildKeepsLinks（验收 #8）：挂靠后统一重建 → 关联保留
// 且无重复（fileCount 不变、不翻倍）。
func TestUploadAttachRebuildKeepsLinks(t *testing.T) {
	env := newTestEnv(t)
	aid := authoring.GenerateAuthorID("作者甲")
	importTXT(t, env, "c.txt", "作者甲\n")
	jpg := makeJPG(t, t.TempDir(), 64, 64)
	uploadAttach201(t, env.uploadAttach(t, env.libID, "r1.jpg", jpg, url.Values{"authorId": {aid}}))
	uploadAttach201(t, env.uploadAttach(t, env.libID, "r2.jpg", jpg, url.Values{"authorId": {aid}}))

	if _, _, code := rebuildTxt(t, env); code != 200 {
		t.Fatalf("重建期望 200，得到 %d", code)
	}
	if a := findAuthor(t, listAuthors(t, env), aid); a.FileCount == nil || *a.FileCount != 2 {
		t.Fatalf("重建后 fileCount=%v, want 2（挂靠保留不丢失不重复）", a.FileCount)
	}
	// 再重建一次仍不翻倍（幂等）。
	if _, _, code := rebuildTxt(t, env); code != 200 {
		t.Fatalf("二次重建期望 200，得到 %d", code)
	}
	if a := findAuthor(t, listAuthors(t, env), aid); a.FileCount == nil || *a.FileCount != 2 {
		t.Fatalf("二次重建后 fileCount=%v, want 2", a.FileCount)
	}
}

// TestUploadAttachConflictRenameFinalName（验收 #9）：同名文件二次上传自动
// 改名 "基名 (2).ext"，片段记录的是两个最终名、fileCount=2。
func TestUploadAttachConflictRenameFinalName(t *testing.T) {
	env := newTestEnv(t)
	aid := authoring.GenerateAuthorID("重名作者")
	jpg := makeJPG(t, t.TempDir(), 64, 64)

	d1 := uploadAttach201(t, env.uploadAttach(t, env.libID, "dup.jpg", jpg, url.Values{"authorName": {"重名作者"}}))
	d2 := uploadAttach201(t, env.uploadAttach(t, env.libID, "dup.jpg", jpg, url.Values{"authorName": {"重名作者"}}))
	if rp := d2.RelPath; rp == nil || *rp != "dup (2).jpg" {
		t.Fatalf("二次上传 relPath=%v, want dup (2).jpg", rp)
	}
	if rp := d1.RelPath; rp == nil || *rp != "dup.jpg" {
		t.Fatalf("首次上传 relPath=%v, want dup.jpg", rp)
	}
	content, _ := fragmentOf(t, env, authoring.AutoFragmentFilename)
	works, _ := blockWorksOf(t, content, aid)
	if len(works) != 2 || works[0] != "dup.jpg" || works[1] != "dup (2).jpg" {
		t.Fatalf("片段记录的应是最终落盘名：%v", works)
	}
	if a := findAuthor(t, listAuthors(t, env), aid); a.FileCount == nil || *a.FileCount != 2 {
		t.Fatalf("fileCount=%v, want 2", a.FileCount)
	}
}

// TestUploadAttachMirror（验收 #14）：配置镜像路径后，上传挂靠与重导入
// 都把片段最新原文写进镜像文件（逐字一致）；匿名片段导出文件名=作者清单.txt。
func TestUploadAttachMirror(t *testing.T) {
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
	// GET 回读一致。
	resp = env.do(t, "GET", "/api/v1/authors/mirror", "")
	var gotCfg gen.AuthorMirrorConfig
	_ = decodeBody(resp, &gotCfg)
	closeBody(resp)
	if gotCfg.Path == nil || *gotCfg.Path != mirrorPath {
		t.Fatalf("GET 镜像配置回显不符：%+v", gotCfg)
	}

	// 上传挂靠 → 镜像 = 自动片段原文。
	jpg := makeJPG(t, t.TempDir(), 64, 64)
	uploadAttach201(t, env.uploadAttach(t, env.libID, "m1.jpg", jpg, url.Values{"authorName": {"镜像作者"}}))
	frag, _ := fragmentOf(t, env, authoring.AutoFragmentFilename)
	assertMirrorFile(t, mirrorPath, frag)

	// 再导入新片段（时钟前移保证「最近导入」序位）→ 镜像跟随更新。
	env.clock.advance(time.Minute)
	imported := "1  导入作者\n作品\na.jpg\n"
	importTXT(t, env, "新清单.txt", imported)
	assertMirrorFile(t, mirrorPath, imported)

	// 匿名片段导出：Content-Disposition 文件名 = 作者清单.txt（协议约定）。
	code, _, _ := importTXTRes(t, env, "", "1  匿名作者\n作品\nb.jpg\n", "")
	if code != http.StatusOK {
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

// TestUploadAttachMirrorUnwritable（验收 #15）：镜像 path 指向不可写位置
// （已存在目录）→ 上传仍 201 不报错（镜像是尽力而为的投影）。
func TestUploadAttachMirrorUnwritable(t *testing.T) {
	env := newTestEnv(t)
	dir := t.TempDir() // path 指向目录：临时文件能建，rename 到目录必失败
	resp := env.do(t, "PUT", "/api/v1/authors/mirror", `{"path":`+jsonQuote(dir)+`}`)
	closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("保存镜像配置期望 200，得到 %d", resp.StatusCode)
	}
	jpg := makeJPG(t, t.TempDir(), 64, 64)
	d := uploadAttach201(t, env.uploadAttach(t, env.libID, "w1.jpg", jpg, url.Values{"authorName": {"不可写镜像作者"}}))
	if d.Id == nil {
		t.Fatal("镜像不可写不应影响上传成功")
	}
}

// TestUploadAttachInjectionRejected（阻断审查 2）：authorName/source 含控制
// 字符（换行/回车，URL 编码 %0A/%0D 直传）→ 400——否则可向 TXT 真相注入
// 任意行（如伪造编号行创建幽灵作者）；authorName 超 openapi maxLength 200
// 同样 400。校验在收流之前 fail-fast，无任何作者/片段副作用。
func TestUploadAttachInjectionRejected(t *testing.T) {
	env := newTestEnv(t)
	aid := authoring.GenerateAuthorID("作者甲")
	importTXT(t, env, "c.txt", "作者甲\n")
	jpg := makeJPG(t, t.TempDir(), 32, 32)
	cases := []struct {
		name  string
		extra url.Values
	}{
		{"source 注入作品行", url.Values{"authorId": {aid}, "source": {"x\n作品\n恶意行.png"}}},
		{"source 含回车", url.Values{"authorId": {aid}, "source": {"x\ry"}}},
		{"authorName 注入编号行", url.Values{"authorName": {"正常名\n2  幽灵作者"}}},
		{"authorName 含回车", url.Values{"authorName": {"名字\r有鬼"}}},
		{
			"authorName 超 200 字符",
			url.Values{"authorName": {strings.Repeat("字", authoring.MaxNewAuthorNameRunes+1)}},
		},
	}
	for _, c := range cases {
		resp := env.uploadAttach(t, env.libID, "inj.jpg", jpg, c.extra)
		closeBody(resp)
		if resp.StatusCode != http.StatusBadRequest {
			t.Fatalf("%s：期望 400，得到 %d", c.name, resp.StatusCode)
		}
	}
	// 被拒请求不得产生任何作者行/片段（格式 C 导入不存片段，此处应为零）。
	if sources, err := authorattach.LoadSources(context.Background(), env.q); err != nil || len(sources) != 0 {
		t.Fatalf("被拒请求产生片段副作用：sources=%v err=%v", sources, err)
	}
	if list := listAuthors(t, env); len(list) != 1 {
		t.Fatalf("被拒请求不应增删作者行：%v", list)
	}
}

// TestUploadAttachNewAuthorNameIdentity（阻断审查 1）：authorName="Night  Cry"
// （双空格）→ authors 表恰一行 id=night、显示名 "Night / Cry"；同文件名再传
// 一次 → 冲突改名后块不重复、行不重复（幂等，原始输入定身份时会裂成
// night__cry/night 两处反复建块）；"bamhor[3D]" → id=bamhor（备注截断）。
func TestUploadAttachNewAuthorNameIdentity(t *testing.T) {
	env := newTestEnv(t)
	jpg := makeJPG(t, t.TempDir(), 64, 64)

	d1 := uploadAttach201(t, env.uploadAttach(t, env.libID, "dup.jpg", jpg, url.Values{"authorName": {"Night  Cry"}}))
	d2 := uploadAttach201(t, env.uploadAttach(t, env.libID, "dup.jpg", jpg, url.Values{"authorName": {"Night  Cry"}}))
	if rp := d2.RelPath; rp == nil || *rp != "dup (2).jpg" {
		t.Fatalf("二次上传 relPath=%v, want dup (2).jpg", rp)
	}
	if rp := d1.RelPath; rp == nil || *rp != "dup.jpg" {
		t.Fatalf("首次上传 relPath=%v, want dup.jpg", rp)
	}

	list := listAuthors(t, env)
	if len(list) != 1 {
		t.Fatalf("双空格变体不得裂分身，作者总数=%d：%+v", len(list), list)
	}
	a := findAuthor(t, list, "night")
	if a.DisplayName == nil || *a.DisplayName != "Night / Cry" {
		t.Fatalf("night 显示名=%v, want Night / Cry", a.DisplayName)
	}
	if a.FileCount == nil || *a.FileCount != 2 {
		t.Fatalf("fileCount=%v, want 2", a.FileCount)
	}

	content, _ := fragmentOf(t, env, authoring.AutoFragmentFilename)
	blocks := authoring.ParseAuthorBlocks(content)
	if len(blocks) != 1 {
		t.Fatalf("块数=%d, want 1（幂等：不得重复建块）:\n%s", len(blocks), content)
	}
	if !reflect.DeepEqual(blocks[0].AuthorNames, []string{"Night", "Cry"}) {
		t.Fatalf("块别名=%v, want [Night Cry]（canonical 写回）", blocks[0].AuthorNames)
	}
	seen := map[string]bool{}
	for _, w := range blocks[0].Works {
		if seen[w] {
			t.Fatalf("作品行重复 %q", w)
		}
		seen[w] = true
	}
	if len(seen) != 2 {
		t.Fatalf("作品行=%v, want [dup.jpg dup (2).jpg]（行不重复）", blocks[0].Works)
	}

	// 括号备注变体：id 取备注截断后的首别名。
	uploadAttach201(t, env.uploadAttach(t, env.libID, "b.jpg", jpg, url.Values{"authorName": {"bamhor[3D]"}}))
	if b := findAuthor(t, listAuthors(t, env), "bamhor"); b.DisplayName == nil || *b.DisplayName != "bamhor" {
		t.Fatalf("bamhor 显示名=%v, want bamhor", b.DisplayName)
	}
}

// TestUploadAttachFormatCAuthorIntoRecentFragment：格式 C 导入作者 Z（不存
// 片段）+ 再导入一份正常清单片段 → 上传挂靠 Z → Z 并入最近导入片段开新块
// （不自动建片段）、关联建立、统一重建后保留。
func TestUploadAttachFormatCAuthorIntoRecentFragment(t *testing.T) {
	env := newTestEnv(t)
	aid := authoring.GenerateAuthorID("Z")
	importTXT(t, env, "c.txt", "Z\n") // 格式 C：只建作者行，不存片段
	importTXT(t, env, "清单.txt", "1  别人\n作品\na.jpg\n")
	jpg := makeJPG(t, t.TempDir(), 64, 64)

	d := uploadAttach201(t, env.uploadAttach(t, env.libID, "z.jpg", jpg, url.Values{"authorId": {aid}}))
	if d.Authors == nil || len(*d.Authors) != 1 || *(*d.Authors)[0].Id != aid {
		t.Fatalf("响应应带回挂靠作者：%v", d.Authors)
	}
	content, ok := fragmentOf(t, env, "清单.txt")
	if !ok {
		t.Fatal("挂靠应并入最近导入的清单片段")
	}
	if _, ok := fragmentOf(t, env, authoring.AutoFragmentFilename); ok {
		t.Fatal("已有片段时不应自动创建承载片段")
	}
	works, _ := blockWorksOf(t, content, aid)
	if len(works) != 1 || works[0] != "z.jpg" {
		t.Fatalf("清单片段中 Z 的作品行=%v, want [z.jpg]", works)
	}
	if a := findAuthor(t, listAuthors(t, env), aid); a.FileCount == nil || *a.FileCount != 1 {
		t.Fatalf("fileCount=%v, want 1（关联建立）", a.FileCount)
	}

	// 统一重建：关联保留不丢失。
	if _, _, code := rebuildTxt(t, env); code != 200 {
		t.Fatalf("重建期望 200，得到 %d", code)
	}
	if a := findAuthor(t, listAuthors(t, env), aid); a.FileCount == nil || *a.FileCount != 1 {
		t.Fatalf("重建后 fileCount=%v, want 1（保留）", a.FileCount)
	}
}
