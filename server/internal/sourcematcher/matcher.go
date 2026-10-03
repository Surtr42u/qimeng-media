package sourcematcher

import (
	"regexp"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"unicode"
	"unicode/utf8"
)

// DefaultCacheSize 匹配结果缓存的默认条目上限。
// 与旧项目 matchAllCache（ConcurrentHashMap，容量上限 8192 超限清空重建）一致：
// 批量遍历（数千文件分组、同文件被多页面重复匹配）时命中缓存 O(1)，
// 消除重复的出处+角色匹配开销。
const DefaultCacheSize = 8192

// leadingPunctRe 匹配剩余串开头的空白与标点（DOMAIN_RULES §4 stripSourceFromName
// 第一步清理）：+/x/& 是角色分隔符（天然支持）、-/_/括号是常见文件名连接符。
// 正则字面量是 §4 逐字规则的一部分，禁止改动。
var leadingPunctRe = regexp.MustCompile(`^[\s+_\-()（）]+`)

// variantEntry 是出处变体索引条目（构造时按长度降序排序，匹配取首个前缀命中）。
type variantEntry struct {
	collapsed string // 去空格 + 小写后的变体（主匹配层）
	raw       string // 仅小写、保留空格（回退层：原始前缀匹配）
	canonical string
}

// aliasEntry 是角色别名索引条目（构造时按别名长度降序排序）。
type aliasEntry struct {
	alias     string // collapsed 后的别名
	canonical string
}

// span 是角色别名在剩余串中占用的区间（区域重叠检测用）。
type span struct{ start, end int }

// index 是不可变匹配索引快照：UpdateCustomSources 时整体重建并原子替换，
// 读路径无锁（并发语义与旧项目"重建检索表后整体替换"等价）。
type index struct {
	groups   map[string]*SourceGroup // canonical → 组（含自定义出处）
	variants []variantEntry          // 全部出处变体（builtin + 自定义），长度降序
	strip    map[string][]string     // canonical → collapsed 变体（长度降序，剥离开头出处用）
	aliases  map[string][]aliasEntry // canonical → 角色别名索引（长度降序）
	// stripRaw 是 canonical → 小写原样变体（未折叠空格，长度降序）。兜底提取层
	// 在原串上剥离开头出处时用（折叠域会抹掉词边界，无法分词），见
	// extractCharacters。
	stripRaw map[string][]string
	// stopWords 是兜底提取层的停用词集合（折叠域，builtinStopWords ∪
	// 用户追加层）：extractTokens 命中即跳过（不终止收集），别名表层
	// 不受影响（DOMAIN_RULES §4，2026-10-03）。
	stopWords map[string]struct{}
}

// matchSource 出处前缀匹配（DOMAIN_RULES §4 匹配流程）：
// 变体按长度降序做 collapsed 前缀匹配（最长优先，"尼尔机械纪元"优先于"尼尔"），
// 未命中回退原始（未折叠）前缀匹配，仍未命中返回 ""。
func (ix *index) matchSource(base string) string {
	collapsed := collapse(base)
	for i := range ix.variants {
		if strings.HasPrefix(collapsed, ix.variants[i].collapsed) {
			return ix.variants[i].canonical
		}
	}
	// 回退层：原始（未折叠）前缀匹配。数学上 collapsed 层已覆盖前缀折叠的全部
	// 命中（折叠是前缀保持的），此层按 DOMAIN_RULES §4 与旧版行为描述的两层
	// 流程保留（语义冗余但无害，行为与旧实现一致）。
	lowered := strings.ToLower(base)
	for i := range ix.variants {
		if strings.HasPrefix(lowered, ix.variants[i].raw) {
			return ix.variants[i].canonical
		}
	}
	return ""
}

// stripSource 剥离文件名开头已匹配的出处部分（旧 stripSourceFromName 语义，
// 在 collapsed 域操作）：该出处全部变体按长度降序尝试前缀剥离（只剥一次），
// 随后两步清理——先去开头空白与标点，再去开头数字但保留后跟 ASCII 字母的
// （数字保护，避免 "2B"/"9S" 类角色名被误删）。
// 返回的剩余串用于角色别名子串匹配。
func (ix *index) stripSource(base, source string) string {
	rest := collapse(base)
	for _, v := range ix.strip[source] { // 已按长度降序
		if strings.HasPrefix(rest, v) {
			rest = rest[len(v):]
			break
		}
	}
	rest = leadingPunctRe.ReplaceAllString(rest, "")
	return stripLeadingDigits(rest)
}

// matchCharacters 角色匹配（DOMAIN_RULES §4）：剩余部分在该出处角色别名表做
// 长度降序子串匹配；同一 canonical 不因多个别名重复添加（去重保护）；区域
// 重叠检测避免同一位置被不同角色重复匹配（长别名优先占用）；结果按 canonical
// 字典序排序（"天使+dva" 与 "dva 天使" 同结果）。
func (ix *index) matchCharacters(base, source string) []string {
	if ix.groups[source] == nil {
		return nil
	}
	rest := ix.stripSource(base, source)
	if rest == "" {
		return nil
	}
	var used []span
	seen := make(map[string]bool)
	var out []string
	for _, a := range ix.aliases[source] {
		if seen[a.canonical] {
			continue // 同 canonical 去重保护；长别名被区域挡住时短别名仍可补位
		}
		for off := 0; ; {
			i := strings.Index(rest[off:], a.alias)
			if i < 0 {
				break
			}
			start, end := off+i, off+i+len(a.alias)
			if !overlapsAny(used, start, end) {
				used = append(used, span{start, end})
				seen[a.canonical] = true
				out = append(out, a.canonical)
				break
			}
			off = start + 1
		}
	}
	sort.Strings(out)
	return out
}

// overlapsAny 报告 [start,end) 是否与任一已占用区间重叠。
func overlapsAny(used []span, start, end int) bool {
	for _, u := range used {
		if start < u.end && u.start < end {
			return true
		}
	}
	return false
}

// ---- 命名规约兜底提取层（DOMAIN_RULES §4「命名规约兜底提取」，2026-10-03）----
//
// 收藏命名高度统一（`出处  角色名 序号.扩展名`）：别名表没收录的新/冷门角色
// 由本层兜住，词表只需维护出处与改名映射。表命中非空时本层完全不参与（零
// 回归）；表零命中才在原串（保留大小写与空格——折叠域无法分词）上提取。

const (
	// extractWordMaxRunes 兜底提取单词条的 rune 上限：真实角色名远短于此，
	// 超长词是描述句混入，拒绝成为角色桶。
	extractWordMaxRunes = 50
	// tokenEdgeTrim 词元边缘清理字符集：连接符与括号序号（"(1)" 剥成 "1" 后
	// 按纯数字终止）。x/&/+ 的分隔语义在 splitSeparators/isSeparatorWord 处理，
	// 不进此集合（避免误伤词内字符）。
	tokenEdgeTrim = "+_-.()（）【】[]"
)

// matchAllCharacters 别名表优先 + 兜底提取（MatchAll 专用）：表命中非空直接
// 返回（本层不参与，既有结果零回归）；表零命中走命名规约提取。
func (ix *index) matchAllCharacters(base, source string) []string {
	if out := ix.matchCharacters(base, source); len(out) > 0 {
		return out
	}
	return ix.extractCharacters(base, source)
}

// extractCharacters 从单段文件名剥离开头出处后按命名规约提取角色名（单段
// 路径用；"+" 多段路径见 MatchAll 对 extractTokens 的直接调用）。
func (ix *index) extractCharacters(base, source string) []string {
	if ix.groups[source] == nil {
		return nil
	}
	rest, ok := trimSourcePrefixFold(base, ix.stripRaw[source])
	if !ok {
		// 防御：折叠域命中的出处变体在原样域剥不掉（带空格写法无原样变体）。
		// 词边界不可得则放弃提取，表路径不受影响。
		return nil
	}
	return ix.extractTokens(rest, source)
}

// extractTokens 在已剥离开头出处的剩余串上按命名规约提取：空格分词、词内
// +/& 再拆、裸 x 作分隔词丢弃；自左向右收集到首个纯数字/纯括号序号词终止
// （序号之后是描述词）。普通词命中本出处别名表取 canonical（改名/归一），
// 否则按原词入库；命中停用词的词是内容备注（触手/白丝等），跳过不终止
// 收集（index.stopWords）；数字开头后跟 ASCII 字母的词（"2B" 形）仅当表
// 认识才保留（防 "8K"/"1080p" 混入）。
func (ix *index) extractTokens(rest, source string) []string {
	var out []string
	seen := make(map[string]bool)
	add := func(tok string) {
		if tok == "" || seen[tok] || utf8.RuneCountInString(tok) > extractWordMaxRunes {
			return
		}
		seen[tok] = true
		out = append(out, tok)
	}
	for _, field := range strings.Fields(rest) {
		if isSeparatorWord(field) {
			continue
		}
		terminated := false
		for _, tok := range splitSeparators(field) {
			tok = strings.Trim(tok, tokenEdgeTrim)
			if tok == "" || isSeparatorWord(tok) {
				continue
			}
			if isIndexNumber(tok) { // 纯数字（含全角）/纯序号：之后是描述词
				terminated = true
				break
			}
			if _, bad := ix.stopWords[collapse(tok)]; bad {
				continue // 停用词：内容备注不进胶囊（跳过不终止）
			}
			if canon, ok := ix.exactAlias(source, tok); ok {
				add(canon) // 表认识：改名/归一（含 "2B" 形保护词）
				continue
			}
			if startsProtectedName(tok) { // "8K"/"1080p" 形且表不认识：丢弃不终止
				continue
			}
			add(tok)
		}
		if terminated {
			break
		}
	}
	sort.Strings(out)
	return out
}

// trimSourcePrefixFold 在原串上按小写变体（长度降序）剥离开头出处，保留
// 剩余部分的原样大小写与空格。逐 rune 大小写折叠比较（unicode.ToLower），
// 不整串 ToLower 后按字节切——个别 rune 折叠会变字节长，回切原串会错位。
func trimSourcePrefixFold(s string, variants []string) (string, bool) {
	for _, v := range variants {
		if v == "" {
			continue
		}
		consumed := 0
		match := true
		for _, want := range v {
			r, size := utf8.DecodeRuneInString(s[consumed:])
			if size == 0 || unicode.ToLower(r) != want {
				match = false
				break
			}
			consumed += size
		}
		if match {
			return s[consumed:], true
		}
	}
	return "", false
}

// splitSeparators 按词内角色分隔符 +/& 拆词（x 不拆——"Rex"/"Max" 类词内
// 字母不能误伤；独立成词的 x 由 isSeparatorWord 处理）。
func splitSeparators(tok string) []string {
	return strings.FieldsFunc(tok, func(r rune) bool {
		return r == '+' || r == '&' || r == '＋' || r == '＆'
	})
}

// isSeparatorWord 报告词是否为纯分隔词（裸 x/X：`Melody x Lawa` 的连接写法）。
func isSeparatorWord(tok string) bool {
	return tok == "x" || tok == "X"
}

// isIndexNumber 报告词是否纯数字（含全角）——文件名里的集数/序号。
func isIndexNumber(tok string) bool {
	hasDigit := false
	for _, r := range tok {
		if !unicode.IsDigit(r) {
			return false
		}
		hasDigit = true
	}
	return hasDigit
}

// startsProtectedName 报告词是否以数字开头且后跟 ASCII 字母（"2B"/"9S"/"8K"
// 形）。表认识时按别名归一保留，不认识时丢弃（画质词混入防护）。
func startsProtectedName(tok string) bool {
	i := 0
	for i < len(tok) && tok[i] >= '0' && tok[i] <= '9' {
		i++
	}
	return i > 0 && i < len(tok) && isASCIILetter(tok[i])
}

// exactAlias 在本出处别名索引里做精确（折叠域）查找：命中返回 canonical。
// 兜底提取的改名/归一与 "2B" 形保护共用本判据。
func (ix *index) exactAlias(source, tok string) (string, bool) {
	c := collapse(tok)
	if c == "" {
		return "", false
	}
	for _, a := range ix.aliases[source] {
		if a.alias == c {
			return a.canonical, true
		}
	}
	return "", false
}

// stripLeadingDigits 剥离开头连续数字段，但数字段后紧跟 ASCII 字母时整段保护。
// 等价 DOMAIN_RULES §4 的 ^\d+(?![a-zA-Z])（RE2 不支持负向先行断言，手工实现）。
// 与 PCRE 回溯语义仅在 "12ab" 类场景有差异（PCRE 会剥 "1" 留 "2ab"）；真实
// 角色名（"2B"/"9S" 单数字+字母）下保护语义完全一致。
func stripLeadingDigits(s string) string {
	i := 0
	for i < len(s) && s[i] >= '0' && s[i] <= '9' {
		i++
	}
	if i == 0 || (i < len(s) && isASCIILetter(s[i])) {
		return s
	}
	return s[i:]
}

func isASCIILetter(b byte) bool {
	return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
}

// collapse 匹配域折叠：去半角空格 + 小写（DOMAIN_RULES §4 匹配流程第 2/3 步，
// 与旧实现 replace(" ","").lowercase() 一致；全角空格不折叠——保真旧语义）。
func collapse(s string) string {
	return strings.ToLower(strings.ReplaceAll(s, " ", ""))
}

// stripExtension 去掉最后一个扩展名；'.' 为首字符（隐藏文件）不剥。
func stripExtension(name string) string {
	if i := strings.LastIndexByte(name, '.'); i > 0 {
		return name[:i]
	}
	return name
}

// matchResult 是缓存的 MatchAll 结果。
type matchResult struct {
	source string
	chars  []string
}

// Matcher 是出处/角色匹配引擎实例（New 构造，并发安全）。
type Matcher struct {
	snap atomic.Pointer[index]

	// updateMu 串行化「替换自定义层 → 重建索引 → 清缓存」：两个词表端点
	// （sources/custom 与 sources/custom-groups，ADR-0033）并发 PUT 时防止
	// 层字段读改写交错——snap.Store 本身原子，但两层字段若不互斥，后写者
	// 会以另一层的中间态重建索引。
	updateMu        sync.Mutex
	customNames     []string      // 旧版裸名层（§4，UpdateCustomSources）
	customGroups    []SourceGroup // 出处组层（ADR-0033，UpdateCustomGroups）
	customStopWords []string      // 停用词追加层（ADR-0033 stopWords 字段，UpdateStopWords）

	cacheMu    sync.Mutex
	cache      map[string]matchResult
	cacheLimit int
}

// New 构造匹配引擎。cacheSize <= 0 时用 DefaultCacheSize。
// 内置检索表与自定义出处在构造时建索引（变体/别名长度降序排序）。
func New(cacheSize int) *Matcher {
	if cacheSize <= 0 {
		cacheSize = DefaultCacheSize
	}
	m := &Matcher{cache: make(map[string]matchResult), cacheLimit: cacheSize}
	m.rebuild()
	return m
}

// UpdateCustomSources 运行时更新自定义出处（用户手动添加的分区名自动加入识别，
// DOMAIN_RULES §4）：canonical = 自定义名本身，参与出处前缀匹配（无角色表）。
// names 允许重复与乱序，内部去重；nil/空切片清空裸名层（不影响出处组层）。
// 更新后清空匹配缓存保证一致性（与旧项目 updateCustomSources 语义一致）。
func (m *Matcher) UpdateCustomSources(names []string) {
	seen := make(map[string]bool, len(names))
	custom := make([]string, 0, len(names))
	for _, n := range names {
		if n != "" && !seen[n] {
			seen[n] = true
			custom = append(custom, n)
		}
	}
	sort.Strings(custom)
	m.updateMu.Lock()
	defer m.updateMu.Unlock()
	m.customNames = custom
	m.rebuild()
	m.clearCache()
}

// UpdateCustomGroups 运行期替换自定义出处组（ADR-0033 词表端点在持久化后
// 同步调用）：groups 即生效名单（传入后所有权归 Matcher，调用方不得再修改），
// 与内置表和裸名层按 canonical 合并——同名 = 扩变体/扩角色，新名 = 追加组；
// nil/空 = 清空出处组层（不影响裸名层）。更新后清空匹配缓存保证一致性。
func (m *Matcher) UpdateCustomGroups(groups []SourceGroup) {
	m.updateMu.Lock()
	defer m.updateMu.Unlock()
	m.customGroups = groups
	m.rebuild()
	m.clearCache()
}

// UpdateStopWords 运行期替换停用词追加层（ADR-0033 词表端点 stopWords 字段
// 调用；构造期装载见 scanner.New/loadStopWords）。与内置基线取并集，只作用
// 于兜底提取层（别名表层不受影响）；nil/空 = 清空追加层（内置基线恒生效）。
// 更新后清空匹配缓存保证一致性。
func (m *Matcher) UpdateStopWords(words []string) {
	seen := make(map[string]bool, len(words))
	custom := make([]string, 0, len(words))
	for _, w := range words {
		if w != "" && !seen[w] {
			seen[w] = true
			custom = append(custom, w)
		}
	}
	sort.Strings(custom)
	m.updateMu.Lock()
	defer m.updateMu.Unlock()
	m.customStopWords = custom
	m.rebuild()
	m.clearCache()
}

// clearCache 整体重置匹配缓存（索引已换，旧键结果作废）。
func (m *Matcher) clearCache() {
	m.cacheMu.Lock()
	m.cache = make(map[string]matchResult)
	m.cacheMu.Unlock()
}

// rebuild 构建不可变索引快照（内置表 + 两层自定义经 MergeGroups 合并）并
// 原子替换。调用方须持 updateMu（New 构造期无并发的例外）。
func (m *Matcher) rebuild() {
	merged := MergeGroups(builtinGroups, m.customNames, m.customGroups)
	ix := &index{
		groups:   make(map[string]*SourceGroup, len(merged)),
		variants: make([]variantEntry, 0, len(merged)*4),
		strip:    make(map[string][]string, len(merged)),
		aliases:  make(map[string][]aliasEntry, len(merged)),
		stripRaw: make(map[string][]string, len(merged)),
	}
	// 停用词集合 = 内置冻结基线 ∪ 用户追加层，逐词折叠后入集合（空串跳过；
	// 两层各自由 Update*/load 侧保证去重，这里再折叠去重一次防空串与重叠）。
	ix.stopWords = make(map[string]struct{}, len(builtinStopWords)+len(m.customStopWords))
	for _, w := range builtinStopWords {
		if c := collapse(w); c != "" {
			ix.stopWords[c] = struct{}{}
		}
	}
	for _, w := range m.customStopWords {
		if c := collapse(w); c != "" {
			ix.stopWords[c] = struct{}{}
		}
	}
	add := func(g *SourceGroup) {
		ix.groups[g.Canonical] = g
		// 出处变体索引：canonical 防御性并入变体集（数据表中 canonical 通常
		// 已在 Variants，补齐保证"匹配规范名本身必然命中"）；collapsed 去重防空变体。
		seen := make(map[string]bool, len(g.Variants)+1)
		stripVars := make([]string, 0, len(g.Variants)+1)
		all := make([]string, 0, len(g.Variants)+1)
		all = append(all, g.Variants...)
		all = append(all, g.Canonical)
		for _, v := range all {
			c := collapse(v)
			if c == "" || seen[c] {
				continue
			}
			seen[c] = true
			stripVars = append(stripVars, c)
			ix.variants = append(ix.variants, variantEntry{
				collapsed: c,
				raw:       strings.ToLower(v),
				canonical: g.Canonical,
			})
		}
		// 剥离表：长度降序（同长按字典序，保证确定性）
		sort.Slice(stripVars, func(i, j int) bool {
			if len(stripVars[i]) != len(stripVars[j]) {
				return len(stripVars[i]) > len(stripVars[j])
			}
			return stripVars[i] < stripVars[j]
		})
		ix.strip[g.Canonical] = stripVars
		// 原样剥离表（小写、保留空格，长度降序）：兜底提取层在原串上剥
		// 离开头出处用（见 extractCharacters）。去重键 = 小写原样串。
		rawSeen := make(map[string]bool, len(all))
		rawVars := make([]string, 0, len(all))
		for _, v := range all {
			low := strings.ToLower(v)
			if low == "" || rawSeen[low] {
				continue
			}
			rawSeen[low] = true
			rawVars = append(rawVars, low)
		}
		sort.Slice(rawVars, func(i, j int) bool {
			if len(rawVars[i]) != len(rawVars[j]) {
				return len(rawVars[i]) > len(rawVars[j])
			}
			return rawVars[i] < rawVars[j]
		})
		ix.stripRaw[g.Canonical] = rawVars
		// 角色别名索引：长度降序（同长按别名与 canonical 字典序，保证确定性）
		var as []aliasEntry
		for _, ce := range g.Characters {
			for _, a := range ce.Aliases {
				ca := collapse(a)
				if ca == "" {
					continue
				}
				as = append(as, aliasEntry{alias: ca, canonical: ce.Canonical})
			}
		}
		sort.Slice(as, func(i, j int) bool {
			if len(as[i].alias) != len(as[j].alias) {
				return len(as[i].alias) > len(as[j].alias)
			}
			if as[i].alias != as[j].alias {
				return as[i].alias < as[j].alias
			}
			return as[i].canonical < as[j].canonical
		})
		ix.aliases[g.Canonical] = as
	}
	for i := range merged {
		add(&merged[i])
	}
	// 变体总表：collapsed 长度降序（最长优先）→ canonical → raw 字典序（跨组
	// 同变体时命中顺序确定）
	sort.Slice(ix.variants, func(i, j int) bool {
		vi, vj := ix.variants[i], ix.variants[j]
		if len(vi.collapsed) != len(vj.collapsed) {
			return len(vi.collapsed) > len(vj.collapsed)
		}
		if vi.canonical != vj.canonical {
			return vi.canonical < vj.canonical
		}
		return vi.raw < vj.raw
	})
	m.snap.Store(ix)
}

// Match 按文件名匹配出处规范名（旧 match() 语义）：去扩展名 → 两层前缀匹配。
// 未命中返回 ""（归"其他"由调用方处理）。
func (m *Matcher) Match(fileName string) string {
	return m.snap.Load().matchSource(stripExtension(fileName))
}

// MatchCharacters 返回文件名在指定出处下的全部角色 canonical
// （旧 matchCharacters() 语义），按 canonical 字典序排序；无命中返回 nil。
func (m *Matcher) MatchCharacters(fileName string, source string) []string {
	return m.snap.Load().matchCharacters(stripExtension(fileName), source)
}

// MatchCharacter 是 MatchCharacters 的兼容形式：多角色按 canonical 字典序
// "+" 拼接（旧 matchCharacter() 语义）。
func (m *Matcher) MatchCharacter(fileName string, source string) string {
	return strings.Join(m.MatchCharacters(fileName, source), "+")
}

// MatchAll 一次调用同时返回出处与角色（旧 matchAll() 语义），结果按文件名缓存。
//
// 文件名（去扩展名后）含 "+" 时分段分别匹配出处并合并：出处按段顺序去重后
// "+" 拼接（"恶魔战士+铁拳8 莫妮卡" → "恶魔战士+铁拳"）；角色 = 对每个命中
// 出处用整名匹配后合并去重、按 canonical 字典序排序（跨出处同名 canonical
// 视为同一药丸显示）。
//
// 角色匹配 = 别名表优先 + 命名规约兜底提取（matchAllCharacters）：表零命中
// 时按 `出处  角色名 序号` 规约从原串提取（DOMAIN_RULES §4 兜底层，2026-10-03）。
func (m *Matcher) MatchAll(fileName string) (string, []string) {
	if r, ok := m.cacheGet(fileName); ok {
		return r.source, r.chars
	}
	ix := m.snap.Load()
	base := stripExtension(fileName)
	var sources []string
	var chars []string
	if strings.Contains(base, "+") {
		seenSrc := make(map[string]bool)
		for _, seg := range strings.Split(base, "+") {
			if s := ix.matchSource(seg); s != "" && !seenSrc[s] {
				seenSrc[s] = true
				sources = append(sources, s)
			}
		}
		seenChar := make(map[string]bool)
		for _, s := range sources {
			// 表层用整名匹配（跨段子串命中是既有语义）；兜底层按分段提取——
			// 只吃自己命中与无主的分段：别的出处段整名提取会把出处名当角色
			// （TestMatchAllMultiSource 锁定：`恶魔战士+铁拳8 莫妮卡` 不得
			// 产出「铁拳8」角色），而无主段（`守望先锋  AA+BB 1` 的 BB）
			// 是同链上无出处前缀的角色名，归入本出处。
			if cs := ix.matchCharacters(base, s); len(cs) > 0 {
				for _, c := range cs {
					if !seenChar[c] {
						seenChar[c] = true
						chars = append(chars, c)
					}
				}
				continue
			}
			for _, seg := range strings.Split(base, "+") {
				owner := ix.matchSource(seg)
				if owner != "" && owner != s {
					continue
				}
				rest := seg
				if owner == s {
					stripped, ok := trimSourcePrefixFold(seg, ix.stripRaw[s])
					if !ok {
						continue // 折叠域才命中的变体：词边界不可得，跳过该段
					}
					rest = stripped
				}
				for _, c := range ix.extractTokens(rest, s) {
					if !seenChar[c] {
						seenChar[c] = true
						chars = append(chars, c)
					}
				}
			}
		}
		sort.Strings(chars)
	} else if s := ix.matchSource(base); s != "" {
		sources = []string{s}
		chars = ix.matchAllCharacters(base, s)
	}
	source := strings.Join(sources, "+")
	m.cacheSet(fileName, matchResult{source: source, chars: chars})
	return source, chars
}

func (m *Matcher) cacheGet(key string) (matchResult, bool) {
	m.cacheMu.Lock()
	defer m.cacheMu.Unlock()
	r, ok := m.cache[key]
	return r, ok
}

// cacheSet 写缓存；超上限整体重置（旧项目 ConcurrentHashMap 容量 8192 超限
// 清空重建的 Go 等价简化：不做逐条 LRU 淘汰，整体重置的语义与均摊成本一致）。
func (m *Matcher) cacheSet(key string, r matchResult) {
	m.cacheMu.Lock()
	defer m.cacheMu.Unlock()
	if len(m.cache) >= m.cacheLimit {
		m.cache = make(map[string]matchResult)
	}
	m.cache[key] = r
}
