package authoring

import (
	"crypto/sha256"
	"encoding/hex"
	"strings"
	"unicode"
)

// kv_settings 键名（migrations/0003；跨包共享所以定义在纯函数包，读写方
// 均须引用此常量，禁止手抄字符串——AI_README_FIRST 代码卫生约束）。
const (
	// SettingKeyCustomSources 存用户自定义出处分区名 JSON 数组（DOMAIN_RULES
	// §4「用户手动添加的分区名自动加入识别」）。读取方：scanner（构造
	// Matcher 时装载）；写入方：出处管理端点（后续任务接线）。
	SettingKeyCustomSources = "custom_sources"

	// SettingKeyCustomSourceGroups 存用户自定义出处组 JSON 数组（元素
	// sourcematcher.SourceGroup 形态：canonical+variants+characters，ADR-0033
	// 检索词表维护）。与 SettingKeyCustomSources（§4 裸名名单）语义隔离：
	// 本键是带角色的出处组，与内置 130 组按 canonical 合并（同名并入扩
	// 变体/角色，新名追加）。读取方：scanner（构造 Matcher 时装载）；
	// 写入方：httpapi 词表维护端点（存储形态 = 匹配引擎输入形态）。
	SettingKeyCustomSourceGroups = "custom_source_groups"

	// SettingKeyCustomStopWords 存停用词追加层 JSON 字符串数组（DOMAIN_RULES
	// §4 兜底提取层跳过的描述词，ADR-0033 端点 stopWords 字段；内置冻结基线
	// 写死 sourcematcher.builtinStopWords 不入库）。读取方：scanner（构造
	// Matcher 时装载）；写入方：httpapi 词表维护端点（PUT 缺省不写此键）。
	SettingKeyCustomStopWords = "custom_stop_words"

	// SettingKeyImportedTxtSources 存全部已导入 TXT 片段 JSON 数组
	//（元素 {filename, content}，统一重建素材，DOMAIN_RULES §6「TXT 导入
	// 以全部已导入 TXT 统一重建为语义」）。读写方：httpapi TXT 导入端点。
	SettingKeyImportedTxtSources = "imported_txt_sources"

	// SettingKeyClientConfig 存客户端配置 JSON（openapi ClientConfig 结构，
	// 设置页扫描/上传卡；读写方 httpapi config.go）。upload 两项实时生效；
	// scan 两项为预留字段（暂未接入扫描器/缩略图管线，保存后暂不生效，
	// openapi description 写明口径）。
	SettingKeyClientConfig = "client_config"

	// SettingKeyClientLogs 存客户端异常上报环形缓冲 JSON 数组（openapi
	// ClientLogEntry 结构，容量 200 条超出丢最旧；读写方 httpapi clientlogs.go）。
	SettingKeyClientLogs = "client_logs"

	// SettingKeyAuthorMirror 存作者总表镜像配置 JSON（openapi AuthorMirrorConfig；
	// 读写方 authorattach；path 空=关闭——用户显式配置的唯一例外写点）。
	SettingKeyAuthorMirror = "author_mirror"

	// SettingKeyUploadEntries 存按片段文件名分组的上传写入条目 JSON（重导入
	// 保护的比对依据；出处元数据而非第二真相——真相永远是 imported_txt_sources
	// 的片段本体，REQ §4.2）。读写方 authorattach。
	SettingKeyUploadEntries = "imported_txt_upload_entries"

	// SettingKeyAuthorSourceVocabulary 存通用来源词表 JSON 字符串数组（来源=
	// 获取渠道平台名，仅记录永不参与匹配；来源建议的唯一数据源，ADR-0024）。
	// 与 §4 资产出处分区（custom_sources）互不相干。读写方 authorattach。
	SettingKeyAuthorSourceVocabulary = "author_source_vocabulary"
)

// AutoFragmentFilename 服务端无任何片段时上传挂靠自动创建的片段名（此后它
// 即「最近导入的片段」；与手工导入片段同构同待遇）。
const AutoFragmentFilename = "上传自动挂靠.txt"

// 作者类型存储值（migrations/0001 authors.type CHECK 约束；scanner/httpapi
// 共用，禁止手抄字符串）。
const (
	AuthorTypeRegular = "regular"
	AuthorTypeCos     = "cos"
)

// CosAuthorIDPrefix 是 COS 作者 authorId 的隔离前缀（DOMAIN_RULES §6：
// 前缀隔离使两套来源同名不冲突）。
const CosAuthorIDPrefix = "cos_"

// hashFallbackPrefix 是清洗后为空时哈希兜底 ID 的前缀（GUIDE_DATA：
// `author_abc12345` 形态）。
const hashFallbackPrefix = "author_"

// GenerateAuthorID 按显示名生成 authorId（GUIDE_DATA「COS 数据规则」，
// DOMAIN_RULES §6 逐字规则）：
//   - 保留中文/日文等 Unicode 字母与数字（Go unicode.IsLetter/IsDigit 覆盖
//     CJK；示例 `rioko凉凉子` → `rioko凉凉子`）；
//   - 下划线保留（常见作者名连接符，如 kamihikoki_mmd，去除会破坏可辨识性）；
//   - 半角空格转下划线；其余特殊符号删除；
//   - 转小写（`水淼Aqua` → `水淼aqua`）；
//   - 清洗后为空用哈希兜底：`author_` + 显示名 SHA-256 前 8 位 hex。
func GenerateAuthorID(displayName string) string {
	var b strings.Builder
	for _, r := range displayName {
		switch {
		case unicode.IsLetter(r) || unicode.IsDigit(r):
			b.WriteRune(r)
		case r == '_':
			b.WriteByte('_')
		case r == ' ':
			b.WriteByte('_')
		}
	}
	id := strings.ToLower(b.String())
	if id == "" {
		sum := sha256.Sum256([]byte(displayName))
		id = hashFallbackPrefix + hex.EncodeToString(sum[:])[:8]
	}
	return id
}

// GenerateCosAuthorID 是 COS 作者的 authorId：同规则加 cos_ 前缀
// （如 `rioko凉凉子` → `cos_rioko凉凉子`）。
func GenerateCosAuthorID(displayName string) string {
	return CosAuthorIDPrefix + GenerateAuthorID(displayName)
}
