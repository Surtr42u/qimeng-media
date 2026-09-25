package authoring

// attach.go：上传挂靠的文本手术纯函数（REQ-上传指定作者与来源 §3.3）。
// 把作品行/来源行/新作者块按 GUIDE_AUTHOR 清单格式规范并入片段原文，写入
// 结果必须能被 ParseAuthorBlocks 原样读回（REQ §3.3 第 9 条「规范填充」——
// 不允许造出只有挂靠代码认识的私有格式）。
//
// 实现约束：逐行做手术，块边界与区域判定的口径与 ParseAuthorBlocks 完全
// 一致（复用同包私有函数与正则，parse.go 零改动）；全部无 IO。

import (
	"fmt"
	"strings"
	"unicode/utf8"
)

// UploadEntry 一位作者在一份片段里的上传写入条目（重导入保护的比对单元；
// Works/Sources 存写入 TXT 的原样行文本，存在性按内容判定）。
type UploadEntry struct {
	AuthorID    string `json:"authorId"`
	DisplayName string `json:"displayName"`
	// Names 是规范别名列表（CanonicalAuthorNames 产物，或挂靠既有块时该块
	// 解析出的 AuthorNames）：MergeUploadEntries 重建缺失块时的编号行来源，
	// 保证 ParseAuthorBlocks 回读的 id == AuthorID（往返恒等）。字段引入前
	// 落库的旧数据没有此字段（nil），MergeUploadEntries 以 DisplayName 单
	// 元素兜底；DisplayName 仅供展示，不参与身份判定。
	Names   []string `json:"names,omitempty"`
	Works   []string `json:"works"`
	Sources []string `json:"sources"`
}

// blockSpan 是逐行扫描定位出的一个作者块的落点与结构锚点（行下标基于
// strings.Split(content, "\n") 的结果）。
type blockSpan struct {
	id          string // GenerateAuthorID(首别名)
	number      int    // 编号行的数字（AppendAuthorBlock 编号顺延的基数）
	start       int    // 编号行下标
	end         int    // 下一块编号行下标或 len(lines)，不含
	worksMarker int    // 首个「作品」标记行下标，无则 -1（来源插入点锚）
	hasSources  bool   // 块内出现过来源/出处标记行
	endsInWorks bool   // 块尾行处于作品区（作品行可直接续写，否则须补标记）
	// sourcesAtWorksMarker 是首个「作品」标记行之前的来源区状态：决定来源行
	// 插到该标记前时要不要自带「来源」标记（「来源」区夹在两个「作品」标记
	// 之间的病态布局里，直接插裸行会落在区域外被解析器忽略）。
	sourcesAtWorksMarker bool
}

// scanBlockSpans 逐行扫描定位全部作者块。块开始判定与区域状态迁移和
// ParseAuthorBlocks 逐字一致：numberedLineRe 命中且别名非全部带媒体扩展名
// （allHaveMediaExt 的编号行在作品区是作品行不是块开始）；isSourcesMarker
// 优先于「作品」标记判定（解析器 switch 顺序）；块外标记行不计入任何块。
func scanBlockSpans(content string) []blockSpan {
	lines := strings.Split(content, "\n")
	var spans []blockSpan
	cur := -1          // 当前块在 spans 中的下标；-1 = 首个编号行之前（块外）
	inSources := false // 与解析器同构的区域状态（块开始时复位）
	inWorks := false
	for i, raw := range lines {
		line := strings.TrimSpace(raw)
		if line == "" {
			continue
		}
		if m := numberedLineRe.FindStringSubmatch(line); m != nil {
			aliases := splitAliases(strings.TrimSpace(m[1]))
			if allHaveMediaExt(aliases) {
				continue // 数字开头作品行：归属当前块作品区，不是块开始
			}
			if cur >= 0 {
				spans[cur].end = i
				spans[cur].endsInWorks = inWorks // 终结上一块：定格块尾区域状态
			}
			number := 0
			for _, ch := range line {
				if ch < '0' || ch > '9' {
					break
				}
				number = number*10 + int(ch-'0')
			}
			id := ""
			if len(aliases) > 0 {
				id = GenerateAuthorID(stripAliasNote(aliases[0]))
			}
			spans = append(spans, blockSpan{
				id: id, number: number, start: i, end: len(lines), worksMarker: -1,
			})
			cur = len(spans) - 1
			inSources, inWorks = false, false // 解析器：新块复位区域状态
			continue
		}
		if isAllDigits(line) {
			continue // 纯数字行：仅作分隔符
		}
		switch {
		case isSourcesMarker(line):
			// 解析器里此分支不受 cur 约束（块外也切状态）；但块开始会复位，
			// 块外效果不外溢进任何 span。
			inSources, inWorks = true, false
			if cur >= 0 {
				spans[cur].hasSources = true
			}
		case line == markerWorks:
			if cur >= 0 {
				if spans[cur].worksMarker < 0 {
					spans[cur].worksMarker = i
					spans[cur].sourcesAtWorksMarker = inSources
				}
				inSources, inWorks = false, true
			}
		}
	}
	if cur >= 0 {
		spans[cur].endsInWorks = inWorks
	}
	return spans
}

// findBlock 返回 authorID 命中的第一个块（跨块同名取首遇，与
// ParseAuthorBlocks 顺序一致）。
func findBlock(spans []blockSpan, authorID string) *blockSpan {
	for i := range spans {
		if spans[i].id == authorID {
			return &spans[i]
		}
	}
	return nil
}

// parseSpan 把块区域单独喂给正式解析器，得到该块解析后的 Works/Sources。
// 区域起点是块编号行、终点在下一块开始之前，状态机在其中恰好产出这一个块，
// 因此结果与全量解析中该块的 Works/Sources 完全一致（去重判定的唯一依据）。
func parseSpan(lines []string, span *blockSpan) AuthorBlock {
	sub := strings.Join(lines[span.start:span.end], "\n")
	blocks := ParseAuthorBlocks(sub)
	if len(blocks) == 0 {
		// 构造上不可达（区域首行即块编号行）；防御性返回空块，调用方
		// 按无既有行处理（最坏效果=多插一次标记行，幂等性不受破坏）。
		return AuthorBlock{}
	}
	return blocks[0]
}

// walkBackOverBlanks 把插入点回退到 min 之后最后一个非空行之后（空行被
// 解析器跳过，插在空行前更贴近手工清单形态；min 是不可越过的下界）。
func walkBackOverBlanks(lines []string, pos, min int) int {
	for pos > min && strings.TrimSpace(lines[pos-1]) == "" {
		pos--
	}
	return pos
}

// appendLines 把 newLines 并入 authorID 对应块的目标区域（works=true 作品区
// 否则来源区）：对块内既有行与输入内部逐行去重后插入。found=false=作者不在
// 任何块中（content 原样返回，调用方走新建块路径）。
func appendLines(content, authorID string, newLines []string, works bool) (string, bool) {
	lines := strings.Split(content, "\n")
	span := findBlock(scanBlockSpans(content), authorID)
	if span == nil {
		return content, false
	}

	// 去重：比对基准 = 该块经正式解析器读出的既有 Works/Sources（字符串
	// 全等才跳过——REQ §3.3 第 4 条幂等）。
	parsed := parseSpan(lines, span)
	existing := parsed.Works
	if !works {
		existing = parsed.Sources
	}
	seen := make(map[string]bool, len(existing)+len(newLines))
	for _, l := range existing {
		seen[l] = true
	}
	var fresh []string
	for _, l := range newLines {
		l = strings.TrimSpace(l)
		if l == "" || seen[l] {
			continue
		}
		seen[l] = true
		fresh = append(fresh, l)
	}
	if len(fresh) == 0 {
		return content, true // 全部已存在：不动原文（幂等）
	}

	insert, pos := buildInsert(lines, span, fresh, works)
	out := make([]string, 0, len(lines)+len(insert))
	out = append(out, lines[:pos]...)
	out = append(out, insert...)
	out = append(out, lines[pos:]...)
	return strings.Join(out, "\n"), true
}

// buildInsert 计算待插入行（含必要的区域标记行）与插入下标。
// 作品行插在块内容末尾，仅当块尾已处于作品区才直接续写，否则先补「作品」
// 标记（没有标记时块尾的裸行不会被解析进 Works——包括「来源」标记行把
// 块尾切成来源区的形态，必须补标记重开作品区）；来源行插在「作品」标记行
// 之前（保持来源区在作品区前的既有形态），无作品标记时插在块末尾，块连
// 来源区都没有时插在编号行之后并补「来源」标记行（REQ §3.3 第 9 条）。
func buildInsert(lines []string, span *blockSpan, fresh []string, works bool) ([]string, int) {
	contentEnd := walkBackOverBlanks(lines, span.end, span.start+1)
	if works {
		if span.endsInWorks {
			return fresh, contentEnd
		}
		return append([]string{markerWorks}, fresh...), contentEnd
	}
	if span.worksMarker >= 0 {
		pos := walkBackOverBlanks(lines, span.worksMarker, span.start+1)
		return withSourcesMarker(span.sourcesAtWorksMarker, fresh), pos
	}
	if span.hasSources {
		return fresh, contentEnd // 来源区延伸到块尾（无作品标记截断）
	}
	// 裸块：编号行之后补「来源」标记行再插来源行。
	return append([]string{markerSources}, fresh...), span.start + 1
}

// withSourcesMarker 在块没有来源区时补「来源」标记行（新来源行本身是普通
// 行，不写成「来源  x」形态——与既有来源区的行形态保持一致）。
func withSourcesMarker(hasSources bool, fresh []string) []string {
	if hasSources {
		return fresh
	}
	return append([]string{markerSources}, fresh...)
}

// AppendWorks 把作品行并入 content 中 authorID 对应作者块的作品区（逐行
// 去重；块无作品区时补「作品」标记行）。返回新 content；found=false=该
// 作者不在任何块中（调用方走新建块路径）。
func AppendWorks(content, authorID string, works []string) (string, bool) {
	return appendLines(content, authorID, works, true)
}

// AppendSources 把来源行并入来源区（逐行去重；块无来源区时在编号行后补
// 「来源」标记行 + 来源行）。found 语义同上。
func AppendSources(content, authorID string, sources []string) (string, bool) {
	return appendLines(content, authorID, sources, false)
}

// blockLineFormat 是新块行格式：编号行 `数字+两空格+别名列表（两空格
// 分隔）`、来源行 `来源+两空格+平台名`（与别名分隔规范同为两空格，B2
// 格式同构）。
const (
	numberedTwoSpaces = "  "
	sourceLineFormat  = markerSources + numberedTwoSpaces + "%s"
	numberedFormat    = "%d" + numberedTwoSpaces + "%s"
)

// AppendAuthorBlock 在 content 末尾追加新作者块：编号=现有最大编号+1（无块
// 则 1）；编号行写 `编号  别名1  别名2`（两空格分隔）+ 来源行 + 「作品」
// 标记 + 作品行，与手工导入片段同构（REQ §3.3 第 9 条：写出的内容必须能被
// ParseAuthorBlocks 原样读回）。names 须是 CanonicalAuthorNames 产物（单个
// 别名内无双空格序列、无括号备注），以两空格 join 写入后解析回读恒等——
// 直接写原始输入/DisplayName（含双空格或括号备注）会让回读 id 与
// GenerateAuthorID 分裂（阻断审查判定的身份分歧缺陷）。sources/works 为空
// 时对应区整段省略。
func AppendAuthorBlock(content string, names []string, sources, works []string) string {
	maxNumber := 0
	for _, s := range scanBlockSpans(content) {
		if s.number > maxNumber {
			maxNumber = s.number
		}
	}

	var b strings.Builder
	base := strings.TrimRight(content, "\n")
	if base != "" {
		b.WriteString(base)
		b.WriteByte('\n')
	}
	fmt.Fprintf(&b, numberedFormat, maxNumber+1, strings.Join(names, numberedTwoSpaces))
	for _, s := range dedupLines(sources) {
		fmt.Fprintf(&b, "\n"+sourceLineFormat, s)
	}
	if works := dedupLines(works); len(works) > 0 {
		b.WriteString("\n" + markerWorks)
		for _, w := range works {
			b.WriteString("\n" + w)
		}
	}
	b.WriteByte('\n')
	return b.String()
}

// dedupLines trim + 去空 + 去重（保序，首遇保留）——新块内不写重复行。
func dedupLines(lines []string) []string {
	seen := make(map[string]bool, len(lines))
	out := make([]string, 0, len(lines))
	for _, l := range lines {
		l = strings.TrimSpace(l)
		if l == "" || seen[l] {
			continue
		}
		seen[l] = true
		out = append(out, l)
	}
	return out
}

// MaxNewAuthorNameRunes 是新建作者名（trim 后）的 rune 数上限。与
// api/openapi.yaml PostApiV1AssetsUploadParams 的 authorName 参数
// （maxLength: 200）双写同步：协议侧改动须同步这里，反之亦然。
const MaxNewAuthorNameRunes = 200

// CanonicalAuthorNames 把新建作者输入规范化为解析器等价的别名列表：
// 与 ParseAuthorBlocks 对编号行的处理完全同构（splitAliases + stripAliasNote），
// 身份判定（GenerateAuthorID）必须用 names[0]，禁止用原始输入——原始输入
// 直接生成 id 会与块解析回读的 id 分裂（"Night  Cry"/"bamhor[3D]"），
// REQ §3.1① 空格/符号差异不得裂分身。别名内保证无双空格序列与括号备注，
// 以两空格 join 写回编号行可被解析器恒等读回（往返安全）。
func CanonicalAuthorNames(input string) []string {
	aliases := splitAliases(strings.TrimSpace(input))
	var out []string
	for _, a := range aliases {
		// 括号备注整体截掉后为空的别名（如 "[3D]"）丢弃：保留会让编号行
		// 出现空别名，回读块形态不可控。
		if a = stripAliasNote(a); a != "" {
			out = append(out, a)
		}
	}
	return out
}

// hasControlChars 报告是否含控制字符（rune < 0x20 或 0x7F）——authorName
// 与 source 含换行/回车可向 TXT 真相注入任意行（审查 PoC 证实），入口
// 一律拒绝。
func hasControlChars(s string) bool {
	for _, r := range s {
		if r < 0x20 || r == 0x7F {
			return true
		}
	}
	return false
}

// ValidNewAuthorName 校验新建作者显示名：trim 非空、原始串不含控制字符
// （防行注入）、长度不超 MaxNewAuthorNameRunes、无媒体扩展名（复用
// hasMediaExt）、非纯数字、非编号行形态（numberedLineRe）、非「来源/出处/
// 作品」标记行、canonical 化后仍有有效别名。全部通过才允许作为 authorName
// 落块（防把文件名/序号/标记行写成作者名的输入事故）。
func ValidNewAuthorName(name string) bool {
	if hasControlChars(name) {
		return false
	}
	name = strings.TrimSpace(name)
	if name == "" || hasMediaExt(name) || isAllDigits(name) {
		return false
	}
	if utf8.RuneCountInString(name) > MaxNewAuthorNameRunes {
		return false
	}
	if numberedLineRe.MatchString(name) {
		return false
	}
	if isSourcesMarker(name) || name == markerWorks {
		return false
	}
	return len(CanonicalAuthorNames(name)) > 0
}

// MaxSourceWordRunes 是单个来源词的 rune 数上限。与 api/openapi.yaml
// SourceVocabulary 的 sources 参数（items maxLength: 500）双写同步：协议侧
// 改动须同步这里，反之亦然（maxItems 32 的项数上限在 httpapi
// normalizeSourceWords 侧执行）。
const MaxSourceWordRunes = 500

// ValidSourceWord 校验来源词（编辑端点与通用来源词表共用）：不含控制字符
// （换行/回车可向 TXT 真相注入任意行，与 ValidNewAuthorName 同一红线）且
// 长度不超 MaxSourceWordRunes。
func ValidSourceWord(s string) bool {
	return !hasControlChars(s) && utf8.RuneCountInString(s) <= MaxSourceWordRunes
}

// MissingUploadEntries 计算 entries 未被 content 覆盖的部分：作者块缺失→
// 整条缺失（保留 displayName 与 Names）；块存在→只保留缺失的作品行/来源行
// （Names 原样透传，块缺失时被冲掉的场景才需要它重建）；全都在→该条目不出
// 现在结果中。
func MissingUploadEntries(content string, entries []UploadEntry) []UploadEntry {
	byID := make(map[string]*AuthorBlock)
	for _, block := range ParseAuthorBlocks(content) {
		if len(block.AuthorNames) == 0 {
			continue
		}
		id := GenerateAuthorID(block.AuthorNames[0])
		if _, ok := byID[id]; !ok {
			byID[id] = &block
		}
	}

	var missing []UploadEntry
	for _, e := range entries {
		block, ok := byID[e.AuthorID]
		if !ok {
			missing = append(missing, e)
			continue
		}
		m := UploadEntry{AuthorID: e.AuthorID, DisplayName: e.DisplayName, Names: e.Names}
		for _, w := range e.Works {
			if !containsLine(block.Works, w) {
				m.Works = append(m.Works, w)
			}
		}
		for _, s := range e.Sources {
			if !containsLine(block.Sources, s) {
				m.Sources = append(m.Sources, s)
			}
		}
		if len(m.Works) > 0 || len(m.Sources) > 0 {
			missing = append(missing, m)
		}
	}
	return missing
}

// containsLine 按字符串全等查行（解析产物已 TrimSpace，查询侧同样 trim
// 口径对齐）。
func containsLine(lines []string, target string) bool {
	target = strings.TrimSpace(target)
	for _, l := range lines {
		if l == target {
			return true
		}
	}
	return false
}

// MergeUploadEntries 把缺失条目并回 content（块存在→AppendWorks/
// AppendSources；块缺失→AppendAuthorBlock，编号行别名取 e.Names）。与
// MissingUploadEntries 配对使用（重导入保护 resolution=keep 路径）。
func MergeUploadEntries(content string, entries []UploadEntry) string {
	for _, e := range entries {
		merged, found := AppendWorks(content, e.AuthorID, e.Works)
		if !found {
			names := e.Names
			if len(names) == 0 {
				// Names 为空的旧数据兜底：Names 字段引入前落库的条目只有
				// DisplayName（本就是别名的 " / " 连接），作单别名使用——
				// 含多别名的旧显示名重建后 id 可能漂移，属旧数据的历史
				// 缺口，新条目不再产生（写入侧已统一记录 canonical Names）。
				names = []string{e.DisplayName}
			}
			content = AppendAuthorBlock(content, names, e.Sources, e.Works)
			continue
		}
		content = merged
		if withSources, found := AppendSources(content, e.AuthorID, e.Sources); found {
			content = withSources
		}
	}
	return content
}
