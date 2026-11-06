package httpapi

// authors_merge_test.go：POST /authors/merge 的 HTTP 契约测试。事务/片段/
// 关联/档案语义由 authoredit 编排测试锁定；这里只锁协议面：
//   - 200 响应计数与不可撤销结果（源身份从列表消失、TXT 块并入目标）；
//   - 400 INVALID_PARAM（同 ID/空 ID/COS）与 404 NOT_FOUND 映射。
// 复用 browse_test.go 的 newTestEnv 与 authors_test.go 的 importTXT。

import (
	"context"
	"net/http"
	"reflect"
	"testing"

	"qimeng-media/server/internal/authoring"
	"qimeng-media/server/internal/httpapi/gen"
	"qimeng-media/server/internal/store"
	"qimeng-media/server/internal/store/db"
)

// mergeAuthors POST /authors/merge（返回状态码与解出的结果；非 200 不解析）。
func (e *testEnv) mergeAuthors(t *testing.T, sourceID, targetID string) (int, gen.AuthorMergeResult) {
	t.Helper()
	resp := e.do(t, "POST", "/api/v1/authors/merge", mergeBody(sourceID, targetID))
	defer closeBody(resp)
	var out gen.AuthorMergeResult
	if resp.StatusCode == http.StatusOK {
		if err := decodeBody(resp, &out); err != nil {
			t.Fatalf("解析合并结果失败: %v", err)
		}
	}
	return resp.StatusCode, out
}

// mergeBody 编码合并请求体（jsonQuote 保证 ID 合法转义）。
func mergeBody(sourceID, targetID string) string {
	return "{\"sourceAuthorId\":" + jsonQuote(sourceID) + ",\"targetAuthorId\":" + jsonQuote(targetID) + "}"
}

// TestMergeAuthorsEndpoint：200 计数与终态——源身份删除、目标块原位保留并
// 并入源块别名/作品/来源、双端重复关联去重、关注并集。
func TestMergeAuthorsEndpoint(t *testing.T) {
	env := newTestEnv(t)
	ctx := context.Background()
	src := authoring.GenerateAuthorID("源乙")
	tgt := authoring.GenerateAuthorID("目标甲")

	// a.jpg 同时被两位作者的作品行命中 → 双端重复关联；b.jpg 仅源命中。
	importTXT(t, env, "目标.txt", "1  目标甲  目标别名\n来源  site-t\n作品\na.jpg\n")
	importTXT(t, env, "源.txt", "1  源乙  源别名\n来源  site-s\n作品\na.jpg\nb.jpg\n")
	if _, err := env.q.SetAuthorFollow(ctx, db.SetAuthorFollowParams{Followed: 1, ID: src}); err != nil {
		t.Fatalf("预置源关注失败: %v", err)
	}

	code, result := env.mergeAuthors(t, src, tgt)
	if code != http.StatusOK {
		t.Fatalf("合并期望 200，得到 %d", code)
	}
	if result.SourceAuthorId != src || result.TargetAuthorId != tgt {
		t.Fatalf("响应身份字段=%+v（want %s→%s）", result, src, tgt)
	}
	if result.AssetsMoved != 1 || result.AssociationsDeduped != 1 {
		t.Fatalf("关联计数=%d/%d, want 1/1", result.AssetsMoved, result.AssociationsDeduped)
	}
	if result.WorksMerged != 1 || result.SourcesMerged != 1 || result.AliasesMerged != 2 {
		t.Fatalf("TXT 计数=%+v, want works=1 sources=1 aliases=2", result)
	}
	if !result.FollowsMerged || !result.DeletedSource {
		t.Fatalf("关注/删除标记=%+v, want both true", result)
	}
	if result.ProfileMoved || result.SourceProfileDiscarded || result.UploadEntriesMoved != 0 {
		t.Fatalf("本用例无档案/上传条目，结果=%+v", result)
	}

	// 源身份从列表消失，target 行保留且关注并集生效。
	list := listAuthors(t, env)
	if _, found := findAuthorOptional(list, src); found {
		t.Fatalf("源身份 %s 仍在作者列表", src)
	}
	if row := findAuthor(t, list, tgt); row.Followed == nil || !*row.Followed {
		t.Fatalf("target 关注标记未并集：%+v", row)
	}

	// 目标块保留在目标片段原位置，源块从源片段删除。
	targetContent, ok := fragmentOf(t, env, "目标.txt")
	if !ok {
		t.Fatal("目标片段缺失")
	}
	works, sources := blockWorksOf(t, targetContent, tgt)
	if !reflect.DeepEqual(works, []string{"a.jpg", "b.jpg"}) || !reflect.DeepEqual(sources, []string{"site-t", "site-s"}) {
		t.Fatalf("目标块作品/来源=%v/%v, want [a.jpg b.jpg]/[site-t site-s]", works, sources)
	}
	sourceContent, ok := fragmentOf(t, env, "源.txt")
	if !ok {
		t.Fatal("源片段应保留（只删块）")
	}
	if _, found := findBlockOptional(t, sourceContent, src); found {
		t.Fatalf("源块未删除:\n%s", sourceContent)
	}
}

// findBlockOptional 在片段中查作者块（存在性断言用，不 Fatal）。
func findBlockOptional(t *testing.T, content, authorID string) (authoring.AuthorBlock, bool) {
	t.Helper()
	for _, block := range authoring.ParseAuthorBlocks(content) {
		if len(block.AuthorNames) > 0 && authoring.GenerateAuthorID(block.AuthorNames[0]) == authorID {
			return block, true
		}
	}
	return authoring.AuthorBlock{}, false
}

// TestMergeAuthorsEndpointErrors：400 INVALID_PARAM（同 ID/空 ID/COS）与
// 404 NOT_FOUND（任一不存在）沿用作者管理现有错误模型；失败零副作用。
func TestMergeAuthorsEndpointErrors(t *testing.T) {
	env := newTestEnv(t)
	ctx := context.Background()
	regular := authoring.GenerateAuthorID("常规作者")
	other := authoring.GenerateAuthorID("另一作者")
	for _, row := range []struct {
		id, display, typ string
	}{
		{regular, "常规作者", authoring.AuthorTypeRegular},
		{other, "另一作者", authoring.AuthorTypeRegular},
		{"cos_merge_test", "COS作者", authoring.AuthorTypeCos},
	} {
		if err := env.q.UpsertAuthor(ctx, db.UpsertAuthorParams{
			ID: row.id, DisplayName: row.display, Type: row.typ, CreatedAt: store.FormatTimestamp(env.clock.Now()),
		}); err != nil {
			t.Fatalf("预置作者 %s 失败: %v", row.id, err)
		}
	}

	cases := []struct {
		name           string
		source, target string
		status         int
		code           string
	}{
		{"同 ID", regular, regular, http.StatusBadRequest, codeInvalidParam},
		{"空 ID", "", regular, http.StatusBadRequest, codeInvalidParam},
		{"源是 COS", "cos_merge_test", regular, http.StatusBadRequest, codeInvalidParam},
		{"目标是 COS", regular, "cos_merge_test", http.StatusBadRequest, codeInvalidParam},
		{"源不存在", "ghost-merge-src", regular, http.StatusNotFound, codeNotFound},
		{"目标不存在", regular, "ghost-merge-tgt", http.StatusNotFound, codeNotFound},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			resp := env.do(t, "POST", "/api/v1/authors/merge", mergeBody(tc.source, tc.target))
			defer closeBody(resp)
			if resp.StatusCode != tc.status {
				t.Fatalf("状态码=%d, want %d", resp.StatusCode, tc.status)
			}
			if got := errorCode(t, resp); got != tc.code {
				t.Fatalf("错误码=%s, want %s", got, tc.code)
			}
		})
	}

	// 全部失败路径零副作用：作者表原样、无片段写入。
	if got := allAuthorIDsForTest(t, env); len(got) != 3 {
		t.Fatalf("失败后作者表被改动：%v", got)
	}
}

// allAuthorIDsForTest 返回作者表 id 集（错误矩阵零副作用断言）。
func allAuthorIDsForTest(t *testing.T, e *testEnv) []string {
	t.Helper()
	rows, err := e.q.ListAuthors(context.Background())
	if err != nil {
		t.Fatalf("查询作者失败: %v", err)
	}
	ids := make([]string, 0, len(rows))
	for _, row := range rows {
		ids = append(ids, row.ID)
	}
	return ids
}
