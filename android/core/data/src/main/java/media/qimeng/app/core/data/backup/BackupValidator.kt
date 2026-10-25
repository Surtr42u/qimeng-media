package media.qimeng.app.core.data.backup

import media.qimeng.sdk.infrastructure.Serializer
import media.qimeng.sdk.models.LegacyBackupImport

/**
 * 校验通过的备份载荷（不透明句柄）：包住生成 SDK 的 LegacyBackupImport——SDK 类型不出
 * core:data（feature:manage 无 :sdk 依赖，2026-10-03 撤依赖批），确认弹窗持有句柄、
 * 确认导入时原样递回 [media.qimeng.app.core.data.repository.BackupRepository.importBackup]
 * 发网。构造只经 [BackupValidator.validate] 真校验链（测试同链取得，不手工拼装）。
 */
class ValidatedBackupPayload internal constructor(
    internal val sdkPayload: LegacyBackupImport,
)

/**
 * 当前v2备份文件前置校验与摘要（U10-6b；对齐 web/src/lib/backup.ts parseBackupFile，
 * 逻辑层职责：字节 → 大小闸 → JSON 合法 → 格式信封校验 → 摘要计数。纯 Kotlin，
 * JVM 单测锁定（core:data test BackupValidatorTest）；页面组件不内嵌这些规则（铁律 7）。
 * 校验顺序与文案逐字对齐 Web——早失败省一次上传，服务端 BAD_REQUEST/413 同口径。
 * 2026-10-03 自 feature:manage 整体搬入本包（Serializer 解析与 SDK 模型引用收口
 * core:data，feature:manage 改持域类型句柄；逻辑零变化）。
 */
object BackupValidator {

    /** 与OpenAPI的schemaVersion、portablebackup.SchemaVersion及Web同步。 */
    const val BACKUP_SCHEMA_VERSION = 2

    /**
     * 备份导入大小上限（64MB）。**双写同步责任**：与服务端portablebackup.ImportMaxBody、
     * web/src/lib/backup.ts L11-14 BACKUP_MAX_BYTES 三方同值（改动须三侧同步）；
     * 服务端超限回 413，客户端前置拦截给可读文案（Web 同注释口径）。
     */
    const val BACKUP_MAX_BYTES = 64 * 1024 * 1024

    /** 校验/解析结果：Invalid.message 即横幅文案（Web throw Error(message) 同构） */
    sealed interface Result {
        data class Ok(val payload: ValidatedBackupPayload, val summary: BackupSummary) : Result
        data class Invalid(val message: String) : Result
    }

    /** 导入确认摘要（计数取备份内原始条数，与旧版恢复确认弹窗同口径；Web 同名接口） */
    data class BackupSummary(
        val fileName: String,
        val mediaFiles: Int,
        val authors: Int,
        val tags: Int,
        /** 当前原生viewEvents条数，展示聚合段不参与导入计数 */
        val eventRows: Int,
    )

    /** 校验并解析备份字节；逐字对齐 Web backup.ts 三段文案与判定顺序 */
    fun validate(fileName: String, bytes: ByteArray): Result {
        if (bytes.size > BACKUP_MAX_BYTES) {
            // Web: `备份文件超过 ${(BACKUP_MAX_BYTES / 1024 / 1024).toFixed(0)}MB 上限，…`
            return Result.Invalid("备份文件超过 ${BACKUP_MAX_BYTES / 1024 / 1024}MB 上限，请确认导出的是完整备份")
        }
        val text = String(bytes, Charsets.UTF_8)
        val moshi = Serializer.moshi
        // 第一段裸解析任意 JSON（对齐 Web JSON.parse 的「文件不是合法 JSON」闸）；
        // 第二段再过信封格式闸（format/完整性缺项=格式不符而非 JSON 非法，Web 同分层）
        val parsed: Any? = try {
            moshi.adapter(Any::class.java).fromJson(text)
        } catch (e: Exception) {
            return Result.Invalid(MESSAGE_BAD_JSON)
        }
        val envelope = parsed as? Map<*, *> ?: return Result.Invalid(MESSAGE_FORMAT_MISMATCH)
        if (envelope["format"] != "qimeng_backup" || envelope["data"] == null) {
            return Result.Invalid(MESSAGE_FORMAT_MISMATCH)
        }
        if ((envelope["schemaVersion"] as? Number)?.toDouble() != BACKUP_SCHEMA_VERSION.toDouble()) {
            return Result.Invalid(MESSAGE_UNSUPPORTED_VERSION)
        }
        val data = envelope["data"] as? Map<*, *> ?: return Result.Invalid(MESSAGE_FORMAT_MISMATCH)
        if (data["viewEvents"] !is List<*>) return Result.Invalid(MESSAGE_MISSING_EVENTS)
        val payload = try {
            moshi.adapter(LegacyBackupImport::class.java).fromJson(text)
        } catch (e: Exception) {
            // 裸解析过、强类型解析挂：信封/数据段结构损坏（缺必填字段或字段类型错），
            // 语义是「格式不符」而非「不是 JSON」，归格式闸文案（Web 同场景会带坏包过闸
            // 再吃服务端 400，App 侧前置拦下更省一次上传）
            return Result.Invalid(MESSAGE_FORMAT_MISMATCH)
        } ?: return Result.Invalid(MESSAGE_BAD_JSON)
        val handle = ValidatedBackupPayload(payload)
        return Result.Ok(payload = handle, summary = summarize(fileName, handle))
    }

    /** 摘要计数（Web backup.ts summarize 同口径：数组缺项计 0） */
    fun summarize(fileName: String, payload: ValidatedBackupPayload): BackupSummary {
        val data = payload.sdkPayload.data
        return BackupSummary(
            fileName = fileName,
            mediaFiles = data.mediaFiles?.size ?: 0,
            authors = data.authors?.size ?: 0,
            tags = data.tags?.size ?: 0,
            eventRows = data.viewEvents?.size ?: 0,
        )
    }

    /** 确认弹窗描述文案（Web backupSummaryText 逐字；toLocaleString → %,d 千分位同语义，
     *  String.format 默认 Locale 与 Web 浏览器侧同为环境地域） */
    fun summaryText(summary: BackupSummary): String =
        (
            "检测到备份数据：%,d 个媒体文件 / %,d 位作者 / %,d 个标签 / %,d 条事件。" +
                "导入按事件身份和关联规则合并、不删除媒体原件，是否导入恢复？"
            ).format(
            summary.mediaFiles,
            summary.authors,
            summary.tags,
            summary.eventRows,
        )

    /** JSON 损坏文案（Web backup.ts L39 逐字） */
    const val MESSAGE_BAD_JSON = "文件不是合法 JSON，请确认选择的是 qimeng_backup.json 备份文件"

    const val MESSAGE_UNSUPPORTED_VERSION = "仅支持本项目的 v$BACKUP_SCHEMA_VERSION 备份，旧版及未知版本不再导入"
    const val MESSAGE_MISSING_EVENTS = "备份格式不完整：v2 必须包含 viewEvents 数组"

    /** 格式不符文案（Web backup.ts L43 逐字） */
    const val MESSAGE_FORMAT_MISMATCH = "备份格式不符：format 必须为 qimeng_backup，data 必须为对象"
}
