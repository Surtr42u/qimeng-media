package sourcematcher

// stop_words.go：停用词内置冻结基线（DOMAIN_RULES §4 兜底提取层）。
// 这些词是文件名里的内容备注/描述词（用户 2026-10-03 拍板：与序号后备注
// 同等对待——只留文件名显示、不进角色胶囊），不是角色名。基线冻结不改，
// 后续增补一律走词层（Matcher.UpdateStopWords，ADR-0033 端点 stopWords 字段）。

// builtinStopWords 停用词内置基线（折叠域精确匹配）。
var builtinStopWords = []string{"触手", "白丝", "婚纱", "多角色", "多角色酒吧"}
