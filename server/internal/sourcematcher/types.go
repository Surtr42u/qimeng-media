package sourcematcher

// SourceGroup 是一个出处的检索组：规范名 + 全部变体 + 该出处下的角色表。
//
// 结构语义与旧项目一致（DOMAIN_RULES §4）：变体含中文名（带/不带空格）、
// 英文名、常见缩写；同系列不同版本归入同组，版本号进 Variants
// （铁拳7/8→"铁拳"，生化危机2/3/4/8→"生化危机"）。
type SourceGroup struct {
	Canonical  string      // 规范名（最通用中文名，不带版本号）
	Variants   []string    // 全部变体（含中文名带/不带空格、英文名、缩写、版本号）
	Characters []CharEntry // 该出处下的角色检索表（可为空）
}

// CharEntry 是一个角色的检索条目：规范名 + 别名表。
//
// 出处+角色名是唯一标识——跨出处同名角色是不同角色（如"安娜"在守望先锋
// 和铁拳中各自独立）。Aliases 含自身+中文别名+英文名+昵称，按长度降序维护。
type CharEntry struct {
	Canonical string   // 角色规范名（最常用中文名）
	Aliases   []string // 全部别名（含自身；匹配按长度降序）
}
