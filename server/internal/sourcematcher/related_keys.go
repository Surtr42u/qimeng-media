package sourcematcher

// 内置词表冻结，模糊键只构建一次；返回的匹配结果仍是每次独立装配的副本。
var builtinRelatedKeys = buildRelatedKeys(builtinGroups)

// buildRelatedKeys 只预计算词条的规范化，不缓存查询结果，也不合并同键原词。
// 原始拼写/别名顺序继续由 SourceGroup 决定，避免破坏联想梯队中的关联词次序。
// 自定义词表在同一个不可变 index 快照内重建并发布，读路径不会混用新旧键。
func buildRelatedKeys(groups []SourceGroup) map[string]string {
	keys := make(map[string]string)
	add := func(term string) {
		if _, exists := keys[term]; !exists {
			keys[term] = CleanFuzzyKey(term)
		}
	}
	for _, g := range groups {
		add(g.Canonical)
		for _, variant := range g.Variants {
			add(variant)
		}
		for _, character := range g.Characters {
			add(character.Canonical)
			for _, alias := range character.Aliases {
				add(alias)
			}
		}
	}
	return keys
}
