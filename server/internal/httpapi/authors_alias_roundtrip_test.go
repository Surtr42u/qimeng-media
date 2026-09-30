package httpapi

// 无块新建作者块的别名往返防回归测试（ADR-0024 编辑端点）：authors 表的
// displayName 是规范别名按 " / " 的连接串，作者块缺失走新建时编号行必须按
// 空格分隔写回各别名——整串当单别名会让 ParseAuthorBlocks 回读的
// GenerateAuthorID(首别名) 漂移（幻影作者、统一重建丢关联）。

import (
	"context"
	"net/http"
	"testing"

	"qimeng-media/server/internal/authoring"
)

// seedMultiAliasAuthor 导入多别名作者片段（displayName 含 " / "）后删除该
// 片段：作者行经重建 upsert 保留、但不再有任何块（无块新建的触发前提）。
func seedMultiAliasAuthor(t *testing.T, e *testEnv) string {
	t.Helper()
	night := authoring.GenerateAuthorID("Night")
	importTXT(t, e, "night.txt", "1  Night  Cry\n作品\na.jpg\n")
	return night
}

// TestAuthorSourcesMultiAliasNoBlockRoundTrip（防回归 #1）：多别名作者无块
// 时 PUT /authors/{id}/sources → 最近导入片段新建块，编号行按空格分隔多
// 别名、解析回读 authorId == 原 id；来源区 GET 往返一致；统一重建后块形态
// 与来源区保持、无幻影作者。
func TestAuthorSourcesMultiAliasNoBlockRoundTrip(t *testing.T) {
	env := newTestEnv(t)
	night := seedMultiAliasAuthor(t, env)
	importTXT(t, env, "other.txt", "1  别人\n作品\nb.jpg\n")
	if code := deleteTxt(t, env, "night.txt"); code != http.StatusNoContent {
		t.Fatalf("删片段期望 204，得到 %d", code)
	}
	if a := findAuthor(t, listAuthors(t, env), night); a.DisplayName == nil || *a.DisplayName != "Night / Cry" {
		t.Fatalf("删片段后作者行应保留多别名 displayName：%+v", a)
	}

	if resp := env.putAuthorSources(t, night, []string{"forum-c"}); resp.StatusCode != http.StatusOK {
		t.Fatalf("PUT 来源区期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}
	content, _ := fragmentOf(t, env, "other.txt")
	if !containsLine(content, "2  Night  Cry") {
		t.Fatalf("新块编号行应为空格分隔多别名（漂移形态是 \"2  Night / Cry\"）:\n%s", content)
	}
	// blockWorksOf 内部断言块回读 id == night（漂移时 fatal）。
	_, srcs := blockWorksOf(t, content, night)
	if len(srcs) != 1 || srcs[0] != "forum-c" {
		t.Fatalf("新块来源区=%v, want [forum-c]", srcs)
	}
	if code, got := getAuthorSources(t, env, night); code != http.StatusOK || len(got) != 1 || got[0] != "forum-c" {
		t.Fatalf("GET 来源区=（%d, %v）, want（200, [forum-c]）", code, got)
	}

	// 统一重建：块按 " / " 连接回读仍命中原 id，来源区保留，无幻影作者。
	if _, _, code := rebuildTxt(t, env); code != http.StatusOK {
		t.Fatalf("重建期望 200，得到 %d", code)
	}
	content, _ = fragmentOf(t, env, "other.txt")
	if _, srcs = blockWorksOf(t, content, night); len(srcs) != 1 || srcs[0] != "forum-c" {
		t.Fatalf("重建后来源区=%v, want [forum-c]", srcs)
	}
	for _, a := range listAuthors(t, env) {
		if a.Id != nil && *a.Id == "night__cry" {
			t.Fatalf("重建后出现幻影作者 night__cry：%+v", listAuthors(t, env))
		}
	}
}

// TestAssetAuthorsMultiAliasNoBlockRoundTrip（防回归 #2）：多别名作者无块时
// PUT /assets/{assetId}/authors 增关联 → 自动承载片段新建块，编号行按空格
// 分隔多别名、回读 id 不漂移；关联表落库；统一重建后关联存活、无幻影作者。
func TestAssetAuthorsMultiAliasNoBlockRoundTrip(t *testing.T) {
	env := newTestEnv(t)
	night := seedMultiAliasAuthor(t, env)
	if code := deleteTxt(t, env, "night.txt"); code != http.StatusNoContent {
		t.Fatalf("删片段期望 204，得到 %d", code)
	}
	d := uploadOne(t, env, "f.jpg")
	if resp := env.putAssetAuthors(t, d.Id.String(), []string{night}); resp.StatusCode != http.StatusOK {
		t.Fatalf("PUT 资产作者期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}

	content, ok := fragmentOf(t, env, authoring.AutoFragmentFilename)
	if !ok {
		t.Fatal("库中无片段时应自动创建承载片段")
	}
	if !containsLine(content, "1  Night  Cry") {
		t.Fatalf("新块编号行应为空格分隔多别名（漂移形态是 \"1  Night / Cry\"）:\n%s", content)
	}
	works, _ := blockWorksOf(t, content, night)
	if len(works) != 1 || works[0] != "f.jpg" {
		t.Fatalf("新块作品行=%v, want [f.jpg]", works)
	}
	refs, err := env.q.ListAssetAuthorRefs(context.Background(), d.Id.String())
	if err != nil || len(refs) != 1 || refs[0].ID != night || refs[0].Type != authoring.AuthorTypeRegular {
		t.Fatalf("asset_authors=%v err=%v, want [{%s regular}]", refs, err, night)
	}

	// 统一重建：块往返恒等 → 关联按作品行存活、不产生幻影作者。
	if _, _, code := rebuildTxt(t, env); code != http.StatusOK {
		t.Fatalf("重建期望 200，得到 %d", code)
	}
	if a := findAuthor(t, listAuthors(t, env), night); a.FileCount == nil || *a.FileCount != 1 {
		t.Fatalf("重建后 fileCount=%v, want 1（关联存活）", a.FileCount)
	}
	refs, err = env.q.ListAssetAuthorRefs(context.Background(), d.Id.String())
	if err != nil || len(refs) != 1 || refs[0].ID != night {
		t.Fatalf("重建后 asset_authors=%v err=%v, want 关联存活", refs, err)
	}
	for _, a := range listAuthors(t, env) {
		if a.Id != nil && *a.Id == "night__cry" {
			t.Fatalf("重建后出现幻影作者 night__cry：%+v", listAuthors(t, env))
		}
	}
}

// TestAuthorNoBlockSingleAliasStillWorks（防回归 #3）：displayName 无 " / "
// 的普通作者无块新建仍正常——来源区与资产关联两个编辑端点、编号行单别名、
// 回读 id 恒等。
func TestAuthorNoBlockSingleAliasStillWorks(t *testing.T) {
	env := newTestEnv(t)
	z := authoring.GenerateAuthorID("Z")
	importTXT(t, env, "c.txt", "Z\n") // 格式 C：零片段常规作者
	d := uploadOne(t, env, "f.jpg")

	if resp := env.putAuthorSources(t, z, []string{"forum-c"}); resp.StatusCode != http.StatusOK {
		t.Fatalf("PUT 来源区期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}
	content, _ := fragmentOf(t, env, authoring.AutoFragmentFilename)
	if !containsLine(content, "1  Z") {
		t.Fatalf("编号行应为单别名 Z:\n%s", content)
	}
	if _, srcs := blockWorksOf(t, content, z); len(srcs) != 1 || srcs[0] != "forum-c" {
		t.Fatalf("来源区=%v, want [forum-c]", srcs)
	}

	if resp := env.putAssetAuthors(t, d.Id.String(), []string{z}); resp.StatusCode != http.StatusOK {
		t.Fatalf("PUT 资产作者期望 200，得到 %d", resp.StatusCode)
	} else {
		closeBody(resp)
	}
	content, _ = fragmentOf(t, env, authoring.AutoFragmentFilename)
	if works, srcs := blockWorksOf(t, content, z); len(works) != 1 || works[0] != "f.jpg" || len(srcs) != 1 {
		t.Fatalf("块=%v/%v, want works [f.jpg] + sources [forum-c]", works, srcs)
	}
}
