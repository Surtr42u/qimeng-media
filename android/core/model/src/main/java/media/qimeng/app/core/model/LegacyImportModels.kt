package media.qimeng.app.core.model

/**
 * POST /import/qimeng-backup 迁移结果的消费投影（域类型，2026-10-03 feature:manage
 * 撤 :sdk 直依赖批）。字段为 App 端消费面（成功反馈文案的六计数 + 迁移提示），
 * 与 openapi LegacyImportResult schema 双写同步——协议侧改动须同步此处，反之亦然
 * （协议侧定位：api/openapi.yaml 搜 LegacyImportResult；完整字段集见生成 SDK 模型，
 * 此处只投影消费字段）。字段可空语义与协议一致（服务端恒填充，null 兼容旧服务端）。
 */
data class LegacyImportSummary(
    /** 备份内文件总数 */
    val mediaFilesTotal: Int? = null,
    /** 按文件名匹配到现有库内资产的文件数 */
    val assetsMatched: Int? = null,
    /** 导入的作者数 */
    val authorsImported: Int? = null,
    /** 导入的标签数 */
    val tagsImported: Int? = null,
    /** ViewEvent 回放条数；同批次重复导入为 0 */
    val eventsReplayed: Int? = null,
    /** settings/scanSources 重新配置提示、albumRules 忽略说明等 */
    val warnings: List<String>? = null,
)

/**
 * POST /authors/import-txt 与 /authors/import-txt/rebuild 的结果消费投影
 * （域类型，2026-10-03 feature:manage 撤 :sdk 直依赖批）。App 端只消费两计数
 * （成功反馈文案），完整字段集（含 mergedUploadEntries）见生成 SDK TxtImportResult；
 * 双写同步责任同上（协议侧定位：api/openapi.yaml 搜 TxtImportResult）。
 */
data class TxtImportSummary(
    /** 导入（或重建关联）的作者数 */
    val authorsImported: Int? = null,
    /** 匹配到的文件数 */
    val filesMatched: Int? = null,
)
