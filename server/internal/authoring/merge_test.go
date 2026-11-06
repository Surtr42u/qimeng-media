package authoring

// merge_test.go：作者块合并纯函数的行为锁定（POST /authors/merge 的 TXT 半场）。
// 核心不变量：目标块原位保留、别名/作品/来源保序去重并入，写出内容可被
// ParseAuthorBlocks 原样读回（target 身份不变）。

import (
	"reflect"
	"testing"
)

func TestMergeAuthorBlock(t *testing.T) {
	targetID := GenerateAuthorID("目标甲")
	content := "1  目标甲  目标别名\n来源  site-a\n作品\na.jpg\n\n2  旁观者\n作品\nx.jpg\n"

	got, stats, found := MergeAuthorBlock(content, targetID,
		[]string{"目标甲", "源乙", "源别名"},
		[]string{"a.jpg", "b.png", "b.png"},
		[]string{"site-a", "site-b"})
	if !found {
		t.Fatalf("目标块应命中:\n%s", content)
	}
	block := editBlockOf(t, got, targetID)
	if want := []string{"目标甲", "目标别名", "源乙", "源别名"}; !reflect.DeepEqual(block.AuthorNames, want) {
		t.Fatalf("别名=%v, want %v\n%s", block.AuthorNames, want, got)
	}
	if want := []string{"a.jpg", "b.png"}; !reflect.DeepEqual(block.Works, want) {
		t.Fatalf("作品行=%v, want %v\n%s", block.Works, want, got)
	}
	if want := []string{"site-a", "site-b"}; !reflect.DeepEqual(block.Sources, want) {
		t.Fatalf("来源区=%v, want %v\n%s", block.Sources, want, got)
	}
	if stats.AliasesMerged != 2 || stats.WorksMerged != 1 || stats.SourcesMerged != 1 {
		t.Fatalf("计数=%+v, want aliases=2 works=1 sources=1", stats)
	}
	if !reflect.DeepEqual(stats.Aliases, block.AuthorNames) {
		t.Fatalf("返回别名=%v, want 回读别名=%v", stats.Aliases, block.AuthorNames)
	}
	// 相邻作者块与作品行不受影响。
	other := editBlockOf(t, got, GenerateAuthorID("旁观者"))
	if !reflect.DeepEqual(other.Works, []string{"x.jpg"}) {
		t.Fatalf("旁观者块被误动：%+v", other)
	}
	// 编号数字保留在目标块首行。
	if !editHasLine(got, "1  目标甲  目标别名  源乙  源别名") {
		t.Fatalf("目标编号行未按合并别名重写:\n%s", got)
	}
}

func TestMergeAuthorBlockIdempotent(t *testing.T) {
	targetID := GenerateAuthorID("目标甲")
	content := "1  目标甲\n来源  site-a\n作品\na.jpg\n"
	addAliases := []string{"源乙"}
	addWorks := []string{"b.png"}
	addSources := []string{"site-b"}

	once, stats, found := MergeAuthorBlock(content, targetID, addAliases, addWorks, addSources)
	if !found || stats.AliasesMerged != 1 || stats.WorksMerged != 1 || stats.SourcesMerged != 1 {
		t.Fatalf("首次合并异常: found=%v stats=%+v", found, stats)
	}
	twice, stats2, found2 := MergeAuthorBlock(once, targetID, addAliases, addWorks, addSources)
	if !found2 || twice != once {
		t.Fatalf("重复合并应逐字幂等:\n--- once ---\n%s\n--- twice ---\n%s", once, twice)
	}
	if stats2.AliasesMerged != 0 || stats2.WorksMerged != 0 || stats2.SourcesMerged != 0 {
		t.Fatalf("重复合并计数应归零：%+v", stats2)
	}

	empty, stats3, found3 := MergeAuthorBlock(content, targetID, nil, nil, nil)
	if !found3 || empty != content {
		t.Fatalf("空载荷应原样返回（found=true）：\n%s", empty)
	}
	if stats3.AliasesMerged+stats3.WorksMerged+stats3.SourcesMerged != 0 {
		t.Fatalf("空载荷计数应归零：%+v", stats3)
	}
}

func TestMergeAuthorBlockTargetMissing(t *testing.T) {
	content := "1  甲\n来源  site-a\n作品\na.jpg\n"
	got, stats, found := MergeAuthorBlock(content, GenerateAuthorID("乙"), []string{"乙"}, []string{"b.png"}, []string{"site-b"})
	if found {
		t.Fatal("目标块不存在应返回 found=false（调用方走新建块路径）")
	}
	if got != content {
		t.Fatalf("目标块不存在应原样返回:\n%s", got)
	}
	if stats.AliasesMerged+stats.WorksMerged+stats.SourcesMerged != 0 {
		t.Fatalf("未命中不应产生计数：%+v", stats)
	}
}
