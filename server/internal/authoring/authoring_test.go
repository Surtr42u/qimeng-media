package authoring

// TXT 解析与匹配测试：断言语义逐条照译自旧项目
// QimengMedia/app/src/test/java/com/qimeng/media/AuthorImportUseCaseTest.kt
// （20 个测试中 13 个与本包对应；7 个分片存储测试随「分片存储不实现」的
// 语义决策豁免，见 doc.go 与本包 CHANGELOG 说明）。旧测试文件名后缀保留
// 在 Go 测试名中，便于两侧对照审计。
//
// 注意：Kotlin trimIndent 的文本在 Go 中以等价无缩进形式书写，语义不变。

import (
	"reflect"
	"testing"
)

func mustBlocks(t *testing.T, text string) []AuthorBlock {
	t.Helper()
	return ParseAuthorBlocks(text)
}

func blocksNames(b AuthorBlock) []string { return b.AuthorNames }
func blocksWorks(b AuthorBlock) []string { return b.Works }

// ---------- 基础块解析 ----------

// 照译 parseAuthorBlocks_formatB2_singleBlockParsed：格式 B2（编号+单作者+出处+作品）。
func TestParseAuthorBlocksFormatB2SingleBlockParsed(t *testing.T) {
	text := "1  that_maskey\n" +
		"出处  kemono\n" +
		"作品\n" +
		"尼尔 机械纪元  2B 1 (1).png\n" +
		"守望先锋  天使 16 (1).png\n"

	blocks := mustBlocks(t, text)
	if len(blocks) != 1 {
		t.Fatalf("blocks=%d, want 1", len(blocks))
	}
	if want := []string{"that_maskey"}; !reflect.DeepEqual(blocksNames(blocks[0]), want) {
		t.Errorf("authorNames=%v, want %v", blocksNames(blocks[0]), want)
	}
	want := []string{"尼尔 机械纪元  2B 1 (1).png", "守望先锋  天使 16 (1).png"}
	if !reflect.DeepEqual(blocksWorks(blocks[0]), want) {
		t.Errorf("works=%v, want %v", blocksWorks(blocks[0]), want)
	}
}

// 照译 parseAuthorBlocks_formatB_multiBlockParsed：同一 TXT 多作者块，用编号行分隔。
func TestParseAuthorBlocksFormatBMultiBlockParsed(t *testing.T) {
	text := "1  bamhor  bamh3d\n" +
		"来源\n" +
		"`https://example.site`\n" +
		"作品\n" +
		"英雄联盟  阿狸 1 (1).mp4\n" +
		"\n" +
		"2  Nagoonimation\n" +
		"作品\n" +
		"守望先锋 朱诺 1 (1).mp4\n"

	blocks := mustBlocks(t, text)
	if len(blocks) != 2 {
		t.Fatalf("blocks=%d, want 2", len(blocks))
	}
	if want := []string{"bamhor", "bamh3d"}; !reflect.DeepEqual(blocksNames(blocks[0]), want) {
		t.Errorf("blocks[0].authorNames=%v, want %v", blocksNames(blocks[0]), want)
	}
	if want := []string{"Nagoonimation"}; !reflect.DeepEqual(blocksNames(blocks[1]), want) {
		t.Errorf("blocks[1].authorNames=%v, want %v", blocksNames(blocks[1]), want)
	}
}

// 照译 parseAuthorBlocks_parenthesizedAlias_stripsSuffix：括号备注自动去除。
func TestParseAuthorBlocksParenthesizedAliasStripsSuffix(t *testing.T) {
	text := "1  kamihikoki_mmd  紙飛行機(小红车资源出处)\n" +
		"作品\n" +
		"a.mp4\n"

	blocks := mustBlocks(t, text)
	if len(blocks) != 1 {
		t.Fatalf("blocks=%d, want 1", len(blocks))
	}
	want := []string{"kamihikoki_mmd", "紙飛行機"}
	if !reflect.DeepEqual(blocksNames(blocks[0]), want) {
		t.Errorf("authorNames=%v, want %v", blocksNames(blocks[0]), want)
	}
}

// ---------- 数字开头文件名误判编号行（旧项目 v1.12/v1.13 修复） ----------

// 照译 parseAuthorBlocks_digitLeadingFileNameInWorks_keptAsWork。
func TestParseAuthorBlocksDigitLeadingFileNameInWorksKeptAsWork(t *testing.T) {
	text := "6  Takerskiy\n" +
		"出处  kemono\n" +
		"作品\n" +
		"2077  帕南.png\n" +
		"守望先锋  雾子 7 (1).jpg\n"

	blocks := mustBlocks(t, text)
	if len(blocks) != 1 {
		t.Fatalf("blocks=%d, want 1", len(blocks))
	}
	if want := []string{"Takerskiy"}; !reflect.DeepEqual(blocksNames(blocks[0]), want) {
		t.Errorf("authorNames=%v, want %v", blocksNames(blocks[0]), want)
	}
	want := []string{"2077  帕南.png", "守望先锋  雾子 7 (1).jpg"}
	if !reflect.DeepEqual(blocksWorks(blocks[0]), want) {
		t.Errorf("works=%v, want %v", blocksWorks(blocks[0]), want)
	}
}

// 照译 parseAuthorBlocks_digitLeadingFileNameOutsideWorks_ignored。
func TestParseAuthorBlocksDigitLeadingFileNameOutsideWorksIgnored(t *testing.T) {
	text := "2077  朱迪+帕南.png\n" +
		"\n" +
		"1  Keu3D\n" +
		"出处  kemono\n" +
		"作品\n" +
		"最终幻想  蒂法+爱丽丝 1 (1).png\n"

	blocks := mustBlocks(t, text)
	if len(blocks) != 1 {
		t.Fatalf("blocks=%d, want 1（数字开头文件名不得创建伪作者块）", len(blocks))
	}
	if want := []string{"Keu3D"}; !reflect.DeepEqual(blocksNames(blocks[0]), want) {
		t.Errorf("authorNames=%v, want %v", blocksNames(blocks[0]), want)
	}
	want := []string{"最终幻想  蒂法+爱丽丝 1 (1).png"}
	if !reflect.DeepEqual(blocksWorks(blocks[0]), want) {
		t.Errorf("works=%v, want %v", blocksWorks(blocks[0]), want)
	}
}

// 照译 parseAuthorBlocks_multipleDigitLeadingWorks_allKept。
func TestParseAuthorBlocksMultipleDigitLeadingWorksAllKept(t *testing.T) {
	text := "28  Night Cry\n" +
		"作品\n" +
		"生化危机  格蕾丝 2.jpg\n" +
		"2077  帕南 2.jpg\n" +
		"守望先锋  雾子 1.jpg\n"

	blocks := mustBlocks(t, text)
	if len(blocks) != 1 {
		t.Fatalf("blocks=%d, want 1", len(blocks))
	}
	want := []string{"生化危机  格蕾丝 2.jpg", "2077  帕南 2.jpg", "守望先锋  雾子 1.jpg"}
	if !reflect.DeepEqual(blocksWorks(blocks[0]), want) {
		t.Errorf("works=%v, want %v", blocksWorks(blocks[0]), want)
	}
}

// ---------- 边界情况 ----------

// 照译 parseAuthorBlocks_plainAuthorList_returnsEmpty：格式 C 由调用方走
// ParsePlainAuthorNames 分支。
func TestParseAuthorBlocksPlainAuthorListReturnsEmpty(t *testing.T) {
	text := "kamihikoki_mmd\n紙飛行機\nbamhor\n"
	if blocks := mustBlocks(t, text); len(blocks) != 0 {
		t.Errorf("blocks=%d, want 0（格式 C 不是块格式）", len(blocks))
	}
}

// 照译 parseAuthorBlocks_emptyText_returnsEmpty。
func TestParseAuthorBlocksEmptyTextReturnsEmpty(t *testing.T) {
	if blocks := mustBlocks(t, ""); len(blocks) != 0 {
		t.Errorf("空文本 blocks=%d, want 0", len(blocks))
	}
	if blocks := mustBlocks(t, "   \n  \n "); len(blocks) != 0 {
		t.Errorf("纯空白文本 blocks=%d, want 0", len(blocks))
	}
}

// 照译 parseAuthorBlocks_numberOnlyLine_notTreatedAsHeader。
func TestParseAuthorBlocksNumberOnlyLineNotTreatedAsHeader(t *testing.T) {
	text := "1  aaa\n" +
		"作品\n" +
		"a.png\n" +
		"\n" +
		"16\n" +
		"\n" +
		"2  bbb\n" +
		"作品\n" +
		"b.png\n"

	blocks := mustBlocks(t, text)
	if len(blocks) != 2 {
		t.Fatalf("blocks=%d, want 2（纯数字行仅作分隔符）", len(blocks))
	}
	if want := []string{"aaa"}; !reflect.DeepEqual(blocksNames(blocks[0]), want) {
		t.Errorf("blocks[0].authorNames=%v, want %v", blocksNames(blocks[0]), want)
	}
	if want := []string{"bbb"}; !reflect.DeepEqual(blocksNames(blocks[1]), want) {
		t.Errorf("blocks[1].authorNames=%v, want %v", blocksNames(blocks[1]), want)
	}
}

// ---------- findMatchingMediaLight 扩展名判定口径（四例照译） ----------

func mkFile(name string) MediaFile { return MediaFile{AssetID: "rk_" + name, FileName: name} }

// 照译 findMatchingMediaLight_workWithNonIndexedMediaExt_doesNotMatchBaseNameFile：
// wmv 等媒体非索引扩展名必须计入 hasExt；若误收窄，"X.wmv" 会整体按基础名
// 匹配错误命中 "X.wmv (1).jpg"。
func TestMatchWorksWorkWithNonIndexedMediaExtDoesNotMatchBaseNameFile(t *testing.T) {
	got := MatchWorks("X.wmv", []MediaFile{mkFile("X.wmv (1).jpg")})
	if len(got) != 0 {
		t.Errorf("got=%v, want 空（wmv 必须按带扩展名处理）", got)
	}
}

// 照译 findMatchingMediaLight_workWithDocExt_treatedAsNoExtension：
// txt/doc/pdf 不计入 hasExt："X.txt" 按无扩展名走规则 2。
func TestMatchWorksWorkWithDocExtTreatedAsNoExtension(t *testing.T) {
	got := MatchWorks("X.txt", []MediaFile{mkFile("X.txt (1).jpg")})
	want := []string{"X.txt (1).jpg"}
	if len(got) != 1 || got[0].FileName != want[0] {
		t.Errorf("got=%v, want %v", got, want)
	}
}

// 照译 findMatchingMediaLight_workWithIndexedExt_exactMatchRequiresSameExtension：
// 有扩展名时规则 1 精确匹配且扩展名一致；不做序号括号匹配。
// X.png 为 2026-09-05 补强：原数据缺「同名不同扩展名」文件，没锁住扩展名
// 检查，翻译版据此漏了该检查（见 TestMatchWorksExtCheckRealCase Kiriko 回归）。
func TestMatchWorksWorkWithIndexedExtExactMatchRequiresSameExtension(t *testing.T) {
	got := MatchWorks("X.mp4", []MediaFile{
		mkFile("X.mp4"), mkFile("X.mp4 (1).mp4"), mkFile("X.mp4 (1).jpg"),
		mkFile("X.png"),
	})
	want := []string{"X.mp4"}
	if len(got) != 1 || got[0].FileName != want[0] {
		t.Errorf("got=%v, want %v", got, want)
	}
}

// 回归（2026-09-05 用户实机）：图集作者 TXT 写「守望先锋  雾子 3.png」、
// 视频作者 TXT 写「守望先锋  雾子 3.mp4」，同名同基础名不同扩展名必须
// 各归各——翻译版丢失旧算法的扩展名检查（mediaExt == ext），两个作者把
// png+mp4 互相污染关联。扩展名检查必须区分大小写无关地比对（旧 equals
// ignoreCase），且非媒体扩展名（X.txt 场景）不参与。
func TestMatchWorksExtCheckRealCaseKiriko(t *testing.T) {
	files := []MediaFile{
		mkFile("守望先锋  雾子 3.png"),
		mkFile("守望先锋  雾子 3.mp4"),
	}
	if got := MatchWorks("守望先锋  雾子 3.mp4", files); len(got) != 1 || got[0].FileName != "守望先锋  雾子 3.mp4" {
		t.Errorf("视频作者作品命中=%v, want 仅 .mp4", got)
	}
	if got := MatchWorks("守望先锋  雾子 3.png", files); len(got) != 1 || got[0].FileName != "守望先锋  雾子 3.png" {
		t.Errorf("图集作者作品命中=%v, want 仅 .png", got)
	}
	// 大小写不敏感（旧算法 ignoreCase）：扩展名与基础名大小写差异不挡匹配
	if got := MatchWorks("X.MP4", []MediaFile{mkFile("x.mp4")}); len(got) != 1 {
		t.Errorf("got=%v, want 大小写差异仍命中", got)
	}
}

// 照译 findMatchingMediaLight_workWithoutExt_matchesExactAndSequenceFiles：
// 无扩展名：精确同名与「基础名+序号括号」文件均命中，精确在前。
func TestMatchWorksWorkWithoutExtMatchesExactAndSequenceFiles(t *testing.T) {
	got := MatchWorks("守望先锋  朱诺 6", []MediaFile{
		mkFile("守望先锋  朱诺 6.jpg"), mkFile("守望先锋  朱诺 6 (1).jpg"),
		mkFile("守望先锋  朱诺 6 卡芙卡.jpg"),
	})
	want := []string{"守望先锋  朱诺 6.jpg", "守望先锋  朱诺 6 (1).jpg"}
	if len(got) != len(want) {
		t.Fatalf("got=%v, want %v", got, want)
	}
	for i, w := range want {
		if got[i].FileName != w {
			t.Errorf("got[%d]=%s, want %s", i, got[i].FileName, w)
		}
	}
}

// ---------- authorId 生成（GUIDE_DATA「COS 数据规则」示例） ----------

func TestGenerateAuthorID(t *testing.T) {
	cases := []struct{ in, want string }{
		{"rioko凉凉子", "rioko凉凉子"},             // 中日文保留，字母小写
		{"水淼Aqua", "水淼aqua"},                 // GUIDE_DATA 示例：ASCII 小写化
		{"kamihikoki_mmd", "kamihikoki_mmd"}, // 下划线保留
		{"Night Cry", "night_cry"},           // 空格转下划线
		{"bamhor[3D]", "bamhor3d"},           // 特殊符号删除、字母数字保留（括号备注去除发生在解析层，不在本函数）
		{"纸舞 - 精选", "纸舞__精选"},                // 空格逐字符转下划线（不合并连续下划线）、符号删除
	}
	for _, c := range cases {
		if got := GenerateAuthorID(c.in); got != c.want {
			t.Errorf("GenerateAuthorID(%q)=%q, want %q", c.in, got, c.want)
		}
	}
	// 清洗后为空：哈希兜底（author_ + 8 位 hex）。
	id := GenerateAuthorID("!!!")
	if len(id) != len("author_")+8 || id[:7] != "author_" {
		t.Errorf("空清洗兜底 id=%q, want author_+8hex", id)
	}
	// 同输入同输出（确定性）。
	if GenerateAuthorID("!!!") != id {
		t.Error("哈希兜底不具确定性")
	}
	if got := GenerateCosAuthorID("rioko凉凉子"); got != "cos_rioko凉凉子" {
		t.Errorf("GenerateCosAuthorID=%q, want cos_ 前缀", got)
	}
}
