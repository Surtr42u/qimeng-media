package media.qimeng.app.feature.manage

import media.qimeng.sdk.infrastructure.Serializer
import media.qimeng.sdk.models.LegacyBackupImport

/**
 * 旧版备份文件前置校验与摘要（U10-6b；对齐 web/src/lib/backup.ts parseLegacyBackupFile，
 * 逻辑层职责：字节 → 大小闸 → JSON 合法 → 格式信封校验 → 摘要计数。纯 Kotlin，
 * JVM 单测锁定（BackupValidatorTest）；页面组件不内嵌这些规则（铁律 7）。
 * 校验顺序与文案逐字对齐 Web——早失败省一次上传，服务端 BAD_REQUEST/413 同口径。
 */
object BackupValidator {

    /**
     * 备份导入大小上限（64MB）。**双写同步责任**：与服务端 legacyImportMaxBody、
     * web/src/lib/backup.ts L11-14 BACKUP_MAX_BYTES 三方同值（改动须三侧同步）；
     * 服务端超限回 413，客户端前置拦截给可读文案（Web 同注释口径）。
     */
    const val BACKUP_MAX_BYTES = 64 * 1024 * 1024

    /** 校验/解析结果：Invalid.message 即横幅文案（Web throw Error(message) 同构） */
    sealed interface Result {
        data class Ok(val payload: LegacyBackupImport, val summary: BackupSummary) : Result
        data class Invalid(val message: String) : Result
    }

    /** 导入确认摘要（计数取备份内原始条数，与旧版恢复确认弹窗同口径；Web 同名接口） */
    data class BackupSummary(
        val fileName: String,
        val mediaFiles: Int,
        val authors: Int,
        val tags: Int,
        /** 统计条数 = mediaStats + dailyBrowse（导入侧转换回放的两大来源，Web 同口径） */
        val statsRows: Int,
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
        val payload = try {
            moshi.adapter(LegacyBackupImport::class.java).fromJson(text)
        } catch (e: Exception) {
            // 裸解析过、强类型解析挂：信封/数据段结构损坏（缺必填字段或字段类型错），
            // 语义是「格式不符」而非「不是 JSON」，归格式闸文案（Web 同场景会带坏包过闸
            // 再吃服务端 400，App 侧前置拦下更省一次上传）
            return Result.Invalid(MESSAGE_FORMAT_MISMATCH)
        } ?: return Result.Invalid(MESSAGE_BAD_JSON)
        return Result.Ok(payload = payload, summary = summarize(fileName, payload))
    }

    /** 摘要计数（Web backup.ts summarize 同口径：数组缺项计 0） */
    fun summarize(fileName: String, payload: LegacyBackupImport): BackupSummary = BackupSummary(
        fileName = fileName,
        mediaFiles = payload.data.mediaFiles?.size ?: 0,
        authors = payload.data.authors?.size ?: 0,
        tags = payload.data.tags?.size ?: 0,
        statsRows = (payload.data.mediaStats?.size ?: 0) + (payload.data.dailyBrowse?.size ?: 0),
    )

    /** 确认弹窗描述文案（Web backupSummaryText 逐字；toLocaleString → %,d 千分位同语义，
     *  String.format 默认 Locale 与 Web 浏览器侧同为环境地域） */
    fun summaryText(summary: BackupSummary): String =
        (
            "检测到备份数据：%,d 个媒体文件 / %,d 位作者 / %,d 个标签 / %,d 条统计。" +
                "导入按唯一键合并、不删除现有数据，是否导入恢复？"
            ).format(
            summary.mediaFiles,
            summary.authors,
            summary.tags,
            summary.statsRows,
        )

    /** JSON 损坏文案（Web backup.ts L39 逐字） */
    const val MESSAGE_BAD_JSON = "文件不是合法 JSON，请确认选择的是 qimeng_backup.json 备份文件"

    /** 格式不符文案（Web backup.ts L43 逐字） */
    const val MESSAGE_FORMAT_MISMATCH = "备份格式不符：format 必须为 qimeng_backup（旧版「绮梦影库」全量备份）"
}
