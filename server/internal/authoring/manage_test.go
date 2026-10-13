package authoring

// manage_test.go：作者管理文本手术纯函数与身份重建的回归锁定（2026-10-13，
// ADR-0024 修订 5）。核心不变量（当日事故根因）：
//   - AliasesForAuthorID 产物写块后，解析回读的 GenerateAuthorID(首别名) 必须
//     等于目标作者 id（往返恒等）——否则来源/作品写进错误作者的块；
//   - RemoveAuthorBlocks 后重新解析，该作者无块（统一重建不会复活作者行）；
//   - RewriteAuthorBlockNames 只动编号行别名：作品/来源区与编号数字保持不变。

import (
	"reflect"
	"strings"
	"testing"
)

// TestCanonicalAuthorNamesDisplaySeparatorLock 锁定 2026-10-13 修订：
// 「 / 」与两个以上空白同为别名分隔（显示名输入往返），无空白斜杠不拆。
func TestCanonicalAuthorNamesDisplaySeparatorLock(t *testing.T) {
	cases := []struct {
		in   string
		want []string
	}{
		{"hornyherring / Horny Herring Studios", []string{"hornyherring", "Horny Herring Studios"}},
		{"a/b", []string{"a/b"}},                 // 无空白斜杠：名字内字面，不拆
		{"a  b / c", []string{"a", "b", "c"}},    // 两种分隔混用：并集拆分
		{"甲 / 乙(出处备注)", []string{"甲", "乙"}},      // 括号备注去除照旧
		{"Night  Cry", []string{"Night", "Cry"}}, // 两个以上空白拆分（既有口径）
		{"a / b / c", []string{"a", "b", "c"}},   // 多个「 / 」
	}
	for _, c := range cases {
		if got := CanonicalAuthorNames(c.in); !reflect.DeepEqual(got, c.want) {
			t.Errorf("CanonicalAuthorNames(%q)=%v, want %v", c.in, got, c.want)
		}
	}
}

// TestAliasesForAuthorIDRoundTripLock 锁定身份重建往返恒等（当日事故回归）：
// 多别名显示名与「单别名含斜杠字面」两种历史形态分别推出正确别名列表；
// 产物经 AppendAuthorBlock 写块后回读 id 恒等于目标 id；推不出目标身份的
// 病态输入必须 ok=false（绝不写漂移块）。
func TestAliasesForAuthorIDRoundTripLock(t *testing.T) {
	cases := []struct {
		name        string
		displayName string
		authorID    string
		wantOK      bool
		wantFirst   string // 期望的别名列表首项（写块编号行首别名）
	}{
		{
			name:        "多别名显示名（标准形态）",
			displayName: "hornyherring / Horny Herring Studios",
			authorID:    "hornyherring",
			wantOK:      true,
			wantFirst:   "hornyherring",
		},
		{
			name:        "单别名含斜杠字面（事故历史形态：整串不拆）",
			displayName: "hornyherring / Horny Herring Studios",
			authorID:    "hornyherring__horny_herring_studios",
			wantOK:      true,
			wantFirst:   "hornyherring / Horny Herring Studios",
		},
		{
			name:        "单别名无分隔（普通作者）",
			displayName: "akiryo / AkiyamaRyo",
			authorID:    "akiryo",
			wantOK:      true,
			wantFirst:   "akiryo",
		},
		{
			name:        "显示名去掉括号备注后无有效别名（不可救）",
			displayName: "[3D]",
			authorID:    "author_deadbeef",
			wantOK:      false,
		},
		{
			name:        "显示名与身份不符（不可救）",
			displayName: "甲",
			authorID:    "乙",
			wantOK:      false,
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			names, ok := AliasesForAuthorID(c.displayName, c.authorID)
			if ok != c.wantOK {
				t.Fatalf("AliasesForAuthorID(%q, %q) ok=%v, want %v", c.displayName, c.authorID, ok, c.wantOK)
			}
			if !ok {
				return
			}
			if names[0] != c.wantFirst {
				t.Errorf("首别名=%q, want %q", names[0], c.wantFirst)
			}
			// 往返：写块 → 解析回读 → id 恒等（核心不变量）。
			content := AppendAuthorBlock("", names, nil, nil)
			blocks := ParseAuthorBlocks(content)
			if len(blocks) != 1 {
				t.Fatalf("写块回读块数=%d, want 1:\n%s", len(blocks), content)
			}
			if got := GenerateAuthorID(blocks[0].AuthorNames[0]); got != c.authorID {
				t.Errorf("回读 id=%q, want %q（写块身份漂移）:\n%s", got, c.authorID, content)
			}
		})
	}
}

// TestRemoveAuthorBlocksLock 锁定整块移除：跨块同名全清、其余块与作品/来源
// 区零损伤、末块连尾随空行清理；移除后重解析该作者无块（统一重建不复活）。
func TestRemoveAuthorBlocksLock(t *testing.T) {
	content := "1  甲\n来源\nsite-a\n作品\na.jpg\n\n\n\n" +
		"2  乙\n作品\nb.jpg\n\n\n\n" +
		"3  甲\n来源\nsite-b\n作品\nc.jpg\n"

	got, removed := RemoveAuthorBlocks(content, "甲")
	if !removed {
		t.Fatal("removed=false, want true")
	}
	for _, b := range ParseAuthorBlocks(got) {
		if GenerateAuthorID(b.AuthorNames[0]) == "甲" {
			t.Fatalf("移除后仍存在 甲 的块:\n%s", got)
		}
	}
	// 乙 的块完好（作品行/来源区零损伤）。
	blocks := ParseAuthorBlocks(got)
	if len(blocks) != 1 || GenerateAuthorID(blocks[0].AuthorNames[0]) != "乙" {
		t.Fatalf("残留块异常: %v", blocks)
	}
	if !reflect.DeepEqual(blocks[0].Works, []string{"b.jpg"}) {
		t.Errorf("乙 作品集=%v, want [b.jpg]", blocks[0].Works)
	}
	// 无匹配：原样返回。
	if orig, ok := RemoveAuthorBlocks(content, "丙"); ok || orig != content {
		t.Errorf("无匹配应原样返回且 removed=false")
	}
	// 末块（且仅一块）移除 → 内容为空块集。
	if out, ok := RemoveAuthorBlocks("1  甲\n作品\na.jpg\n", "甲"); !ok || len(ParseAuthorBlocks(out)) != 0 {
		t.Errorf("单块移除后应无块，out=%q ok=%v", out, ok)
	}
}

// TestRewriteAuthorBlockNamesLock 锁定编号行重写：编号数字保留、别名列表
// 整体替换、作品/来源区与其余块只字不动、跨块同名全改。
func TestRewriteAuthorBlockNamesLock(t *testing.T) {
	content := "126  hornyherring\n来源  hanime1\n作品\nx.mp4\n\n\n\n127  另一个人\n作品\ny.jpg\n"
	got, found := RewriteAuthorBlockNames(content, "hornyherring", []string{"hornyherring", "Horny Herring Studios"})
	if !found {
		t.Fatal("found=false, want true")
	}
	if !strings.Contains(got, "126  hornyherring  Horny Herring Studios\n") {
		t.Errorf("编号行未按预期重写:\n%s", got)
	}
	if !strings.Contains(got, "来源  hanime1\n作品\nx.mp4\n") {
		t.Errorf("作品/来源区被误动:\n%s", got)
	}
	if !strings.Contains(got, "127  另一个人\n作品\ny.jpg\n") {
		t.Errorf("其余块被误动:\n%s", got)
	}
	// 重写后回读：首别名派生新身份。
	for _, b := range ParseAuthorBlocks(got) {
		if len(b.AuthorNames) == 0 {
			continue
		}
		if GenerateAuthorID(b.AuthorNames[0]) != "hornyherring" {
			continue
		}
		if !reflect.DeepEqual(b.Sources, []string{"hanime1"}) || !reflect.DeepEqual(b.Works, []string{"x.mp4"}) {
			t.Errorf("重写后来源/作品=%v/%v, want [hanime1]/[x.mp4]", b.Sources, b.Works)
		}
	}
	// 无匹配原样返回。
	if orig, ok := RewriteAuthorBlockNames(content, "不存在", []string{"x"}); ok || orig != content {
		t.Error("无匹配应原样返回且 found=false")
	}
}
