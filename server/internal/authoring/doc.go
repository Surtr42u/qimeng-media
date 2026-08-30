// Package authoring 实现作者体系的领域规则：TXT 作者导入解析、作品名-文件名
// 匹配、authorId 生成。
//
// 职责与规则唯一权威：docs/DOMAIN_RULES.md §6（作者体系）+ 旧仓库
// QimengMedia/docs/GUIDE_AUTHOR.md「TXT 导入规则」「文件名匹配规则」节与
// GUIDE_DATA.md「COS 数据规则」的 authorId 生成规则；TXT 解析行为语义由旧
// 项目单元测试 AuthorImportUseCaseTest.kt 的断言锁定，本包测试逐条照译。
// 实现全部重写（未搬运旧 Kotlin 实现代码）。
//
// 三块能力：
//   - ParseAuthorBlocks：格式 A/B/B2（编号行 + 来源/出处 + 作品区）解析，
//     状态机纯函数；ParsePlainAuthorNames：格式 C（纯作者名列表）解析。
//   - MatchWorks：TXT 作品名与库内文件名匹配（规则 1 精确匹配 + 规则 2
//     序号括号容错），扩展名判定口径见 match.go。
//   - GenerateAuthorID / GenerateCosAuthorID：authorId 生成（保留中日文
//     Unicode、去特殊符号、空格转下划线、小写化、空清洗哈希兜底；COS 同
//     规则加 cos_ 前缀）。
//
// 边界（docs/ARCHITECTURE.md §5，同 recommend/stats 口径）：
//   - 纯函数：不碰 IO，不访问数据库/文件系统/网络；输入输出均为字符串与
//     领域结构体；不依赖任何其他业务模块
//   - 落库与 kv_settings 存储由调用方（httpapi/scanner）组装，本包不懂存储
//
// 语义决策（与旧项目的有意差异）：旧版「blocks 分片存储」不实现——它是旧
// Android CursorWindow（约 2MB 单行上限）的规避手段，新架构 TXT 内容经 HTTP
// 请求体（有 1MB 上限）进入、片段整行存 kv_settings，无分片需求。
package authoring
