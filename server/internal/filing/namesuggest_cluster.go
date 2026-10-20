package filing

// shortestFamilyPrefix 在规范化族键的空格边界由短到长探测已有族。
// 原两层遍历最终也是取最短的「已有族键 + 空格」前缀；按边界查哈希表
// 保留词边界语义，同时避免 F 个族互扫 F 次。输入已由 normNameBase
// 折叠空白，故只检查半角空格，不能把字符前缀（如「角色甲乙」）误并入。
func shortestFamilyPrefix(key string, families map[string]*nameFamily) string {
	for i := 0; i < len(key); i++ {
		if key[i] == ' ' {
			if _, exists := families[key[:i]]; exists {
				return key[:i]
			}
		}
	}
	return key
}
