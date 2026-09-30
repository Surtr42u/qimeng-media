package authorattach

// vocabulary_prefill.go：通用来源词表的一次性自动预填（ADR-0024）。出厂空
// 词表让来源推荐一直空白；预填从已导入片段统计「被多位作者共用的通用平台
// 名」，单个作者自己的地址（个人 x 页等）与链接形态的词一律排除。预填只
// 发生在 kv 键不存在时——PUT 恒写键（清空也写空数组），键存在即用户已
// 表态，永不再预填（EnsureSourceVocabulary，vocabulary.go）。

import (
	"sort"
	"strings"

	"qimeng-media/server/internal/authoring"
)

// 预填口径常量：保留门槛（被 ≥2 位不同作者共用）与条数上限。上限 20 为
// 词表 maxItems: 32（api/openapi.yaml SourceVocabulary，maxSourceVocabularyItems
// 同源）留出用户手动追加的空间。
const (
	prefillMinAuthorCount = 2
	prefillMaxItems       = 20
)

// wordAuthorStats 是一个来源词的引用统计：引用过它的不同作者 id 集合
// （块 id 去重——同一作者在多个片段/多行重复引用不累加）。
type wordAuthorStats struct {
	authors map[string]bool
}

// PrefillSourceVocabulary 纯函数：从作者块统计预填词表。口径：
//   - 作者身份 = GenerateAuthorID(首别名)，跨片段同 id 去重；
//   - 词逐项 trim 计（空词跳过；片段行的落库形态本就 trim，防御兜底）；
//   - 保留 authorCount >= prefillMinAuthorCount 的词（单作者个人词排除）；
//   - 排除链接形态：含 http/www.（大小写不敏感）或含点号（域名形态 xx.yy；
//     平台名 lofter/forum-c 无点号不受影响）；
//   - 排除不可入库词（ValidSourceWord：控制字符/超长——与词表 PUT 通道
//     同款校验，预填不得写入编辑端点写不进的内容）；
//   - 排序 authorCount 降序、name 升序（项目惯例），截断 prefillMaxItems；
//     无合格词返回空切片（非 nil）。
func PrefillSourceVocabulary(blocks []authoring.AuthorBlock) []string {
	stats := make(map[string]*wordAuthorStats)
	for _, b := range blocks {
		if len(b.AuthorNames) == 0 {
			continue
		}
		id := authoring.GenerateAuthorID(b.AuthorNames[0])
		for _, raw := range b.Sources {
			word := strings.TrimSpace(raw)
			if word == "" {
				continue
			}
			s := stats[word]
			if s == nil {
				s = &wordAuthorStats{authors: make(map[string]bool)}
				stats[word] = s
			}
			s.authors[id] = true
		}
	}
	type scored struct {
		word  string
		count int
	}
	var kept []scored
	for word, s := range stats {
		if len(s.authors) < prefillMinAuthorCount || isLinkShaped(word) ||
			!authoring.ValidSourceWord(word) {
			continue
		}
		kept = append(kept, scored{word: word, count: len(s.authors)})
	}
	sort.Slice(kept, func(i, j int) bool {
		if kept[i].count != kept[j].count {
			return kept[i].count > kept[j].count
		}
		return kept[i].word < kept[j].word
	})
	if len(kept) > prefillMaxItems {
		kept = kept[:prefillMaxItems]
	}
	out := make([]string, 0, len(kept))
	for _, it := range kept {
		out = append(out, it.word)
	}
	return out
}

// isLinkShaped 报告词是否链接/域名形态（预填排除项：链接是地址不是平台
// 名，来源推荐只该出平台词）。http/www. 大小写不敏感；含任意点号即按域名
// 形态排除（xx.yy）。
func isLinkShaped(word string) bool {
	lower := strings.ToLower(word)
	return strings.Contains(lower, "http") ||
		strings.Contains(lower, "www.") ||
		strings.Contains(word, ".")
}

// parseAllBlocks 按片段数组序解析全部作者块（预填统计与后续同类统计的
// 共用入口；ParseAuthorBlocks 对无块文本返回空，天然跳过格式 C 片段）。
func parseAllBlocks(sources []Source) []authoring.AuthorBlock {
	var blocks []authoring.AuthorBlock
	for _, src := range sources {
		blocks = append(blocks, authoring.ParseAuthorBlocks(src.Content)...)
	}
	return blocks
}
