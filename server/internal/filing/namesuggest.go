package filing

// namesuggest.go：作品名序号联想纯函数（GET /assets/name-suggestions 的
// 核心判定，ADR-0024 修订）。无 IO、无 HTTP，行为由表驱动测试锁定。
//
// 语义升级：
//   1. 检索匹配：前缀匹配（去扩展名/空白折叠/ASCII小写）优先，同时支持分词包含
//      与子串匹配（如输入「法环」命中「艾尔登法环 菈妮」，输入「菈妮」命中中段角色）；
//   2. 序号解析增强：支持裸数字（空格/下划线/连字符/中文紧贴）、半角/全角括号
//      （() / （） / [] / 【】）、常见序号前缀（No. / # / EP 等）；
//   3. 格式与前导零保留：严格保留原始位宽（如「01」→「02」，「09」→「10」）；
//   4. 多角色多样性轮转：按主体角色聚类轮转调度，杜绝单一大热度角色（如玛丽卡多个
//      子系列）霸占全部槽位，确保库内不同角色（菈妮、梅琳娜、瑟濂等）均能优先曝光；
//   5. 无序号族智能推荐：若某角色仅有单文件/无序号文件，自动推荐「规范基名 2」及基名；
//   6. 容量扩容：上限扩容至 8 条（与 api/openapi.yaml NameSuggestions 双写同步）。

import (
	"fmt"
	"path"
	"sort"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

// maxNameSuggestions 联想返回条数上限（不同命名族优先多角色多样性轮转）。
// 与 api/openapi.yaml NameSuggestions.suggestions 的 maxItems: 8 双写同步。
const maxNameSuggestions = 8

// SuggestSeriesNames 作品名序号联想：names 是库内现存文件名（含扩展名），
// q 是用户输入（不带扩展名，防御去扩展名）。
// 返回建议基名（不含扩展名），最多 maxNameSuggestions 条。
func SuggestSeriesNames(names []string, q string) []string {
	qBase := stripExt(strings.TrimSpace(q))
	qNorm := asciiLower(normNameBase(qBase))
	qKey := strings.ReplaceAll(qNorm, " ", "")
	if qKey == "" {
		return []string{}
	}
	qTokens := strings.Fields(qNorm)

	type matchedFile struct {
		base      string
		norm      string
		tier      int
		suffix    seriesSuffix
		numbered  bool
		famKey    string
		rawPrefix string
	}

	var matched []matchedFile
	for _, name := range names {
		// 忽略点文件（如 .gitignore）
		if strings.HasPrefix(name, ".") {
			continue
		}

		base := stripExt(name)
		if isPureDigits(base) {
			continue
		}

		norm := asciiLower(normNameBase(base))
		normNoSpace := strings.ReplaceAll(norm, " ", "")

		tier := calcMatchTier(norm, normNoSpace, qKey, qTokens)
		if tier == 0 {
			continue
		}

		suffix, numbered := parseSeriesSuffix(base)
		famKey := norm
		rawPrefix := normNameBase(base)
		if numbered {
			famKey = asciiLower(normNameBase(suffix.prefix))
			rawPrefix = suffix.prefix
		} else {
			// 对于无序号文件，检查是否带非数字括号修饰（如 "守望先锋 DVA（特写）"、"名 (特写)"）
			cleanBase, _, hasQual := stripBracketQualifier(base)
			if hasQual {
				cleanNorm := asciiLower(normNameBase(cleanBase))
				cleanNoSpace := strings.ReplaceAll(cleanNorm, " ", "")
				// 若去除修饰后与 q 完全相同，不构成独立子实体
				if cleanNoSpace == qKey {
					continue
				}
				famKey = cleanNorm
				rawPrefix = normNameBase(cleanBase)
			} else {
				// 若既无序号也无独立子实体词（如单词 "名单" 搜 "名"、"夕阳风景" 搜 "夕阳"）
				if !isDistinctEntityAfterQuery(norm, qTokens, qNorm) {
					continue
				}
			}
		}

		matched = append(matched, matchedFile{
			base:      base,
			norm:      norm,
			tier:      tier,
			suffix:    suffix,
			numbered:  numbered,
			famKey:    famKey,
			rawPrefix: rawPrefix,
		})
	}

	if len(matched) == 0 {
		return []string{}
	}

	// 汇总为命名族（family）
	fams := make(map[string]*nameFamily)
	for _, m := range matched {
		fam := fams[m.famKey]
		if fam == nil {
			fam = &nameFamily{
				famKey:    m.famKey,
				bestTier:  m.tier,
				rawPrefix: m.rawPrefix,
				rawBase:   normNameBase(m.base),
			}
			fams[m.famKey] = fam
		}
		fam.hits++
		if m.tier < fam.bestTier {
			fam.bestTier = m.tier
		}
		if m.numbered && fam.betterCandidate(m.suffix) {
			fam.hasNum = true
			fam.best = m.suffix
		}
	}

	// 计算 clusterKey：若某族键包含更短族键的前缀（如「法环 玛丽卡 特写」属于「法环 玛丽卡」），
	// 聚类至更短的主体键，保证主体角色级别多样性轮转
	for _, fam := range fams {
		cluster := fam.famKey
		for otherKey := range fams {
			if len(otherKey) < len(cluster) && strings.HasPrefix(cluster, otherKey+" ") {
				cluster = otherKey
			}
		}
		fam.clusterKey = cluster
	}

	// 按 cluster 分组
	clusters := make(map[string][]*nameFamily)
	for _, fam := range fams {
		clusters[fam.clusterKey] = append(clusters[fam.clusterKey], fam)
	}

	// 标记 cluster 整体是否含有序号系列
	clusterHasNum := make(map[string]bool)
	for cKey, famList := range clusters {
		for _, f := range famList {
			if f.hasNum {
				clusterHasNum[cKey] = true
				break
			}
		}
	}

	// cluster 内部排序：tier 升序 → 是否带序号降序 → hits 降序 → famKey 字典序
	for _, famList := range clusters {
		sort.Slice(famList, func(i, j int) bool {
			a, b := famList[i], famList[j]
			if a.bestTier != b.bestTier {
				return a.bestTier < b.bestTier
			}
			if a.hasNum != b.hasNum {
				return a.hasNum && !b.hasNum
			}
			if a.hits != b.hits {
				return a.hits > b.hits
			}
			return a.famKey < b.famKey
		})
	}

	// cluster 间排序：首选成员 tier 升序 → cluster 总命中降序 → clusterKey 字典序
	clusterKeys := make([]string, 0, len(clusters))
	for k := range clusters {
		clusterKeys = append(clusterKeys, k)
	}
	sort.Slice(clusterKeys, func(i, j int) bool {
		ca, cb := clusters[clusterKeys[i]], clusters[clusterKeys[j]]
		tierA, tierB := ca[0].bestTier, cb[0].bestTier
		if tierA != tierB {
			return tierA < tierB
		}
		hitsA, hitsB := 0, 0
		for _, f := range ca {
			hitsA += f.hits
		}
		for _, f := range cb {
			hitsB += f.hits
		}
		if hitsA != hitsB {
			return hitsA > hitsB
		}
		return clusterKeys[i] < clusterKeys[j]
	})

	out := make([]string, 0, maxNameSuggestions)
	seen := make(map[string]bool)

	addSuggestion := func(sug string) bool {
		if sug == "" || seen[sug] {
			return false
		}
		seen[sug] = true
		out = append(out, sug)
		return len(out) == maxNameSuggestions
	}

	// 第一轮：主体角色多样性优先轮转（每个不同角色 cluster 取首选条目）
	for _, cKey := range clusterKeys {
		famList := clusters[cKey]
		topFam := famList[0]
		// 若 cluster 已有序号系列，但首项无序号则跳过
		if clusterHasNum[cKey] && !topFam.hasNum {
			continue
		}
		sug := topFam.formatSuggestion()
		if sug != "" {
			if addSuggestion(sug) {
				return out
			}
		}
	}

	// 第二轮：各角色的子形态 / 副系列条目补充
	maxInCluster := 0
	for _, famList := range clusters {
		if len(famList) > maxInCluster {
			maxInCluster = len(famList)
		}
	}
	for idx := 1; idx < maxInCluster; idx++ {
		for _, cKey := range clusterKeys {
			famList := clusters[cKey]
			if idx < len(famList) {
				fam := famList[idx]
				// 若 cluster 已有序号系列，但子项无序号则跳过
				if clusterHasNum[cKey] && !fam.hasNum {
					continue
				}
				sug := fam.formatSuggestion()
				if sug != "" {
					if addSuggestion(sug) {
						return out
					}
				}
			}
		}
	}

	return out
}

func isPureDigits(s string) bool {
	if s == "" {
		return false
	}
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return false
		}
	}
	return true
}

func isDistinctEntityAfterQuery(norm string, qTokens []string, qNorm string) bool {
	if norm == qNorm {
		return true
	}
	fields := strings.Fields(norm)
	if len(fields) > len(qTokens) {
		return true
	}
	if strings.ContainsAny(norm, "-_") {
		return true
	}
	return false
}

func stripBracketQualifier(s string) (cleanPrefix string, qualifier string, hasQualifier bool) {
	pairs := []struct{ open, close string }{
		{"(", ")"},
		{"（", "）"},
		{"[", "]"},
		{"【", "】"},
	}
	for _, p := range pairs {
		if strings.HasSuffix(s, p.close) {
			openIdx := strings.LastIndex(s, p.open)
			if openIdx > 0 {
				inner := s[openIdx+len(p.open) : len(s)-len(p.close)]
				if !isDigits(inner) {
					clean := strings.TrimSpace(s[:openIdx])
					return clean, inner, true
				}
			}
		}
	}
	return s, "", false
}

func calcMatchTier(norm, normNoSpace, qKey string, qTokens []string) int {
	if strings.HasPrefix(normNoSpace, qKey) {
		return 1
	}
	if len(qTokens) > 1 {
		allMatch := true
		for _, tok := range qTokens {
			if !strings.Contains(norm, tok) {
				allMatch = false
				break
			}
		}
		if allMatch {
			return 2
		}
	}
	if strings.Contains(normNoSpace, qKey) {
		return 3
	}
	return 0
}

type nameFamily struct {
	famKey     string
	clusterKey string
	hits       int
	bestTier   int
	hasNum     bool
	best       seriesSuffix
	rawPrefix  string
	rawBase    string
}

func (f *nameFamily) betterCandidate(s seriesSuffix) bool {
	if !f.hasNum {
		return true
	}
	switch {
	case s.num != f.best.num:
		return s.num > f.best.num
	case s.bracketOpen == "" && f.best.bracketOpen != "":
		return true
	case s.bracketOpen != "" && f.best.bracketOpen == "":
		return false
	default:
		return s.text() < f.best.text()
	}
}

func (f *nameFamily) formatSuggestion() string {
	if f.hasNum {
		return f.best.formatNext()
	}
	// 无序号族：已有 1 个文件，下一推荐序号为 2
	if f.rawBase != "" {
		return f.rawBase + " 2"
	}
	return ""
}

type seriesSuffix struct {
	prefix       string // 序号前的原样前缀
	ws           string // 序号前的分隔（空白）
	marker       string // 序号前缀标（如 No. / # / EP 等）
	num          int    // 序号数值
	width        int    // 序号原始位宽（保留前导零）
	bracketOpen  string // 括号开（"(", "（", "[", "【"）
	bracketClose string // 括号闭（")", "）", "]", "】"）
}

func (s seriesSuffix) text() string {
	return s.prefix + s.ws + s.bracketOpen + s.marker
}

func (s seriesSuffix) formatNext() string {
	nextVal := s.num + 1
	var nextStr string
	if s.width > 1 {
		nextStr = fmt.Sprintf("%0*d", s.width, nextVal)
	} else {
		nextStr = strconv.Itoa(nextVal)
	}

	if s.bracketOpen != "" {
		return s.prefix + s.ws + s.bracketOpen + s.marker + nextStr + s.bracketClose
	}
	return s.prefix + s.ws + s.marker + nextStr
}

func parseSeriesSuffix(base string) (seriesSuffix, bool) {
	if base == "" {
		return seriesSuffix{}, false
	}
	// 1. 尝试括号序号：() / （） / [] / 【】
	if s, ok := parseBracketSeries(base); ok {
		return s, true
	}
	// 2. 尝试裸序号（含空格、连字符、下划线、标前缀及中文紧贴）
	return parseBareSeries(base)
}

func parseBracketSeries(base string) (seriesSuffix, bool) {
	pairs := []struct {
		open  string
		close string
	}{
		{"(", ")"},
		{"（", "）"},
		{"[", "]"},
		{"【", "】"},
	}

	for _, p := range pairs {
		if !strings.HasSuffix(base, p.close) {
			continue
		}
		openIdx := strings.LastIndex(base, p.open)
		if openIdx < 0 {
			continue
		}
		inner := base[openIdx+len(p.open) : len(base)-len(p.close)]
		marker, num, width, ok := parseNumberWithMarker(inner)
		if !ok {
			continue
		}
		ws, wsStart, _ := whitespaceBefore(base, openIdx)
		prefix := base[:openIdx]
		if ws != "" {
			prefix = base[:wsStart]
		}
		if prefix == "" && ws == "" {
			// 整名全为括号序号（如 "(1).png"），无名字主体，不构成系列
			continue
		}
		return seriesSuffix{
			prefix:       prefix,
			ws:           ws,
			marker:       marker,
			num:          num,
			width:        width,
			bracketOpen:  p.open,
			bracketClose: p.close,
		}, true
	}
	return seriesSuffix{}, false
}

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

	digitStr := base[start:end]
	num, ok := parseDigits(digitStr)
	if !ok {
		return seriesSuffix{}, false
	}
	width := len(digitStr)

	// 检查数字前的标记（No. / # / EP 等）或空白/分隔符
	rem := base[:start]
	marker := ""
	for _, m := range []string{"No.", "no.", "NO.", "No ", "no ", "NO ", "#", "EP", "ep", "EP.", "ep.", "Vol.", "vol."} {
		if strings.HasSuffix(rem, m) {
			marker = m
			rem = rem[:len(rem)-len(m)]
			break
		}
	}

	ws, wsStart, hasWs := whitespaceBefore(rem, len(rem))
	prefix := rem
	if hasWs {
		prefix = rem[:wsStart]
	}

	if prefix == "" {
		return seriesSuffix{}, false
	}

	// 规则判定：无空白紧贴单个 ASCII 字母（如 "v2"）不按序号处理；
	// 但中文紧贴（如 "菈妮01"、"菈妮1"）、多位数字（"dva01"）或有 marker/空白者均视为合法序号
	if !hasWs && marker == "" {
		rLast, _ := utf8.DecodeLastRuneInString(prefix)
		if rLast <= unicode.MaxASCII && unicode.IsLetter(rLast) && width == 1 && len(prefix) <= 2 {
			return seriesSuffix{}, false
		}
	}

	return seriesSuffix{
		prefix: prefix,
		ws:     ws,
		marker: marker,
		num:    num,
		width:  width,
	}, true
}

func parseNumberWithMarker(inner string) (marker string, num int, width int, ok bool) {
	trimmed := strings.TrimSpace(inner)
	if trimmed == "" {
		return "", 0, 0, false
	}

	end := len(trimmed)
	start := end
	for start > 0 {
		r, rs := prevRune(trimmed, start)
		if r < '0' || r > '9' {
			break
		}
		start = rs
	}
	if start == end {
		return "", 0, 0, false
	}

	digitStr := trimmed[start:end]
	n, valid := parseDigits(digitStr)
	if !valid {
		return "", 0, 0, false
	}

	prefix := strings.TrimSpace(trimmed[:start])
	return prefix, n, len(digitStr), true
}

func isDigits(s string) bool {
	if s == "" {
		return false
	}
	for i := 0; i < len(s); i++ {
		if s[i] < '0' || s[i] > '9' {
			return false
		}
	}
	return true
}

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
	if err != nil || n < 0 {
		return 0, false
	}
	return n, true
}

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

func prevRune(s string, i int) (rune, int) {
	for start := i - 1; start >= 0 && start >= i-utf8.UTFMax; start-- {
		if utf8.RuneStart(s[start]) {
			r, _ := utf8.DecodeRuneInString(s[start:i])
			return r, start
		}
	}
	return utf8.RuneError, i - 1
}

func normNameBase(name string) string {
	return strings.Join(strings.Fields(stripExt(name)), " ")
}

func stripExt(name string) string {
	ext := path.Ext(name)
	base := strings.TrimSuffix(name, ext)
	if base == "" {
		return name
	}
	return base
}

func asciiLower(s string) string {
	b := []byte(s)
	for i := range b {
		if b[i] >= 'A' && b[i] <= 'Z' {
			b[i] += 'a' - 'A'
		}
	}
	return string(b)
}
