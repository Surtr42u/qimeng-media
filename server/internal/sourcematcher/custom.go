package sourcematcher

// custom.go：自定义出处组合并层（ADR-0033 检索词表维护接口的引擎侧）。
// 内置 130 组（source_groups_data.go）是冻结基线，用户/AI 经词表端点维护的
// 自定义层按 canonical 并入：同名 = 扩变体/扩角色（给内置组补词条），新名 =
// 追加新组。旧版裸名名单（UpdateCustomSources）是合并层的退化输入——无角色
// 表的裸组（同 canonical 时与内置组合并而非整体覆盖，角色表不因裸名丢失）。
// 合并是纯函数，行为由 custom_test.go 锁定。

// MergeGroups 把自定义层并入基线组表（纯函数）。合并顺序 base → names →
// groups；输出为全新深拷贝（不与任一入参共享底层数组），调用方改写入参
// 不影响已合并结果。组间/组内顺序不影响匹配——索引层对变体与别名统一
// 去重并按长度降序排列。
func MergeGroups(base []SourceGroup, names []string, groups []SourceGroup) []SourceGroup {
	out := make([]SourceGroup, 0, len(base)+len(names)+len(groups))
	pos := make(map[string]int, cap(out))
	add := func(g SourceGroup) {
		if g.Canonical == "" {
			return
		}
		if i, ok := pos[g.Canonical]; ok {
			out[i] = mergeOne(out[i], g)
			return
		}
		pos[g.Canonical] = len(out)
		out = append(out, normalizeGroup(g))
	}
	for _, g := range base {
		add(g)
	}
	for _, n := range names {
		add(SourceGroup{Canonical: n})
	}
	for _, g := range groups {
		add(g)
	}
	return out
}

// normalizeGroup 单组规范化：Variants 并入 canonical、各角色 Aliases 并入
// 自身 canonical（存储层允许省略自身，引擎侧兜底保证"规范名恒参与匹配"），
// 精确串去重（先见序）。全部新建底层数组——入参可能是包级内置表，禁止
// 原地改写。
func normalizeGroup(g SourceGroup) SourceGroup {
	out := SourceGroup{
		Canonical:  g.Canonical,
		Variants:   copyUnique(g.Variants, g.Canonical),
		Characters: make([]CharEntry, 0, len(g.Characters)),
	}
	seen := make(map[string]bool, len(g.Characters))
	for _, ce := range g.Characters {
		if ce.Canonical == "" {
			continue
		}
		if seen[ce.Canonical] {
			continue
		}
		seen[ce.Canonical] = true
		out.Characters = append(out.Characters, CharEntry{
			Canonical: ce.Canonical,
			Aliases:   copyUnique(ce.Aliases, ce.Canonical),
		})
	}
	return out
}

// mergeOne 把 next 并入 prev（同 canonical，两侧均已 normalize）：Variants
// 追加、Characters 按 canonical 合并（同名角色 = 扩别名）。
func mergeOne(prev, next SourceGroup) SourceGroup {
	prev.Variants = copyUnique(prev.Variants, next.Variants...)
	for _, ce := range next.Characters {
		found := false
		for i := range prev.Characters {
			if prev.Characters[i].Canonical == ce.Canonical {
				prev.Characters[i].Aliases = copyUnique(prev.Characters[i].Aliases, ce.Aliases...)
				found = true
				break
			}
		}
		if !found {
			prev.Characters = append(prev.Characters, ce)
		}
	}
	return prev
}

// copyUnique 精确串去重拷贝（跳过空串，先见序保留；额外项追加在尾部）。
// 恒返回新建底层数组（cap 恰好装满时长度==容量，后续 append 必然重分配，
// 不会写穿到入参共享的数组）。
func copyUnique(items []string, extra ...string) []string {
	seen := make(map[string]bool, len(items)+len(extra))
	out := make([]string, 0, len(items)+len(extra))
	add := func(list []string) {
		for _, s := range list {
			if s == "" || seen[s] {
				continue
			}
			seen[s] = true
			out = append(out, s)
		}
	}
	add(items)
	add(extra)
	return out
}
