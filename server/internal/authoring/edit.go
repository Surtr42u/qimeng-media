package authoring

// edit.go：资产编辑的文本手术纯函数（ADR-0024：编辑端点的作者关联/来源
// 变更同样写进片段本体原地更新——TXT 片段是唯一真相，只改关联表会被下次
// 统一重建冲掉）。与 attach.go 同一实现约束：块边界与区域判定的口径与
// ParseAuthorBlocks 完全一致（复用同包私有函数与正则），全部无 IO。

import (
	"strings"
	"unicode"
)

// RemoveWorks 从 content 中 authorID 对应作者块（首遇块，与解析器同序）的
// 作品区删除 filenames 匹配的作品行。匹配规则：行文本 trim 后精确等于
// fileName 优先，归一化匹配兜底（trim+小写+去空白；保留扩展名——png/mp4
// 同名互不误伤）。块不存在或无匹配行时 content 原样返回、removed 为 nil；
// removed 是实际删除的行文本（trim 后、保序）。
func RemoveWorks(content, authorID string, filenames []string) (string, []string) {
	norm := make(map[string]bool, len(filenames))
	for _, f := range filenames {
		if f = normalizeWorkName(f); f != "" {
			norm[f] = true
		}
	}
	if len(norm) == 0 {
		return content, nil
	}
	lines := strings.Split(content, "\n")
	span := findBlock(scanBlockSpans(content), authorID)
	if span == nil {
		return content, nil
	}
	var removed []string
	del := map[int]bool{}
	// 块内逐行分类，只删作品区行（区域状态迁移与解析器逐字一致——来源区
	// 同名行、纯数字分隔行、区域外散行一律不动）。
	inSources, inWorks := false, false
	for i := span.start + 1; i < span.end; i++ {
		line := strings.TrimSpace(lines[i])
		if line == "" {
			continue
		}
		if m := numberedLineRe.FindStringSubmatch(line); m != nil {
			if allHaveMediaExt(splitAliases(strings.TrimSpace(m[1]))) {
				// 数字开头作品行（解析器：作品区内归入 Works）。
				if inWorks && norm[normalizeWorkName(line)] {
					del[i] = true
					removed = append(removed, line)
				}
				continue
			}
			break // 下一块编号行（构造上不可达：span 已截断，防御性终止）
		}
		if isAllDigits(line) {
			continue // 纯数字分隔行
		}
		switch {
		case isSourcesMarker(line):
			inSources, inWorks = true, false
		case line == markerWorks:
			inSources, inWorks = false, true
		case inSources:
		case inWorks:
			if norm[normalizeWorkName(line)] {
				del[i] = true
				removed = append(removed, line)
			}
		}
	}
	if len(del) == 0 {
		return content, nil
	}
	out := make([]string, 0, len(lines)-len(del))
	for i, l := range lines {
		if !del[i] {
			out = append(out, l)
		}
	}
	return strings.Join(out, "\n"), removed
}

// normalizeWorkName 作品行归一化（RemoveWorks 的兜底匹配口径）：trim +
// 小写 + 去全部空白字符，保留扩展名与大小写/空白差异之外的全部字符。
func normalizeWorkName(s string) string {
	var b strings.Builder
	for _, r := range strings.TrimSpace(s) {
		if unicode.IsSpace(r) {
			continue
		}
		b.WriteRune(unicode.ToLower(r))
	}
	return b.String()
}

// ReplaceSources 整体替换 content 中 authorID 对应作者块（首遇块）的来源区：
// 新来源写成单个「来源」标记行 + 出处词两空格并排（来源区规范形态，
// replaceSourceRegion 是唯一实现）；sources 空=来源区整段移除（来源/出处
// 标记行不得残留）。落点见 replaceSourceRegion。found=false=作者不在任何块中
// （content 原样返回，调用方走新建块路径）。写出内容可被 ParseAuthorBlocks
// 原样读回（sources/works 往返恒等）。
func ReplaceSources(content, authorID string, sources []string) (string, bool) {
	lines := strings.Split(content, "\n")
	span := findBlock(scanBlockSpans(content), authorID)
	if span == nil {
		return content, false
	}
	return replaceSourceRegion(lines, span, sources), true
}

// PruneUploadEntries 从上传写入条目中剔除 content 已不存在的部分（编辑是
// 有意变更，ADR-0024：不同步修剪会让重导入保护对已被编辑移除的行误报
// 409）。条目按作者对齐 MissingUploadEntries 的缺失明细，只保留仍存在于
// 片段本体的作品行/来源行；行全部消失（含块被整体移除）的条目整条剔除。
// entries 为空或无缺失时原样返回。
func PruneUploadEntries(entries []UploadEntry, content string) []UploadEntry {
	missing := MissingUploadEntries(content, entries)
	if len(missing) == 0 {
		return entries
	}
	missByID := make(map[string]UploadEntry, len(missing))
	for _, m := range missing {
		missByID[m.AuthorID] = m
	}
	out := make([]UploadEntry, 0, len(entries))
	for _, e := range entries {
		m, ok := missByID[e.AuthorID]
		if !ok {
			out = append(out, e)
			continue
		}
		kept := UploadEntry{AuthorID: e.AuthorID, DisplayName: e.DisplayName, Names: e.Names}
		for _, w := range e.Works {
			if !containsLine(m.Works, w) {
				kept.Works = append(kept.Works, w)
			}
		}
		for _, s := range e.Sources {
			if !containsLine(m.Sources, s) {
				kept.Sources = append(kept.Sources, s)
			}
		}
		if len(kept.Works) > 0 || len(kept.Sources) > 0 {
			out = append(out, kept)
		}
	}
	return out
}
