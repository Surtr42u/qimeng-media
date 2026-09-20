// fileindex.go：作品名匹配域的预计算索引（审计 R2，2026-09-20）。
// insertLinks 原对每个 (作者, 去重作品) 调 MatchWorks 全域线性扫并重算
// 每个文件的规范基础名——O(作者×作品×资产)；本索引一次遍历把每个文件的
// 「规范基础名」与「去 (N) 序号尾基础名」各建哈希桶，单作品匹配降为桶内
// 候选过滤。语义与 MatchWorks 逐字等价（fileindex_test 对照锁定）；
// MatchWorks 保留为行为参照与单测基准，不删除。
package authoring

import (
	"path/filepath"
	"strings"
	"unicode"
)

// foldKey 返回 EqualFold 意义下的规范形（逐 rune 取其 SimpleFold 轨道最
// 小元）。不能用 strings.ToLower 做桶键：ToLower 与 EqualFold 在奇异折叠
// 对（如 U+017F ſ 与 's'、U+212A K 与 'k'）上不等价，会漏原实现可命中的
// 文件；min-of-orbit 键满足 foldKey(a)==foldKey(b) ⟺ strings.EqualFold(a,b)，
// 语义严格等价。轨道长度 2~4，构建期开销可忽略。
func foldKey(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	for _, r := range s {
		m := r
		for f := unicode.SimpleFold(r); f != r; f = unicode.SimpleFold(f) {
			if f < m {
				m = f
			}
		}
		b.WriteRune(m)
	}
	return b.String()
}

// FileIndex 作品名匹配域索引：byBase 供规则 1（精确）查询，byStripped 供
// 规则 2（去尾部 (N) 后）查询；桶内保持输入序。
type FileIndex struct {
	byBase     map[string][]MediaFile // 规范基础名桶，桶内保持输入序
	byStripped map[string][]MediaFile // 去 (N) 序号尾基础名桶，输入序
}

// BuildFileIndex 单遍建索引。stripped 与 base 相同（无尾部 (N)）的文件不
// 进 byStripped——原实现的规则 1 命中即 continue，这类文件不会二次命中。
func BuildFileIndex(files []MediaFile) *FileIndex {
	ix := &FileIndex{
		byBase:     make(map[string][]MediaFile, len(files)),
		byStripped: make(map[string][]MediaFile, len(files)),
	}
	for _, f := range files {
		base := fileBaseOf(f.FileName)
		key := foldKey(base)
		ix.byBase[key] = append(ix.byBase[key], f)
		stripped := fileSuffixTailRe.ReplaceAllString(base, "")
		if stripped != base {
			sKey := foldKey(stripped)
			ix.byStripped[sKey] = append(ix.byStripped[sKey], f)
		}
	}
	return ix
}

// MatchWorks 与包级 MatchWorks 同语义同顺序：规则 1 = byBase 桶内按
// 扩展名规则过滤（hasExt 时 ext 相等，否则全收，过滤不改相对顺序）；
// 规则 2（seqTolerant 时）= byStripped 桶内结果减去规则 1 已命中项
// （原实现 continue 语义；按 AssetID 排除，匹配域内 AssetID 唯一）。
// 返回 append(exact, seq...)。
func (ix *FileIndex) MatchWorks(work string) []MediaFile {
	work = strings.TrimSpace(work)
	if work == "" {
		return nil
	}
	wk := normalizeWork(work)
	key := foldKey(wk.base)

	exact := ix.byBase[key]
	if wk.hasExt {
		filtered := exact[:0:0]
		for _, f := range exact {
			if strings.ToLower(filepath.Ext(f.FileName)) == wk.ext {
				filtered = append(filtered, f)
			}
		}
		exact = filtered
	}
	if !wk.seqTolerant {
		return exact
	}
	hit := make(map[string]bool, len(exact))
	for _, f := range exact {
		hit[f.AssetID] = true
	}
	seq := make([]MediaFile, 0, len(ix.byStripped[key]))
	for _, f := range ix.byStripped[key] {
		if !hit[f.AssetID] {
			seq = append(seq, f)
		}
	}
	return append(exact, seq...)
}
