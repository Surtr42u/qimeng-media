package authoring

import (
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"
)

// AuthorBlock 是格式 A/B/B2 解析出的一个作者块。
type AuthorBlock struct {
	// AuthorNames 是括号备注去除后的别名列表（有序）；首个别名是主要名称，
	// 用于生成 authorId（GUIDE_AUTHOR 格式规范）。
	AuthorNames []string
	// DisplayName 是 ` / ` 连接的显示名（如 `kamihikoki_mmd / 紙飛行機`）。
	DisplayName string
	// Sources 是来源/出处区的行（URL/平台名），仅记录不参与匹配
	// （GUIDE_AUTHOR：作者不管来源网址，这里保留原文供存储层取舍）。
	Sources []string
	// Works 是作品区逐行的作品文件名（只需文件名不需路径）。
	Works []string
}

// 来源/出处标记词（两者等价，GUIDE_AUTHOR 格式规范）；标记行可同行带平台名
// （`出处  kemono`）。
const (
	markerSources = "来源"
	markerWorks   = "作品"
)

// numberedLineRe 匹配编号行形态 `数字 内容`（数字与内容间有空白）；
// 纯数字行不匹配（测试锁定：仅作分隔符忽略，不创建块、不误加作品）。
// 正则字面量是 GUIDE_AUTHOR 逐字规则的一部分，禁止改动。
var numberedLineRe = regexp.MustCompile(`^\d+\s+(.+)$`)

// aliasSeparatorRe 是编号行内别名的分隔：两个或以上空白（GUIDE_AUTHOR
// 格式规范；单空格是别名内部字符，如 `Night Cry`）。
var aliasSeparatorRe = regexp.MustCompile(`\s{2,}`)

// workSuffixRe 匹配序号括号 `(N)`：规则 2 的触发条件之一（作品名自身不含
// 序号括号）与文件名尾部的剥离目标。
var (
	workSuffixRe     = regexp.MustCompile(`\(\d+\)`)
	fileSuffixTailRe = regexp.MustCompile(`\(\d+\)$`)
)

// ParseAuthorBlocks 解析格式 A/B/B2（编号行 + 来源/出处 + 作品区）。
// 三种格式自动识别由状态机天然完成（A=编号+多别名+来源；B=多作者块；
// B2=出处关键词同行）。不是块格式的文本（格式 C 纯作者名列表）返回空，
// 由调用方转走 ParsePlainAuthorNames——与旧项目 parseAuthorBlocks 返回空
// 即走简单行分支的语义一致。
//
// 逐行规则（GUIDE_AUTHOR 格式规范，行为由旧项目测试断言锁定）：
//   - 空行跳过；换行 LF 与 CRLF 均支持（行尾 \r 经 TrimSpace 归一）。
//   - 编号行 `数字  别名1  别名2 ...`：开启新块；别名按两个以上空白分隔，
//     括号备注自动去除；纯数字行（无内容）忽略。
//   - 数字开头作品名的误判修复（旧项目 v1.13）：编号行解析出的别名全部带
//     媒体扩展名时按作品行处理——处于作品区归入当前块作品，块外忽略。
//     扩展名判定在括号备注去除之前进行（备注去除是作者名语义，若先去除
//     `帕南(1).png` 会丢扩展名而误判为别名）。
//   - `来源`/`出处` 标记行进入来源区（同行剩余部分是平台名）；`作品`
//     标记行（单独一行）进入作品区；两个区域各行分别归入当前块的
//     Sources / Works，无当前块时忽略。
func ParseAuthorBlocks(text string) []AuthorBlock {
	var blocks []AuthorBlock
	var cur *AuthorBlock
	inSources := false
	inWorks := false

	for _, raw := range strings.Split(text, "\n") {
		line := strings.TrimSpace(raw)
		if line == "" {
			continue
		}

		// 编号行形态：数字 + 空白 + 内容。
		if m := numberedLineRe.FindStringSubmatch(line); m != nil {
			rest := strings.TrimSpace(m[1])
			aliases := splitAliases(rest)
			// 数字开头文件名误判修复：全部别名带媒体扩展名 → 按作品行处理。
			if allHaveMediaExt(aliases) {
				if inWorks && cur != nil {
					cur.Works = append(cur.Works, line)
				}
				continue // 块外（无归属作者）：忽略，不创建伪作者块
			}
			blocks = append(blocks, AuthorBlock{})
			cur = &blocks[len(blocks)-1]
			for _, a := range aliases {
				cur.AuthorNames = append(cur.AuthorNames, stripAliasNote(a))
			}
			cur.DisplayName = strings.Join(cur.AuthorNames, " / ")
			inSources = false
			inWorks = false
			continue
		}

		// 纯数字行：不满足 `数字 内容` 编号行格式，仅作分隔符忽略
		//（不创建块、不误加作品；下一编号行正常开启新块）。
		if isAllDigits(line) {
			continue
		}

		switch {
		case isSourcesMarker(line):
			inSources, inWorks = true, false
			if cur != nil {
				if platform := platformName(line); platform != "" {
					cur.Sources = append(cur.Sources, platform)
				}
			}
		case line == markerWorks:
			if cur != nil {
				inSources, inWorks = false, true
			}
		case inSources:
			if cur != nil {
				cur.Sources = append(cur.Sources, line)
			}
		case inWorks:
			if cur != nil {
				cur.Works = append(cur.Works, line)
			}
		}
		// 其余（无当前块或区域外普通行）：忽略。
	}
	return blocks
}

// ParsePlainAuthorNames 解析格式 C（纯作者名列表）：每行一个作者名，仅创建
// 作者不关联文件（GUIDE_AUTHOR：适用于只需导入作者名、后续手动关联的场景）。
//
// 调用方约定：ParseAuthorBlocks 返回空时才走本函数（三格式自动识别）。
// 为防御把混入的文件行/编号行当作者导入（旧版块外忽略语义的同类保护），
// 跳过三类行：媒体扩展名结尾的行、编号行形态、来源/作品标记行。
func ParsePlainAuthorNames(text string) []string {
	var names []string
	seen := make(map[string]bool)
	for _, raw := range strings.Split(text, "\n") {
		line := strings.TrimSpace(raw)
		if line == "" || seen[line] {
			continue
		}
		if hasMediaExt(line) || numberedLineRe.MatchString(line) || isSourcesMarker(line) ||
			line == markerWorks || isAllDigits(line) {
			continue
		}
		seen[line] = true
		names = append(names, line)
	}
	return names
}

// splitAliases 把编号行内容按两个以上空白分隔为别名列表（括号备注去除前）。
func splitAliases(rest string) []string {
	parts := aliasSeparatorRe.Split(rest, -1)
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// stripAliasNote 去除作者名中的括号备注：`()`、`[]`、`（）`、`【】` 及其后
// 内容（GUIDE_AUTHOR：`紙飛行機(小红车资源出处)` → `紙飛行機`，
// `bamhor[3D]` → `bamhor`）。截断点取最先出现的任一括号起始字符。
func stripAliasNote(name string) string {
	idx := -1
	for _, ch := range []string{"(", "[", "（", "【"} {
		if i := strings.Index(name, ch); i >= 0 && (idx < 0 || i < idx) {
			idx = i
		}
	}
	if idx >= 0 {
		name = name[:idx]
	}
	return strings.TrimSpace(name)
}

// isSourcesMarker 报告是否来源/出处标记行（标记词起头，可同行跟平台名；
// 标记词后必须紧跟空白，避免误吃以该词开头的作者名）。
func isSourcesMarker(line string) bool {
	for _, m := range []string{markerSources, markerOutsource} {
		if line == m {
			return true
		}
		if rest, ok := strings.CutPrefix(line, m); ok && rest != "" {
			if r, _ := utf8.DecodeRuneInString(rest); unicode.IsSpace(r) {
				return true
			}
		}
	}
	return false
}

// markerOutsource 是 `出处`（与 `来源` 同义，GUIDE_AUTHOR：两者均识别）。
const markerOutsource = "出处"

// platformName 提取来源标记行同行携带的平台名（`出处  kemono` → `kemono`）。
func platformName(line string) string {
	for _, m := range []string{markerSources, markerOutsource} {
		if rest, ok := strings.CutPrefix(line, m); ok {
			return strings.TrimSpace(rest)
		}
	}
	return ""
}

// isAllDigits 报告非空且全为 ASCII 数字（纯数字行判定）。
func isAllDigits(s string) bool {
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return false
		}
	}
	return len(s) > 0
}
