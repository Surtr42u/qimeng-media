package filing

// namesuggest.go：作品名序号联想的纯函数（GET /assets/name-suggestions 的
// 核心判定，ADR-0024 修订）。无 IO、无 HTTP，行为由表驱动测试锁定。
//
// 语义（api/openapi.yaml 该端点 description 逐字口径）：对 q 做规范化前缀
// 匹配（去扩展名、空白折叠、ASCII 大小写不敏感）检索库内现存文件名；命中
// 系列按既有编号风格取最大序号 +1，返回「既有命名风格 + 下一序号」的建议
// 基名——用户输入不规范（少空格/小写）时建议自动吸附库里既有的规范写法。
// 序号跨扩展名共用（同名 png/mp4 算同一系列）；无序号成员的族不产生建议。

import (
	"path"
	"sort"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

// maxNameSuggestions 联想返回条数上限（不同命名族各一）。与 api/openapi.yaml
// NameSuggestions.suggestions 的 maxItems: 3 双写同步：协议侧改动必须同步
// 这里，反之亦然（同步责任注释风格见 pagination.go）。
const maxNameSuggestions = 3

// SuggestSeriesNames 作品名序号联想：names 是库内现存文件名（含扩展名），
// q 是用户输入（协议约定不带扩展名，但按同规则先去扩展名防御）。
// 返回建议基名（不含扩展名），每族一条、最多 maxNameSuggestions 条
// （族内命中文件数降序、族键字典序 tie-break）；无命中或 q 规范化后为空
// → 空切片（非 nil，协议 200 空列表语义）。
//
// 匹配与建议的口径：
//   - 规范化 = 去扩展名 → 空白序列折叠为单空格 → trim → ASCII 小写；
//   - 命中 = 规范化后移除全部空白再取前缀（折叠保写法、剥离保命中：
//     「少空格」输入也能吸附「名 12」这类规范写法，与端点描述一致）；
//   - 族键 = 命中基名去掉尾部序号后的规范化形式（序号两种风格：
//     「名 12」裸序号、「名 (2)」括号序号；序号前必须有空白，全角括号
//     「（特写）」是内容不是序号）；
//   - 建议 = 族内最大序号成员的原样风格（保留库里真实大小写与空格）+
//     最大序号 +1 按同风格格式化；序号平局取裸风格（协议描述的主风格），
//     再平取原样前缀字典序较小者，保证结果与输入顺序无关。
func SuggestSeriesNames(names []string, q string) []string {
	qKey := matchKey(q)
	if qKey == "" {
		return []string{}
	}
	fams := make(map[string]*nameFamily)
	for _, name := range names {
		base := stripExt(name)
		norm := asciiLower(normNameBase(base))
		if !strings.HasPrefix(strings.ReplaceAll(norm, " ", ""), qKey) {
			continue
		}
		suffix, numbered := parseSeriesSuffix(base)
		key := norm
		if numbered {
			key = asciiLower(normNameBase(suffix.prefix))
		}
		fam := fams[key]
		if fam == nil {
			fam = &nameFamily{}
			fams[key] = fam
		}
		fam.hits++
		if numbered && fam.betterCandidate(suffix) {
			fam.hasNum = true
			fam.best = suffix
		}
	}
	keys := make([]string, 0, len(fams))
	for key := range fams {
		keys = append(keys, key)
	}
	sort.Slice(keys, func(i, j int) bool {
		if fams[keys[i]].hits != fams[keys[j]].hits {
			return fams[keys[i]].hits > fams[keys[j]].hits
		}
		return keys[i] < keys[j]
	})
	out := make([]string, 0, maxNameSuggestions)
	for _, key := range keys {
		if len(out) == maxNameSuggestions {
			break
		}
		if fam := fams[key]; fam.hasNum {
			out = append(out, fam.best.formatNext())
		}
	}
	return out
}

// nameFamily 是一个命名族（族键 → 命中统计与最大序号成员）。
type nameFamily struct {
	hits   int          // 族内命中文件数（含无序号成员，排序用）
	hasNum bool         // 族内是否存在带序号成员（决定是否产生建议）
	best   seriesSuffix // 最大序号成员（建议的原样风格来源）
}

// betterCandidate 报告 s 是否比当前 best 更有资格作为族的代表成员：
// 序号更大者优先；平局裸风格优先（括号风格是上传冲突重命名的产物，
// 裸序号是用户自己的主风格）；再平取原样前缀字典序较小者（与输入顺序无关）。
func (f *nameFamily) betterCandidate(s seriesSuffix) bool {
	if !f.hasNum {
		return true
	}
	switch {
	case s.num != f.best.num:
		return s.num > f.best.num
	case s.paren != f.best.paren:
		return f.best.paren && !s.paren
	default:
		return s.text() < f.best.text()
	}
}

// seriesSuffix 是基名尾部解析出的序号（「名 12」/「名 (2)」两种风格）。
type seriesSuffix struct {
	prefix string // 序号前的基名原样前缀（不含序号与其前空白）
	ws     string // 序号前的空白（原样保留进建议）
	num    int    // 序号值
	paren  bool   // true=「名 (2)」括号风格，false=「名 12」裸风格
}

func (s seriesSuffix) text() string { return s.prefix + s.ws }

// formatNext 组装「原样风格 + 最大序号 +1」的建议基名（不含扩展名）。
// 前导零不保留：「dva 07」的下一个建议是「dva 8」——序号风格只承载
// 裸/括号两种排版形态，数字位宽不属风格（审查记档口径）。
func (s seriesSuffix) formatNext() string {
	next := strconv.Itoa(s.num + 1)
	if s.paren {
		return s.prefix + s.ws + "(" + next + ")"
	}
	return s.prefix + s.ws + next
}

// parseSeriesSuffix 解析基名尾部的序号（在原串上做，保留原样风格所需的大小
// 写与空白信息）；ok=false = 无序号（含全角括号内容、无空白紧贴数字、整名
// 纯数字、序号溢出等不构成「名 序号」形态的情况）。
func parseSeriesSuffix(base string) (seriesSuffix, bool) {
	if base == "" {
		return seriesSuffix{}, false
	}
	if base[len(base)-1] == ')' {
		return parseParenSeries(base)
	}
	return parseBareSeries(base)
}

// parseParenSeries 解析括号序号「名 (2)」：ASCII 半角括号、纯数字内容、
// 括号前必须有空白（全角「（特写）」与「名(2)」紧贴形态不算序号）。
func parseParenSeries(base string) (seriesSuffix, bool) {
	open := strings.LastIndexByte(base, '(')
	if open <= 0 {
		return seriesSuffix{}, false
	}
	num, ok := parseDigits(base[open+1 : len(base)-1])
	if !ok {
		return seriesSuffix{}, false
	}
	ws, start, ok := whitespaceBefore(base, open)
	if !ok || start == 0 {
		return seriesSuffix{}, false
	}
	return seriesSuffix{prefix: base[:start], ws: ws, num: num, paren: true}, true
}

// parseBareSeries 解析裸序号「名 12」：尾部数字串且前面必须有空白
// （整名纯数字没有「名字」部分，不构成系列）。
func parseBareSeries(base string) (seriesSuffix, bool) {
	end := len(base)
	start := end
	for start > 0 {
		r, rs := prevRune(base, start)
		if r < '0' || r > '9' {
			break
		}
		start = rs
	}
	if start == end || start == 0 {
		return seriesSuffix{}, false
	}
	num, ok := parseDigits(base[start:end])
	if !ok {
		return seriesSuffix{}, false
	}
	ws, wsStart, ok := whitespaceBefore(base, start)
	if !ok || wsStart == 0 {
		return seriesSuffix{}, false
	}
	return seriesSuffix{prefix: base[:wsStart], ws: ws, num: num}, true
}

// parseDigits 解析非空 ASCII 数字串为序号；溢出 int 的超大串不按序号处理
// （避免 max+1 回绕出负数建议）。
func parseDigits(s string) (int, bool) {
	if s == "" {
		return 0, false
	}
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return 0, false
		}
	}
	n, err := strconv.Atoi(s)
	if err != nil {
		return 0, false
	}
	return n, true
}

// whitespaceBefore 从下标 i 起向前收集连续空白（unicode 空白，含全角空格），
// 返回（空白串, 空白段起始下标, 是否至少一个空白）。
func whitespaceBefore(s string, i int) (string, int, bool) {
	start := i
	for start > 0 {
		r, rs := prevRune(s, start)
		if !unicode.IsSpace(r) {
			break
		}
		start = rs
	}
	if start == i {
		return "", i, false
	}
	return s[start:i], start, true
}

// prevRune 解码 s 中结束于下标 i 的 rune（返回 rune 与其起始下标）。
func prevRune(s string, i int) (rune, int) {
	for start := i - 1; start >= 0 && start >= i-utf8.UTFMax; start-- {
		if utf8.RuneStart(s[start]) {
			r, _ := utf8.DecodeRuneInString(s[start:i])
			return r, start
		}
	}
	return utf8.RuneError, i - 1
}

// normNameBase 基名规范化：去扩展名 → 空白序列折叠为单空格 → trim。
// 不含大小写折叠（仅匹配键做小写；族键按端点描述含 ASCII 小写，
// 由调用方在 normNameBase 结果上追加）。
func normNameBase(name string) string {
	return strings.Join(strings.Fields(stripExt(name)), " ")
}

// stripExt 去掉文件名最后一个扩展名（与 ResolveConflict 同口径：整名被
// Ext 视为扩展名的点文件「.gitignore」原样保留，避免剥成空串）。
func stripExt(name string) string {
	ext := path.Ext(name)
	base := strings.TrimSuffix(name, ext)
	if base == "" {
		return name
	}
	return base
}

// matchKey 联想匹配键：规范化后移除全部空白再 ASCII 小写（仅 A-Z 折叠，
// 中文/假名等非 ASCII 原样）——「空白序列折叠」保证多空格不裂分写法，
// 「移除空白」保证少空格输入也能命中既有规范写法（端点描述的吸附语义）。
func matchKey(name string) string {
	return asciiLower(strings.ReplaceAll(normNameBase(name), " ", ""))
}

// asciiLower ASCII 小写折叠（逐字节安全：仅 A-Z 字节改写，多字节 UTF-8
// 序列不受影响）。
func asciiLower(s string) string {
	b := []byte(s)
	for i := range b {
		if b[i] >= 'A' && b[i] <= 'Z' {
			b[i] += 'a' - 'A'
		}
	}
	return string(b)
}
