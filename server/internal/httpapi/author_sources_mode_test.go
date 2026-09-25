package httpapi

// PUT /authors/{authorId}/sources 的 mode 语义端到端测试（ADR-0024 编辑 +
// 上传流程自动挂靠并入）：mode=append 并入去重、永不覆盖既有来源区
// （ADR-0023 原上传来源口径——上传是补充不是编辑）；mode 缺省=replace
// （既有整体替换行为回归不动）；非法 mode 400 且无片段副作用。复用
// upload_attach_test.go / authors_alias_roundtrip_test.go 的夹具与辅助函数。

import (
	"context"
	"net/http"
	"reflect"
	"strings"
	"testing"

	"qimeng-media/server/internal/authorattach"
	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
)

// modePtr 测试小工具：mode 字段指针字面量。
func modePtr(s string) *string { return &s }

// putAuthorSourcesMode PUT /authors/{authorId}/sources，mode 显式编码
// （nil=省略 mode 字段——协议缺省 replace 口径的回归输入）。
func (e *testEnv) putAuthorSourcesMode(t *testing.T, authorID string, sources []string, mode *string) *http.Response {
	t.Helper()
	body := `{"sources":` + stringJSONArray(sources)
	if mode != nil {
		body += `,"mode":` + jsonQuote(*mode)
	}
	return e.do(t, "PUT", "/api/v1/authors/"+authorID+"/sources", body+`}`)
}

// TestAuthorSourcesAppendMerges（mode=append 主链路）：既有来源区
// [老王论坛 kemono] → PUT {sources:[kemono 新站点], mode:append} →
// [老王论坛 kemono 新站点]（保序去重）；重复 PUT 同内容幂等不变；上传
// 条目元数据只修剪不新增（append 无移除行、修剪恒 no-op，且不为新来源行
// 新增条目——编辑语义与上传挂靠的口径分界）。
func TestAuthorSourcesAppendMerges(t *testing.T) {
	env := newTestEnv(t)
	aid := authoring.GenerateAuthorID("来源作者")
	importTXT(t, env, "s.txt", "1  来源作者\n来源\n老王论坛\nkemono\n作品\na.jpg\n")

	resp := env.putAuthorSourcesMode(t, aid, []string{"kemono", "新站点"}, modePtr("append"))
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("PUT 来源区期望 200，得到 %d", resp.StatusCode)
	}
	var saved gen.SourceVocabulary
	if err := decodeBody(resp, &saved); err != nil {
		t.Fatalf("解析响应失败: %v", err)
	}
	closeBody(resp)
	want := []string{"老王论坛", "kemono", "新站点"}
	if !reflect.DeepEqual(saved.Sources, want) {
		t.Fatalf("回显=%v, want %v（既有区在前保序去重）", saved.Sources, want)
	}
	content, _ := fragmentOf(t, env, "s.txt")
	works, sources := blockWorksOf(t, content, aid)
	if !reflect.DeepEqual(sources, want) {
		t.Fatalf("片段来源区=%v, want %v（并入非覆盖）", sources, want)
	}
	if len(works) != 1 || works[0] != "a.jpg" {
		t.Fatalf("作品行不得受影响：%v", works)
	}

	// 重复 PUT 同内容：幂等——片段与来源区均不变。
	before := content
	resp = env.putAuthorSourcesMode(t, aid, []string{"kemono", "新站点"}, modePtr("append"))
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("重复 PUT 期望 200，得到 %d", resp.StatusCode)
	}
	closeBody(resp)
	if after, _ := fragmentOf(t, env, "s.txt"); after != before {
		t.Fatalf("重复 append 改变片段:\n--- 前 ---\n%s\n--- 后 ---\n%s", before, after)
	}
	if code, got := getAuthorSources(t, env, aid); code != http.StatusOK || !reflect.DeepEqual(got, want) {
		t.Fatalf("幂等后 GET=（%d, %v）, want（200, %v）", code, got, want)
	}

	// 条目元数据只修剪不新增：append 追加新行后，存量条目原样保留、
	// 不为新行新增条目。
	seedUploadEntry(t, env, "s.txt", authoring.UploadEntry{
		AuthorID: aid, DisplayName: "来源作者", Names: []string{"来源作者"},
		Works: []string{"a.jpg"}, Sources: []string{"老王论坛"},
	})
	resp = env.putAuthorSourcesMode(t, aid, []string{"新站点2"}, modePtr("append"))
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("PUT 期望 200，得到 %d", resp.StatusCode)
	}
	closeBody(resp)
	entries, err := authorattach.LoadUploadEntries(context.Background(), env.q)
	if err != nil {
		t.Fatalf("读条目失败: %v", err)
	}
	wantEntries := []authoring.UploadEntry{{
		AuthorID: aid, DisplayName: "来源作者", Names: []string{"来源作者"},
		Works: []string{"a.jpg"}, Sources: []string{"老王论坛"},
	}}
	if got := entries["s.txt"]; !reflect.DeepEqual(got, wantEntries) {
		t.Fatalf("append 后条目元数据=%+v, want 原样（只修剪不新增）", got)
	}
}

// TestAuthorSourcesAppendNoBlockAuthor（append 无块作者）：与 replace 同款
// 新建块路径——多别名作者编号行按空格分隔各别名写回、解析回读 id 不漂移
// （displayNameAliases 修复在 append 路径同样生效）；重复 append 不重复建块。
func TestAuthorSourcesAppendNoBlockAuthor(t *testing.T) {
	env := newTestEnv(t)
	night := seedMultiAliasAuthor(t, env) // 作者行保留（多别名 displayName）、无任何块
	importTXT(t, env, "other.txt", "1  别人\n作品\nb.jpg\n")
	if code := deleteTxt(t, env, "night.txt"); code != http.StatusNoContent {
		t.Fatalf("删片段期望 204，得到 %d", code)
	}

	resp := env.putAuthorSourcesMode(t, night, []string{"老王论坛"}, modePtr("append"))
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("PUT 来源区期望 200，得到 %d", resp.StatusCode)
	}
	closeBody(resp)
	content, _ := fragmentOf(t, env, "other.txt")
	if !containsLine(content, "2  Night  Cry") {
		t.Fatalf("新块编号行应为空格分隔多别名（漂移形态是 \"2  Night / Cry\"）:\n%s", content)
	}
	// blockWorksOf 内部断言块回读 id == night（漂移时 fatal）。
	if _, srcs := blockWorksOf(t, content, night); len(srcs) != 1 || srcs[0] != "老王论坛" {
		t.Fatalf("新块来源区=%v, want [老王论坛]", srcs)
	}

	// 幂等：重复 append 不重复建块、来源区不翻倍。
	resp = env.putAuthorSourcesMode(t, night, []string{"老王论坛"}, modePtr("append"))
	closeBody(resp)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("重复 PUT 期望 200，得到 %d", resp.StatusCode)
	}
	content, _ = fragmentOf(t, env, "other.txt")
	if n := strings.Count(content, "2  Night  Cry"); n != 1 {
		t.Fatalf("重复 append 后编号行数=%d, want 1（不重复建块）:\n%s", n, content)
	}
	if _, srcs := blockWorksOf(t, content, night); len(srcs) != 1 {
		t.Fatalf("重复 append 来源区=%v, want [老王论坛]（不翻倍）", srcs)
	}
	for _, a := range listAuthors(t, env) {
		if a.Id != nil && *a.Id == "night__cry" {
			t.Fatalf("append 后出现幻影作者 night__cry：%+v", listAuthors(t, env))
		}
	}
}

// TestAuthorSourcesModeDefaultAndValidation：mode 缺省=replace（不传 mode
// 字段的既有客户端行为回归不动）；显式 replace 同现状；非法 mode 400 且
// 无片段副作用。
func TestAuthorSourcesModeDefaultAndValidation(t *testing.T) {
	env := newTestEnv(t)
	aid := authoring.GenerateAuthorID("来源作者")
	importTXT(t, env, "s.txt", "1  来源作者\n来源\n老王论坛\nkemono\n作品\na.jpg\n")

	// 缺省（省略 mode 字段）= replace：整体替换而非并入（并入会得到
	// [老王论坛 kemono 新站点x]，replace 才是 [新站点x]）。
	if resp := env.putAuthorSourcesMode(t, aid, []string{"新站点x"}, nil); resp.StatusCode != http.StatusOK {
		t.Fatalf("缺省 PUT 期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}
	if code, got := getAuthorSources(t, env, aid); code != http.StatusOK || !reflect.DeepEqual(got, []string{"新站点x"}) {
		t.Fatalf("缺省 mode 后 GET=（%d, %v）, want（200, [新站点x]）（整体替换）", code, got)
	}

	// 显式 replace：同现状。
	if resp := env.putAuthorSourcesMode(t, aid, []string{"站点y"}, modePtr("replace")); resp.StatusCode != http.StatusOK {
		t.Fatalf("显式 replace PUT 期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}
	if code, got := getAuthorSources(t, env, aid); code != http.StatusOK || !reflect.DeepEqual(got, []string{"站点y"}) {
		t.Fatalf("显式 replace 后 GET=（%d, %v）, want（200, [站点y]）", code, got)
	}

	// 非法 mode → 400，片段无任何副作用。
	before, _ := fragmentOf(t, env, "s.txt")
	resp := env.putAuthorSourcesMode(t, aid, []string{"z"}, modePtr("bogus"))
	closeBody(resp)
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("非法 mode 期望 400，得到 %d", resp.StatusCode)
	}
	after, _ := fragmentOf(t, env, "s.txt")
	if after != before {
		t.Fatalf("被拒请求不得改动片段:\n--- 前 ---\n%s\n--- 后 ---\n%s", before, after)
	}
}
