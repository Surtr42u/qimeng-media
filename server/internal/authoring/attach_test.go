package authoring

// attach.go 上传挂靠纯函数测试（REQ-上传指定作者与来源 §3.3）。
// 核心断言形态是「往返锁定」：手术后的内容必须被 ParseAuthorBlocks 原样
// 读回（REQ 验收 #9/#13/#8 的根）——新行落对区域、既旧行分毫不动、无重复。

import (
	"reflect"
	"strings"
	"testing"
)

// blockOf 取 content 中 authorID 对应的解析块（往返断言的取材入口）。
func blockOf(t *testing.T, content, authorID string) AuthorBlock {
	t.Helper()
	for _, b := range ParseAuthorBlocks(content) {
		if len(b.AuthorNames) > 0 && GenerateAuthorID(b.AuthorNames[0]) == authorID {
			return b
		}
	}
	t.Fatalf("作者 %s 未在内容中解析出块\n内容:\n%s", authorID, content)
	return AuthorBlock{}
}

// ---------- AppendWorks ----------

func TestAppendWorks(t *testing.T) {
	cases := []struct {
		name    string
		content string
		author  string
		works   []string
		want    string // 空串=期望内容不变
		wantOK  bool
	}{
		{
			name:    "有作品区_续写在块尾",
			content: "1  bamhor\n来源\nsite-a\n作品\na.png\n",
			author:  "bamhor",
			works:   []string{"b.png"},
			want:    "1  bamhor\n来源\nsite-a\n作品\na.png\nb.png\n",
			wantOK:  true,
		},
		{
			name:    "裸块_先补作品标记",
			content: "1  bamhor\n",
			author:  "bamhor",
			works:   []string{"a.png"},
			want:    "1  bamhor\n作品\na.png\n",
			wantOK:  true,
		},
		{
			name:    "只有来源区的块_补作品标记开新区",
			content: "1  bamhor\n来源\nsite-a\n",
			author:  "bamhor",
			works:   []string{"a.png"},
			want:    "1  bamhor\n来源\nsite-a\n作品\na.png\n",
			wantOK:  true,
		},
		{
			name:    "作者不在任何块_found为假",
			content: "1  bamhor\n作品\na.png\n",
			author:  "nagoonimation",
			works:   []string{"x.png"},
			want:    "",
			wantOK:  false,
		},
		{
			name:    "既有行去重_内容不动",
			content: "1  bamhor\n作品\na.png\n",
			author:  "bamhor",
			works:   []string{"a.png", "a.png"},
			want:    "1  bamhor\n作品\na.png\n",
			wantOK:  true,
		},
		{
			name:    "多块场景_只动目标块且不越界",
			content: "1  aaa\n作品\na.png\n\n2  bbb\n作品\nb.png\n",
			author:  "bbb",
			works:   []string{"c.png"},
			want:    "1  aaa\n作品\na.png\n\n2  bbb\n作品\nb.png\nc.png\n",
			wantOK:  true,
		},
		{
			name:    "数字开头作品行_续写不被当新块",
			content: "6  Takerskiy\n出处  site-a\n作品\n2077  帕南.png\n",
			author:  "takerskiy",
			works:   []string{"2077  朱迪.png"},
			want:    "6  Takerskiy\n出处  site-a\n作品\n2077  帕南.png\n2077  朱迪.png\n",
			wantOK:  true,
		},
		{
			name:    "块尾前有空行_插在最后一个非空行后",
			content: "1  aaa\n作品\na.png\n\n\n2  bbb\n",
			author:  "aaa",
			works:   []string{"new.png"},
			want:    "1  aaa\n作品\na.png\nnew.png\n\n\n2  bbb\n",
			wantOK:  true,
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, found := AppendWorks(c.content, c.author, c.works)
			if found != c.wantOK {
				t.Fatalf("found=%v, want %v", found, c.wantOK)
			}
			want := c.want
			if want == "" {
				want = c.content
			}
			if got != want {
				t.Errorf("内容不符:\ngot:\n%q\nwant:\n%q", got, want)
			}
			if !c.wantOK {
				return
			}
			// 往返锁定：新行被解析进目标作者块的 Works，且无重复。
			block := blockOf(t, got, c.author)
			seen := make(map[string]bool)
			for _, w := range block.Works {
				if seen[w] {
					t.Errorf("Works 出现重复行 %q", w)
				}
				seen[w] = true
			}
			for _, w := range c.works {
				if !seen[w] {
					t.Errorf("解析回的 Works 缺少新行 %q（got=%v）", w, block.Works)
				}
			}
		})
	}
}

// 幂等：同一输入重复追加，内容稳定不再变化（REQ §3.3 第 4 条）。
func TestAppendWorksIdempotent(t *testing.T) {
	content := "1  bamhor\n出处  site-a\n作品\na.png\n"
	once, _ := AppendWorks(content, "bamhor", []string{"b.png"})
	twice, _ := AppendWorks(once, "bamhor", []string{"b.png"})
	if twice != once {
		t.Errorf("二次追加改变了内容:\nonce:\n%q\ntwice:\n%q", once, twice)
	}
	// 来源侧同样幂等。
	s1, _ := AppendSources(content, "bamhor", []string{"pixiv"})
	s2, _ := AppendSources(s1, "bamhor", []string{"pixiv"})
	if s2 != s1 {
		t.Errorf("来源二次追加改变了内容:\nonce:\n%q\ntwice:\n%q", s1, s2)
	}
}

// ---------- AppendSources ----------

func TestAppendSources(t *testing.T) {
	cases := []struct {
		name    string
		content string
		author  string
		sources []string
		want    string
		wantOK  bool
	}{
		{
			name:    "出处带平台名形态_新行并入来源区",
			content: "1  that_maskey\n出处  site-a\n作品\na.png\n",
			author:  "that_maskey",
			sources: []string{"pixiv"},
			want:    "1  that_maskey\n出处  site-a\npixiv\n作品\na.png\n",
			wantOK:  true,
		},
		{
			name:    "裸来源标记_既有行去重只补新行",
			content: "1  aaa\n来源\nsite-a\n作品\na.png\n",
			author:  "aaa",
			sources: []string{"site-a", "site-f"},
			want:    "1  aaa\n来源\nsite-a\nsite-f\n作品\na.png\n",
			wantOK:  true,
		},
		{
			name:    "来源区在块尾_无作品标记_续写块尾",
			content: "1  aaa\n来源\nsite-a\n",
			author:  "aaa",
			sources: []string{"pixiv"},
			want:    "1  aaa\n来源\nsite-a\npixiv\n",
			wantOK:  true,
		},
		{
			name:    "裸块_编号行后补来源标记",
			content: "1  aaa\n",
			author:  "aaa",
			sources: []string{"site-a"},
			want:    "1  aaa\n来源\nsite-a\n",
			wantOK:  true,
		},
		{
			name:    "有作品区无来源区_标记加行插在作品前",
			content: "1  aaa\n作品\na.png\n",
			author:  "aaa",
			sources: []string{"site-a"},
			want:    "1  aaa\n来源\nsite-a\n作品\na.png\n",
			wantOK:  true,
		},
		{
			name:    "作者不在任何块_found为假",
			content: "1  aaa\n作品\na.png\n",
			author:  "zzz",
			sources: []string{"site-a"},
			want:    "1  aaa\n作品\na.png\n",
			wantOK:  false,
		},
		{
			name:    "全部已存在_内容不动",
			content: "1  aaa\n来源\nsite-a\n",
			author:  "aaa",
			sources: []string{"site-a"},
			want:    "1  aaa\n来源\nsite-a\n",
			wantOK:  true,
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, found := AppendSources(c.content, c.author, c.sources)
			if found != c.wantOK {
				t.Fatalf("found=%v, want %v", found, c.wantOK)
			}
			if got != c.want {
				t.Errorf("内容不符:\ngot:\n%q\nwant:\n%q", got, c.want)
			}
			if !c.wantOK {
				return
			}
			// 往返锁定：Sources 解析回来恰好是并集且无重复。
			block := blockOf(t, got, c.author)
			seen := make(map[string]bool)
			for _, s := range block.Sources {
				if seen[s] {
					t.Errorf("Sources 出现重复行 %q", s)
				}
				seen[s] = true
			}
			for _, s := range c.sources {
				if !seen[s] {
					t.Errorf("解析回的 Sources 缺少新行 %q（got=%v）", s, block.Sources)
				}
			}
		})
	}
}

// 来源区夹在两个作品标记之间的病态布局：插裸行会被区域状态吞掉，必须
// 自带「来源」标记行（在首个「作品」标记前新开来源区，来源是集合语义，
// 行序无所谓）。
func TestAppendSourcesSandwichedSourcesRegion(t *testing.T) {
	content := "1  aaa\n作品\nx.png\n来源\nsite-a\n作品\ny.png\n"
	got, found := AppendSources(content, "aaa", []string{"site-f"})
	if !found {
		t.Fatal("found=false, want true")
	}
	block := blockOf(t, got, "aaa")
	if !reflect.DeepEqual(block.Sources, []string{"site-f", "site-a"}) {
		t.Errorf("Sources=%v, want [site-f site-a]\n内容:\n%s", block.Sources, got)
	}
	if !reflect.DeepEqual(block.Works, []string{"x.png", "y.png"}) {
		t.Errorf("Works=%v, want [x.png y.png]（既有作品行不得被破坏）", block.Works)
	}
}

// wantLines 期望值构造：解析器对空区产出 nil，比较域与之一致。
func wantLines(lines []string) []string {
	if len(lines) == 0 {
		return nil
	}
	return dedupLines(lines)
}

// ---------- AppendAuthorBlock ----------

func TestAppendAuthorBlock(t *testing.T) {
	cases := []struct {
		name    string
		content string
		names   []string
		sources []string
		works   []string
		want    string
	}{
		{
			name:    "空内容_从编号1开始_来源作品齐全",
			content: "",
			names:   []string{"NewAuthor"},
			sources: []string{"site-a"},
			works:   []string{"a.png"},
			want:    "1  NewAuthor\n来源  site-a\n作品\na.png\n",
		},
		{
			name:    "已有块_编号顺延最大值",
			content: "1  aaa\n作品\na.png\n\n5  bbb\n作品\nb.png\n",
			names:   []string{"ccc"},
			works:   []string{"c.png"},
			want:    "1  aaa\n作品\na.png\n\n5  bbb\n作品\nb.png\n6  ccc\n作品\nc.png\n",
		},
		{
			name:    "只作品",
			content: "1  aaa\n",
			names:   []string{"bbb"},
			works:   []string{"b.png"},
			want:    "1  aaa\n2  bbb\n作品\nb.png\n",
		},
		{
			name:    "只来源",
			content: "1  aaa\n",
			names:   []string{"bbb"},
			sources: []string{"site-a"},
			want:    "1  aaa\n2  bbb\n来源  site-a\n",
		},
		{
			name:    "来源作品都无_整段省略",
			content: "1  aaa\n",
			names:   []string{"bbb"},
			want:    "1  aaa\n2  bbb\n",
		},
		{
			name:    "显示名数字开头不误伤",
			content: "",
			names:   []string{"3cat"},
			sources: []string{"site-a"},
			works:   []string{"x.png"},
			want:    "1  3cat\n来源  site-a\n作品\nx.png\n",
		},
		{
			name:    "无尾换行的既有内容_先补换行",
			content: "1  aaa\n作品\na.png",
			names:   []string{"bbb"},
			works:   []string{"b.png"},
			want:    "1  aaa\n作品\na.png\n2  bbb\n作品\nb.png\n",
		},
		{
			name:    "多别名两空格分隔_回读恒等",
			content: "",
			names:   []string{"Night", "Cry"},
			sources: []string{"site-a"},
			works:   []string{"a.png"},
			want:    "1  Night  Cry\n来源  site-a\n作品\na.png\n",
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := AppendAuthorBlock(c.content, c.names, c.sources, c.works)
			if got != c.want {
				t.Errorf("内容不符:\ngot:\n%q\nwant:\n%q", got, c.want)
			}
			// 往返锁定：新块被解析出正确的身份名/来源/作品。
			block := blockOf(t, got, GenerateAuthorID(c.names[0]))
			if want := strings.Join(c.names, " / "); block.DisplayName != want {
				t.Errorf("DisplayName=%q, want %q", block.DisplayName, want)
			}
			if want := wantLines(c.sources); !reflect.DeepEqual(block.Sources, want) {
				t.Errorf("Sources=%v, want %v", block.Sources, want)
			}
			if want := wantLines(c.works); !reflect.DeepEqual(block.Works, want) {
				t.Errorf("Works=%v, want %v", block.Works, want)
			}
		})
	}
}

// 身份归一防回归（阻断审查 1）：新建作者输入经 CanonicalAuthorNames 后写块，
// ParseAuthorBlocks 回读的 id 必须等于 GenerateAuthorID(names[0])——原始输入
// 直接生成 id 会与块回读分裂（"Night  Cry" 表内 night__cry vs 块 night；
// "bamhor[3D]" 表内 bamhor3d vs 块 bamhor）。
func TestAppendAuthorBlockCanonicalRoundtrip(t *testing.T) {
	for _, in := range []string{
		"Night  Cry",          // 双空格：splitAliases 拆成两别名
		"bamhor[3D]",          // 括号备注：stripAliasNote 截断
		"Night Cry",           // 单空格：别名内部字符，不拆
		"rioko凉凉子(备注)",        // 全角括号备注
		"  A  B  C  ",         // 首尾空白 + 三别名
		"bamhor [3D]  mmd(注)", // 备注+多别名混合
	} {
		names := CanonicalAuthorNames(in)
		if len(names) == 0 {
			t.Fatalf("CanonicalAuthorNames(%q) 不应为空", in)
		}
		wantID := GenerateAuthorID(names[0])
		got := AppendAuthorBlock("1  别人\n作品\nx.png\n", names, []string{"site-a"}, []string{"a.png"})
		block := blockOf(t, got, wantID)
		if !reflect.DeepEqual(block.AuthorNames, names) {
			t.Errorf("输入 %q：回读 AuthorNames=%v, want %v\n内容:\n%s", in, block.AuthorNames, names, got)
		}
		if GenerateAuthorID(block.AuthorNames[0]) != wantID {
			t.Errorf("输入 %q：回读 id=%q, want %q（身份分裂）", in, GenerateAuthorID(block.AuthorNames[0]), wantID)
		}
	}
}

// CanonicalAuthorNames 与解析器对编号行的处理同构（splitAliases +
// stripAliasNote），是身份判定的唯一合法入口。
func TestCanonicalAuthorNames(t *testing.T) {
	cases := []struct {
		in   string
		want []string
	}{
		{"Night  Cry", []string{"Night", "Cry"}},
		{"bamhor[3D]", []string{"bamhor"}},
		{"bamhor (3D)", []string{"bamhor"}},
		{"Night Cry", []string{"Night Cry"}},
		{"  紙飛行機(site-b)  mmd  ", []string{"紙飛行機", "mmd"}},
		{"rioko凉凉子（全角备注）", []string{"rioko凉凉子"}},
		{"[3D]", nil},     // 备注截断后为空：丢弃
		{"", nil},         // 空串
		{"   ", nil},      // 纯空白
		{"  \t \n ", nil}, // 分隔空白归零
	}
	for _, c := range cases {
		if got := CanonicalAuthorNames(c.in); !reflect.DeepEqual(got, c.want) {
			t.Errorf("CanonicalAuthorNames(%q)=%v, want %v", c.in, got, c.want)
		}
	}
}

// ---------- ValidNewAuthorName ----------

func TestValidNewAuthorName(t *testing.T) {
	cases := []struct {
		name  string
		in    string
		valid bool
	}{
		{"合法名", "Night Cry", true},
		{"数字开头合法名", "3cat", true},
		{"中日文合法名", "rioko凉凉子", true},
		{"带括号备注合法（canonical 后仍非空）", "bamhor[3D]", true},
		{"双空格合法（canonical 拆多别名）", "Night  Cry", true},
		{"媒体扩展名", "a.png", false},
		{"纯数字", "123", false},
		{"编号行形态", "12 abc", false},
		{"来源标记词", "来源", false},
		{"出处带平台名", "出处  site-a", false},
		{"作品标记词", "作品", false},
		{"空串", "", false},
		{"纯空白", "   ", false},
		// 控制字符一律拒绝（阻断审查 2：换行/回车可向 TXT 真相注入任意行）。
		{"含换行", "正常名\n2  幽灵作者", false},
		{"含回车", "名\r字", false},
		{"含制表符", "名\t字", false},
		{"含 DEL", "名\x7f字", false},
		{"行首换行", "\nNight", false},
		// maxLength 200（openapi authorName maxLength: 200 双写）。
		{"恰 200 字符", strings.Repeat("字", MaxNewAuthorNameRunes), true},
		{"201 字符超限", strings.Repeat("字", MaxNewAuthorNameRunes+1), false},
		{"ASCII 201 字符超限", strings.Repeat("a", MaxNewAuthorNameRunes+1), false},
		// canonical 化后为空：括号备注截掉全部内容。
		{"全括号备注", "[备注]", false},
		{"全括号备注带空白", "  (备注)  ", false},
	}
	for _, c := range cases {
		if got := ValidNewAuthorName(c.in); got != c.valid {
			t.Errorf("ValidNewAuthorName(%q)=%v, want %v", c.in, got, c.valid)
		}
	}
}

// ValidSourceWord：控制字符拒绝 + rune 上限（openapi source items
// maxLength: 500 双写）。
func TestValidSourceWord(t *testing.T) {
	cases := []struct {
		in    string
		valid bool
	}{
		{"site-a", true},
		{"新站点x", true},
		{"https://example.com/a?b=1", true},
		{"x\n作品\n恶意行.png", false}, // 审查 PoC 的行注入载荷
		{"x\ry", false},
		{"x\ty", false},
		{"x\x7fy", false},
		{strings.Repeat("来", MaxSourceWordRunes), true},
		{strings.Repeat("来", MaxSourceWordRunes+1), false},
	}
	for _, c := range cases {
		if got := ValidSourceWord(c.in); got != c.valid {
			t.Errorf("ValidSourceWord(%q)=%v, want %v", c.in, got, c.valid)
		}
	}
}

// ---------- MissingUploadEntries / MergeUploadEntries ----------

func TestMissingAndMergeUploadEntries(t *testing.T) {
	content := "1  aaa\n来源\nsite-a\n作品\na.png\nb.png\n"
	entries := []UploadEntry{
		{AuthorID: "aaa", DisplayName: "aaa", Works: []string{"a.png", "c.png"}, Sources: []string{"site-a"}},
		{AuthorID: "bbb", DisplayName: "bbb", Works: []string{"x.png"}},
	}

	// 全都在 → 该条目不出现；缺一行 → 只缺那行；整块没了 → 整条缺失。
	missing := MissingUploadEntries(content, entries)
	wantMissing := []UploadEntry{
		{AuthorID: "aaa", DisplayName: "aaa", Works: []string{"c.png"}},
		{AuthorID: "bbb", DisplayName: "bbb", Works: []string{"x.png"}},
	}
	if !reflect.DeepEqual(missing, wantMissing) {
		t.Fatalf("missing=%+v, want %+v", missing, wantMissing)
	}

	// 全都在 → 空。
	if got := MissingUploadEntries(content, []UploadEntry{{
		AuthorID: "aaa", DisplayName: "aaa", Works: []string{"a.png", "b.png"}, Sources: []string{"site-a"},
	}}); len(got) != 0 {
		t.Errorf("全覆盖 missing=%+v, want 空", got)
	}

	// 并回：既有块补缺行，缺失块开新块保 displayName。
	merged := MergeUploadEntries(content, missing)
	wantMerged := "1  aaa\n来源\nsite-a\n作品\na.png\nb.png\nc.png\n2  bbb\n作品\nx.png\n"
	if merged != wantMerged {
		t.Errorf("merged=\n%q\nwant:\n%q", merged, wantMerged)
	}
	if b := blockOf(t, merged, "bbb"); b.DisplayName != "bbb" {
		t.Errorf("新块 DisplayName=%q, want bbb", b.DisplayName)
	}

	// 闭环：并回后再算缺失 → 空（REQ 验收 #11 保留路径）。
	if got := MissingUploadEntries(merged, entries); len(got) != 0 {
		t.Errorf("闭环 missing=%+v, want 空", got)
	}

	// 二次并回幂等：内容不再变化。
	if again := MergeUploadEntries(merged, missing); again != merged {
		t.Errorf("二次并回改变内容:\nonce:\n%q\ntwice:\n%q", merged, again)
	}
}

// MergeUploadEntries 对「块缺失但只有来源行要并回」的条目也开新块承载。
func TestMergeUploadEntriesSourcesOnly(t *testing.T) {
	merged := MergeUploadEntries("", []UploadEntry{
		{AuthorID: "aaa", DisplayName: "aaa", Sources: []string{"site-a"}},
	})
	want := "1  aaa\n来源  site-a\n"
	if merged != want {
		t.Errorf("merged=%q, want %q", merged, want)
	}
	if b := blockOf(t, merged, "aaa"); !reflect.DeepEqual(b.Sources, []string{"site-a"}) {
		t.Errorf("Sources=%v, want [site-a]", b.Sources)
	}
}

// MergeUploadEntries 用 Names 重建块后的往返锁定（防回归，阻断审查 1）：
// 重建块的解析回读 id 必须等于 entry.AuthorID——Names 引入前用 DisplayName
// 重建，多别名显示名（"Night / Cry"）会被当单别名读回，id 漂移成
// night_cry ≠ night，重导入保护从此每次都并出新块（幂等破坏）。
func TestMergeUploadEntriesNamesRoundtrip(t *testing.T) {
	entries := []UploadEntry{{
		AuthorID:    "night",
		DisplayName: "Night / Cry",
		Names:       []string{"Night", "Cry"},
		Works:       []string{"a.png"},
		Sources:     []string{"site-a"},
	}}
	merged := MergeUploadEntries("", entries)
	block := blockOf(t, merged, "night")
	if !reflect.DeepEqual(block.AuthorNames, entries[0].Names) {
		t.Errorf("回读 AuthorNames=%v, want %v\n内容:\n%s", block.AuthorNames, entries[0].Names, merged)
	}
	if got := GenerateAuthorID(block.AuthorNames[0]); got != entries[0].AuthorID {
		t.Errorf("回读 id=%q, want %q（往返身份分裂）", got, entries[0].AuthorID)
	}
	if block.DisplayName != entries[0].DisplayName {
		t.Errorf("DisplayName=%q, want %q", block.DisplayName, entries[0].DisplayName)
	}
	// 闭环：重建后的内容对同一条目再算缺失 → 空（不会反复并块）。
	if got := MissingUploadEntries(merged, entries); len(got) != 0 {
		t.Errorf("重建后 missing=%+v, want 空", got)
	}

	// Names 为空的旧数据兜底：DisplayName 作单别名（单别名旧数据显示名
	// 不含 " / "，回读 id 不漂移；多别名旧数据的历史缺口见实现注释）。
	old := []UploadEntry{{AuthorID: "bamhor", DisplayName: "bamhor", Works: []string{"b.png"}}}
	mergedOld := MergeUploadEntries("", old)
	if b := blockOf(t, mergedOld, "bamhor"); GenerateAuthorID(b.AuthorNames[0]) != "bamhor" {
		t.Errorf("旧数据兜底回读 id=%q, want bamhor", GenerateAuthorID(b.AuthorNames[0]))
	}
}
