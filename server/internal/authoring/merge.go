package authoring

// merge.go：作者合并的文本手术纯函数（POST /authors/merge，2026-11-06）。
//
// 为什么单独成文件：合并的跨片段编排（哪些片段有源块/目标块、条目与关联
// 迁移）属于 authorattach 编排层；本文件只负责「把一批别名/作品行/来源词
// 并入 content 中目标身份的首个块」这一文本真相操作。块边界与区域判定
// 全部复用 parse.go / edit.go / attach.go 的同一套私有函数与正则，禁止
// 在合并路径重写另一套解析口径（TXT 片段是唯一真相）。
//
// 源块的移除与跨片段数据收集不在本文件：目标块可能与源块分处不同片段，
// 调用方先收集源块载荷、移除源块，再把载荷交给 MergeAuthorBlock 写入目标
// 片段——保证「目标块原位保留、源块删除」而数据不丢。

import "strings"

// AuthorBlockMerge 是一次块合并实际并入的条目数（去重后新增）与目标块
// 合并后的别名列表（供上传条目 Names/DisplayName 同步使用）。
type AuthorBlockMerge struct {
	AliasesMerged int
	WorksMerged   int
	SourcesMerged int
	Aliases       []string
}

// MergeAuthorBlock 把 addAliases/addWorks/addSources 并入 content 中 targetID
// 的首个作者块（目标块保持原位置；与 findAuthorBlock 的「数组序首个片段
// 首个同名块」口径一致）：
//   - 别名 = 目标现有别名 + 新增中未出现者（保持目标顺序、去重），编号行
//     经 RewriteAuthorBlockNames 原地重写（编号数字与作品/来源区不动）；
//   - 作品行/来源词由 AppendWorks/AppendSources 并入（各自保序去重；来源区
//     按既有规范形态整段重排，词集合为目标 ∪ 新增）；
//   - 全部条目已存在 → 原文逐字返回、零写入。
//
// 返回 found=false 表示 content 中没有目标块（内容原样返回；调用方按
// 「目标无块」走 AppendAuthorBlock 新建块路径）。addAliases 中的首别名
// 不得改变目标身份：调用方收集的源别名只追加在目标现有别名之后，且目标
// 块已由 targetID 定位，因此回读 id 恒等于 targetID。
func MergeAuthorBlock(content, targetID string, addAliases, addWorks, addSources []string) (string, AuthorBlockMerge, bool) {
	span := findBlock(scanBlockSpans(content), targetID)
	if span == nil {
		return content, AuthorBlockMerge{}, false
	}
	lines := strings.Split(content, "\n")
	existing := parseSpan(lines, span)
	freshAliases := missingWords(existing.AuthorNames, addAliases)
	freshWorks := missingWords(existing.Works, addWorks)
	freshSources := missingWords(existing.Sources, addSources)
	aliases := append(append([]string(nil), existing.AuthorNames...), freshAliases...)
	stats := AuthorBlockMerge{
		AliasesMerged: len(freshAliases),
		WorksMerged:   len(freshWorks),
		SourcesMerged: len(freshSources),
		Aliases:       aliases,
	}
	if len(freshAliases) == 0 && len(freshWorks) == 0 && len(freshSources) == 0 {
		return content, stats, true
	}
	if len(freshAliases) > 0 {
		content, _ = RewriteAuthorBlockNames(content, targetID, aliases)
	}
	if len(freshWorks) > 0 {
		content, _ = AppendWorks(content, targetID, freshWorks)
	}
	if len(freshSources) > 0 {
		content, _ = AppendSources(content, targetID, freshSources)
	}
	return content, stats, true
}
