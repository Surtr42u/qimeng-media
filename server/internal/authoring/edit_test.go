package authoring

// edit_test.go：编辑文本手术纯函数的行为锁定（表驱动）。核心不变量：
// 写出（删除/替换）后的片段必须能被 ParseAuthorBlocks 原样读回——
// sources/works 往返恒等，与统一重建共用同一解析器口径。

import (
	"reflect"
	"strings"
	"testing"
)

// editBlockOf 取 content 中 authorID 首遇块的解析结果（无块报错）。
func editBlockOf(t *testing.T, content, authorID string) AuthorBlock {
	t.Helper()
	for _, b := range ParseAuthorBlocks(content) {
		if len(b.AuthorNames) > 0 && GenerateAuthorID(b.AuthorNames[0]) == authorID {
			return b
		}
	}
	t.Fatalf("片段中不存在作者 %s 的块:\n%s", authorID, content)
	return AuthorBlock{}
}

// editHasLine 判断整行存在于 content（测试侧简易断言，非解析器语义）。
func editHasLine(content, line string) bool {
	for _, l := range strings.Split(content, "\n") {
		if strings.TrimSpace(l) == line {
			return true
		}
	}
	return false
}

func TestRemoveWorks(t *testing.T) {
	id := GenerateAuthorID("甲")
	base := "1  甲\n来源\nsite-a\n作品\na.jpg\nb.png\nc.mp4\n\n2  乙\n作品\na.jpg\n"
	cases := []struct {
		name      string
		content   string
		filenames []string
		wantWorks []string
		wantGone  []string // 断言被删行不再以整行形态出现
		removed   []string
	}{
		{
			name:      "精确匹配删除多行",
			content:   base,
			filenames: []string{"a.jpg", "c.mp4"},
			wantWorks: []string{"b.png"},
			removed:   []string{"a.jpg", "c.mp4"},
		},
		{
			name:      "大小写与空格变体归一化匹配",
			content:   base,
			filenames: []string{"B .PNG"},
			wantWorks: []string{"a.jpg", "c.mp4"},
			removed:   []string{"b.png"},
		},
		{
			name:      "扩展名保留不误伤同名",
			content:   "1  甲\n作品\na.png\na.mp4\n",
			filenames: []string{"a.png"},
			wantWorks: []string{"a.mp4"},
			removed:   []string{"a.png"},
		},
		{
			name:      "只动目标作者块",
			content:   base,
			filenames: []string{"a.jpg"},
			wantWorks: []string{"b.png", "c.mp4"},
			removed:   []string{"a.jpg"},
		},
		{
			name:      "块不存在原样返回",
			content:   base,
			filenames: []string{"a.jpg"},
			wantWorks: nil, // 查的是"乙"块（见下方 authorID 覆写）
			removed:   nil,
		},
		{
			name:      "无匹配行原样返回",
			content:   base,
			filenames: []string{"ghost.jpg"},
			wantWorks: []string{"a.jpg", "b.png", "c.mp4"},
			removed:   nil,
		},
		{
			name:      "来源区同名行不删",
			content:   "1  甲\n来源\na.jpg\n作品\nb.jpg\n",
			filenames: []string{"a.jpg"},
			wantWorks: []string{"b.jpg"},
			removed:   nil,
		},
		{
			name:      "数字开头作品行可删",
			content:   "1  甲\n作品\n12 a.png\nb.jpg\n",
			filenames: []string{"12 a.png"},
			wantWorks: []string{"b.jpg"},
			removed:   []string{"12 a.png"},
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			authorID := id
			if tc.name == "块不存在原样返回" {
				authorID = GenerateAuthorID("丙")
			}
			got, removed := RemoveWorks(tc.content, authorID, tc.filenames)
			if tc.name == "块不存在原样返回" {
				if got != tc.content || removed != nil {
					t.Fatalf("块不存在应原样返回：removed=%v\n%s", removed, got)
				}
				return
			}
			b := editBlockOf(t, got, authorID)
			if !reflect.DeepEqual(b.Works, tc.wantWorks) {
				t.Fatalf("删除后作品行=%v, want %v\n%s", b.Works, tc.wantWorks, got)
			}
			if !reflect.DeepEqual(removed, tc.removed) {
				t.Fatalf("removed=%v, want %v", removed, tc.removed)
			}
			// 来源区不因删作品行而变。
			before := editBlockOf(t, tc.content, authorID)
			if !reflect.DeepEqual(editBlockOf(t, got, authorID).Sources, before.Sources) {
				t.Fatalf("来源区被误删：%v", before.Sources)
			}
		})
	}
}

func TestRemoveWorksKeepsOtherBlocks(t *testing.T) {
	base := "1  甲\n作品\na.jpg\n\n2  乙\n作品\na.jpg\n"
	got, removed := RemoveWorks(base, GenerateAuthorID("甲"), []string{"a.jpg"})
	if removed == nil {
		t.Fatal("甲块的 a.jpg 应被删除")
	}
	if b := editBlockOf(t, got, GenerateAuthorID("乙")); len(b.Works) != 1 || b.Works[0] != "a.jpg" {
		t.Fatalf("乙块不应受影响：%v", b.Works)
	}
}

func TestReplaceSources(t *testing.T) {
	id := GenerateAuthorID("甲")
	cases := []struct {
		name       string
		content    string
		sources    []string
		wantSrcs   []string
		wantWorks  []string
		wantRawHas string // 断言写出原文包含的行（空=不断言）
		wantRawNo  string // 断言写出原文不包含的行（空=不断言）
	}{
		{
			name:       "替换既有来源区",
			content:    "1  甲\n来源\nsite-a\nforum-c\n作品\na.jpg\n",
			sources:    []string{"新站点"},
			wantSrcs:   []string{"新站点"},
			wantWorks:  []string{"a.jpg"},
			wantRawNo:  "site-a",
			wantRawHas: "来源  新站点",
		},
		{
			name:      "清空来源区标记行不残留",
			content:   "1  甲\n来源\nsite-a\n作品\na.jpg\n",
			sources:   nil,
			wantSrcs:  nil,
			wantWorks: []string{"a.jpg"},
			wantRawNo: "来源",
		},
		{
			name:       "出处标记行形态一并清除",
			content:    "1  甲\n出处  site-a\n作品\na.jpg\n",
			sources:    []string{"新站点"},
			wantSrcs:   []string{"新站点"},
			wantWorks:  []string{"a.jpg"},
			wantRawNo:  "site-a",
			wantRawHas: "来源  新站点",
		},
		{
			name:      "无来源区插在作品标记行前",
			content:   "1  甲\n作品\na.jpg\n",
			sources:   []string{"新站点"},
			wantSrcs:  []string{"新站点"},
			wantWorks: []string{"a.jpg"},
		},
		{
			name:      "裸块插在编号行后",
			content:   "1  甲\n",
			sources:   []string{"新站点"},
			wantSrcs:  []string{"新站点"},
			wantWorks: nil,
		},
		{
			name:      "裸块顶到内容末尾",
			content:   "0  无关\n作品\nz.jpg\n1  甲",
			sources:   []string{"新站点"},
			wantSrcs:  []string{"新站点"},
			wantWorks: nil,
		},
		{
			name:      "输入去重",
			content:   "1  甲\n来源\nsite-a\n",
			sources:   []string{"a", "a", " b "},
			wantSrcs:  []string{"a", "b"},
			wantWorks: nil,
		},
		{
			name:       "来源区与作品区交错的病态布局",
			content:    "1  甲\n作品\nw1\n来源\ns1\n作品\nw2\n",
			sources:    []string{"新站点"},
			wantSrcs:   []string{"新站点"},
			wantWorks:  []string{"w1", "w2"},
			wantRawNo:  "s1",
			wantRawHas: "来源  新站点",
		},
		{
			name:      "来源区与作品区交错的病态布局清空",
			content:   "1  甲\n作品\nw1\n来源\ns1\n作品\nw2\n",
			sources:   []string{},
			wantSrcs:  nil,
			wantWorks: []string{"w1", "w2"},
		},
		{
			name:      "幂等替换",
			content:   "1  甲\n来源  site-a\n作品\na.jpg\n",
			sources:   []string{"site-a"},
			wantSrcs:  []string{"site-a"},
			wantWorks: []string{"a.jpg"},
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, found := ReplaceSources(tc.content, id, tc.sources)
			if !found {
				t.Fatalf("块应命中:\n%s", tc.content)
			}
			b := editBlockOf(t, got, id)
			if tc.wantSrcs == nil && len(b.Sources) != 0 {
				t.Fatalf("来源区=%v, want 空\n%s", b.Sources, got)
			}
			if !reflect.DeepEqual(b.Sources, tc.wantSrcs) {
				t.Fatalf("来源区=%v, want %v\n%s", b.Sources, tc.wantSrcs, got)
			}
			if !reflect.DeepEqual(b.Works, tc.wantWorks) {
				t.Fatalf("作品区=%v, want %v（替换来源不得动作品区）\n%s", b.Works, tc.wantWorks, got)
			}
			if tc.wantRawHas != "" && !editHasLine(got, tc.wantRawHas) {
				t.Fatalf("写出原文缺 %q:\n%s", tc.wantRawHas, got)
			}
			if tc.wantRawNo != "" && editHasLine(got, tc.wantRawNo) {
				t.Fatalf("写出原文不应残留 %q:\n%s", tc.wantRawNo, got)
			}
		})
	}
}

func TestReplaceSourcesBlockNotFound(t *testing.T) {
	base := "1  甲\n来源\nsite-a\n"
	got, found := ReplaceSources(base, GenerateAuthorID("乙"), []string{"x"})
	if found {
		t.Fatal("块不存在应返回 found=false")
	}
	if got != base {
		t.Fatalf("块不存在应原样返回:\n%s", got)
	}
}

func TestReplaceSourcesIdempotent(t *testing.T) {
	id := GenerateAuthorID("甲")
	once, _ := ReplaceSources("1  甲\n作品\na.jpg\n", id, []string{"site-a", "forum-c"})
	twice, _ := ReplaceSources(once, id, []string{"site-a", "forum-c"})
	if once != twice {
		t.Fatalf("幂等替换应逐字一致：\n--- 一次 ---\n%s\n--- 两次 ---\n%s", once, twice)
	}
}

func TestPruneUploadEntries(t *testing.T) {
	id := GenerateAuthorID("甲")
	nid := GenerateAuthorID("乙")
	entries := []UploadEntry{
		{AuthorID: id, DisplayName: "甲", Names: []string{"甲"}, Works: []string{"f.jpg", "keep.png"}, Sources: []string{"site-a"}},
		{AuthorID: nid, DisplayName: "乙", Names: []string{"乙"}, Works: []string{"g.jpg"}},
	}
	content := "1  甲\n来源\nsite-a\n作品\nkeep.png\n\n2  乙\n作品\ng.jpg\n"
	got := PruneUploadEntries(entries, content)
	if len(got) != 2 {
		t.Fatalf("条目数=%d, want 2（甲保留缺行条目、乙完整保留）：%+v", len(got), got)
	}
	if !reflect.DeepEqual(got[0].Works, []string{"keep.png"}) {
		t.Fatalf("甲条目应剔除已不存在的 f.jpg：%+v", got[0].Works)
	}
	if !reflect.DeepEqual(got[0].Sources, []string{"site-a"}) {
		t.Fatalf("来源行与作品行独立裁剪：%+v", got[0].Sources)
	}
	if got[0].DisplayName != "甲" || !reflect.DeepEqual(got[0].Names, []string{"甲"}) {
		t.Fatalf("保留条目的 DisplayName/Names 不得丢失：%+v", got[0])
	}
	if !reflect.DeepEqual(got[1], entries[1]) {
		t.Fatalf("完整保留的条目应原样透传：%+v", got[1])
	}

	// 块被整体移除 → 条目整条剔除。
	onlyB := "2  乙\n作品\ng.jpg\n"
	got = PruneUploadEntries(entries, onlyB)
	if len(got) != 1 || got[0].AuthorID != nid {
		t.Fatalf("块消失的条目应整条剔除：%+v", got)
	}

	// 无缺失时原样返回（含同一底层数组，不产生新分配）。
	full := "1  甲\n来源\nsite-a\n作品\nf.jpg\nkeep.png\n\n2  乙\n作品\ng.jpg\n"
	got = PruneUploadEntries(entries, full)
	if len(got) != 2 {
		t.Fatalf("无缺失应原样返回：%+v", got)
	}
}
