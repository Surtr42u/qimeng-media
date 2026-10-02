package store

// provenance.go：关联/作者类记录的溯源词表单源（ADR-0032，migration 0016）。
//
// 每一行 asset_authors/asset_tags/asset_characters/authors 记录携带
// 「何时（created_at）+ 经何通道（origin）」证据；origin 取值即下方受控
// 词表。为什么在 store 包：四个写入侧包（httpapi/authorattach/scanner 与
// 导入查询）都依赖 store（FormatTimestamp 已是先例），此处放常量不引入
// 新依赖方向；词表校验（导入端兜底）同文件单点维护。
//
// 语义细则（逐字遵守 DOMAIN_RULES §10「关联溯源与行级合并裁决」）：
//   - origin 记录「写入本行的通道」，不随备份搬运失真（导出透传原始值）；
//   - OriginLegacy 是迁移回填专用哨兵（0016 DEFAULT），写入端永不主动写；
//   - 备份透传值经 NormalizeOrigin 校验，非法/缺省兜底 OriginImport。

// 写入通道受控词表（ADR-0032 决策 2）。协议侧备份载荷 description 双写
// 同步：api/openapi.yaml LegacyAuthor/LegacyAuthorMediaRef/LegacyMediaTagRef
// 的 origin 字段说明须与此词表一致，反之亦然（AI_README_FIRST 代码卫生约束 3）。
const (
	// OriginClient：HTTP 客户端端点写入（PUT /assets/{id}/tags、
	// PUT /assets/{id}/authors、上传挂靠）。web/app 命中同一端点，服务端
	// 不做 User-Agent 猜测——设备级区分留待协议级客户端标识（ADR-0032）。
	OriginClient = "client"
	// OriginTXT：TXT 片段统一重建（POST /authors/import-txt 手动通道）。
	OriginTXT = "txt"
	// OriginImport：备份导入通道（POST /import/qimeng-backup，含备份 TXT
	// 片段段触发的重建；备份未透传来历时的兜底章）。
	OriginImport = "import"
	// OriginLocalSync：本机同步通道自动导入（ADR-0030，TXT 经 importTxt
	// 时以通道参数区分；其媒体文件入库后的关联由扫描器派生盖 OriginScanner）。
	OriginLocalSync = "local-sync"
	// OriginScanner：扫描器派生（asset_characters 全部、COS 作者关联）。
	OriginScanner = "scanner"
	// OriginLegacy：0016 迁移回填存量行=不可考。写入端永不主动写此值。
	OriginLegacy = "legacy"
)

// validOrigins 词表全集（含 OriginLegacy：备份导出可原样透传对端不可考行，
// 导入侧对 'legacy' 同样按词表放行——透传不撒谎）。
var validOrigins = map[string]bool{
	OriginClient: true, OriginTXT: true, OriginImport: true,
	OriginLocalSync: true, OriginScanner: true, OriginLegacy: true,
}

// NormalizeOrigin 把备份透传的 origin 校验归一：空串/未携带/非法值一律兜底
// OriginImport（DOMAIN_RULES §10 导入裁决 ①：词表校验）。
func NormalizeOrigin(raw *string) string {
	if raw == nil {
		return OriginImport
	}
	if validOrigins[*raw] {
		return *raw
	}
	return OriginImport
}
