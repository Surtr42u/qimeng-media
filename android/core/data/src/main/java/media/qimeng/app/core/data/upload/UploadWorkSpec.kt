package media.qimeng.app.core.data.upload

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import media.qimeng.app.core.model.UploadRules

/**
 * 上传队列的工作契约（M4-5）：唯一链名 / Data 键位 / 输入输出映射 / 终局状态机。
 * 全部纯函数（无 Android 组件调用），单测锁定入队载荷与失败重试状态机。
 *
 * 串行语义说明（冻结架构，不自造锁）：所有上传请求进同一个 [UNIQUE_WORK_NAME]
 * unique 链（ExistingWorkPolicy.APPEND_OR_REPLACE）——WorkManager 官方保证 unique
 * work 链内严格串行（并发=1，避免服务端扫描压力，HANDOVER_APP M4-5 冻结口径）。
 */
object UploadWorkSpec {

    /** unique 链名：全 App 唯一，串行即由它保证 */
    const val UNIQUE_WORK_NAME = "qm-upload-serial-queue"

    /** 全部上传任务的公共 tag（诊断/批量观察用） */
    const val TAG_UPLOAD = "qm-upload"

    /** 单任务 tag 前缀 + localId（WorkInfo 反查客户端任务标识） */
    const val TAG_ITEM_PREFIX = "qm-upload-item:"

    // ---- 输入 Data 键位（enqueue 写入，worker 读取） ----
    const val KEY_LOCAL_ID = "localId"
    const val KEY_URI = "uri"
    const val KEY_LIBRARY_ID = "libraryId"
    const val KEY_DIR = "dir"
    const val KEY_DISPLAY_NAME = "displayName"
    const val KEY_SIZE_BYTES = "sizeBytes"
    /** 编辑后的落库文件名（入队时已解析为实际值——非空恒写；旧在途载荷缺键回退 displayName） */
    const val KEY_UPLOAD_FILE_NAME = "uploadFileName"
    /** 自动挂靠作者 id（可空键：不带挂靠的任务不写） */
    const val KEY_ATTACH_AUTHOR_ID = "attachAuthorId"
    /** 自动挂靠来源词数组（可空键：不带挂靠的任务不写；mode=append 由 worker 侧固定） */
    const val KEY_ATTACH_SOURCES = "attachSources"
    /**
     * 目标库展示名（2026-09-28 上传归档文件夹功能：worker 归档分派用——归档根已设置且
     * 库名非空才走 <归档根>/<库名>/ 新路径）。空串不写键（挂靠键同款可空口径），
     * 旧在途载荷缺键反解回退空串。
     */
    const val KEY_LIBRARY_NAME = "libraryName"

    /**
     * 源文件已在归档根标志（2026-10-01 归档一键上传）：true = 条目来自归档文件夹一键上传，
     * worker 上传成功后跳过归档移动——源文件本就在归档根的库文件夹内，重复归档会对同
     * 一路径走 archiveToLibraryRoot 的同名同内容「删旧放新」路径（先删目标位再落新），
     * 源与目标是同一路径时等于删源。仅 true 时写键（可空键同款口径），旧在途载荷缺键
     * 反解回退 false（既有归档行为不变）。
     */
    const val KEY_ALREADY_ARCHIVED = "alreadyArchived"

    // ---- 过程/输出 Data 键位（worker setProgress / Result.outputData） ----
    const val KEY_PROGRESS_PERCENT = "progressPercent"
    const val KEY_FINAL_FILE_NAME = "finalFileName"
    const val KEY_ERROR_MESSAGE = "errorMessage"

    /**
     * 挂靠失败标志（挂靠批）：outputData 键。挂靠失败 = 文件已入库——终态必须走
     * **Result.success + 本标志**（failure 会经 iterativelyFailWorkAndDependents 级联杀链，
     * 与取消通道同一依据），队列映射在 State.SUCCEEDED 侧识别本标志落 UploadStatus.ATTACH_FAILED。
     */
    const val KEY_ATTACH_FAILED = "attachFailed"

    /**
     * 取消标志（批C 任务Q C-2，342 笔返工改走 success 载荷）：outputData 键。
     * 取消终态映射为 **Result.success + 本标志**——不落 failure（failure 会经
     * iterativelyFailWorkAndDependents 级联杀链，见 [outcomeToResult] 注释）；
     * 队列映射在 State.SUCCEEDED 与 State.FAILED 两侧都识别本标志落
     * UploadStatus.CANCELLED。
     */
    const val KEY_CANCELLED = "cancelled"

    /**
     * 重试上限（次）。第 1 次执行 + 最多 3 次重试 = 4 次尝试；超过即终局失败
     * （文案进 KEY_ERROR_MESSAGE）。退避节奏由请求侧 BackoffPolicy.EXPONENTIAL +
     * MIN_BACKOFF_MILLIS（10s，WorkManager 系统钳制下限）决定。
     */
    const val MAX_RETRIES = 3

    /**
     * 入队载荷：一个任务的全部执行参数（worker 侧反解见 [specFromInputData]）。
     * uploadFileName 为入队时已解析的实际落库名（UploadItem.effectiveUploadName，
     * 恒非空）；attachAuthorId/attachSources 可空 = 不带挂靠（来源挂靠以作者存在为前提，
     * UI 侧单源保证，worker 防御性兜底见 [UploadAttacher]）。
     */
    data class UploadRequestSpec(
        val localId: String,
        val uri: String,
        val libraryId: String,
        val dir: String,
        val displayName: String,
        val sizeBytes: Long,
        val uploadFileName: String,
        val attachAuthorId: String? = null,
        val attachSources: List<String>? = null,
        /** 目标库展示名（空串 = 未解析到/旧载荷，worker 归档分派回退 uploaded/） */
        val libraryName: String = "",
        /** 源文件已在归档根（一键上传条目）：true 时 worker 上传成功后跳过归档移动 */
        val alreadyArchived: Boolean = false,
    )

    fun itemToInputData(spec: UploadRequestSpec): Data = Data.Builder()
        .putString(KEY_LOCAL_ID, spec.localId)
        .putString(KEY_URI, spec.uri)
        .putString(KEY_LIBRARY_ID, spec.libraryId)
        .putString(KEY_DIR, spec.dir)
        .putString(KEY_DISPLAY_NAME, spec.displayName)
        .putLong(KEY_SIZE_BYTES, spec.sizeBytes)
        .putString(KEY_UPLOAD_FILE_NAME, spec.uploadFileName)
        .apply {
            // 可空键不写（缺键 = 不带挂靠/无库名；写 null 值会被 Data 拒绝）
            spec.attachAuthorId?.let { putString(KEY_ATTACH_AUTHOR_ID, it) }
            spec.attachSources?.takeIf { it.isNotEmpty() }?.let { putStringArray(KEY_ATTACH_SOURCES, it.toTypedArray()) }
            spec.libraryName.takeIf { it.isNotBlank() }?.let { putString(KEY_LIBRARY_NAME, it) }
            // 布尔标志只 true 时写键（缺键 = false，既有行为；与取消/挂靠失败输出键同口径）
            if (spec.alreadyArchived) putBoolean(KEY_ALREADY_ARCHIVED, true)
        }
        .build()

    /**
     * 反解入队载荷；缺任一必需键返回 null（worker 直接终局失败——防御性兜底）。
     * 兼容口径（冻结）：历史在途载荷可能缺新键——uploadFileName 缺失回退 displayName、
     * 挂靠键缺失 = 不带挂靠（纯上传）；更早挂靠批形态的 authorId/authorName/source
     * 等已删除键按 Data「未知键忽略」语义自然忽略。
     */
    fun specFromInputData(data: Data): UploadRequestSpec? {
        val localId = data.getString(KEY_LOCAL_ID) ?: return null
        val uri = data.getString(KEY_URI) ?: return null
        val libraryId = data.getString(KEY_LIBRARY_ID) ?: return null
        val dir = data.getString(KEY_DIR) ?: return null
        val displayName = data.getString(KEY_DISPLAY_NAME) ?: return null
        return UploadRequestSpec(
            localId = localId,
            uri = uri,
            libraryId = libraryId,
            dir = dir,
            displayName = displayName,
            sizeBytes = data.getLong(KEY_SIZE_BYTES, -1L),
            uploadFileName = UploadRules.sanitizeFileName(data.getString(KEY_UPLOAD_FILE_NAME)?.takeIf { it.isNotBlank() } ?: displayName),
            attachAuthorId = data.getString(KEY_ATTACH_AUTHOR_ID),
            attachSources = data.getStringArray(KEY_ATTACH_SOURCES)?.toList(),
            // 缺键回退空串（挂靠键同款旧载荷兼容口径：归档分派侧按空串回退 uploaded/）
            libraryName = data.getString(KEY_LIBRARY_NAME).orEmpty(),
            // 缺键回退 false（旧在途载荷无此键，维持既有归档行为）
            alreadyArchived = data.getBoolean(KEY_ALREADY_ARCHIVED, false),
        )
    }

    /**
     * 可重试失败的重试额度判定（worker 与状态机共用同一条件，单一事实源）。
     */
    fun isRetryExhausted(runAttemptCount: Int): Boolean = runAttemptCount >= MAX_RETRIES

    /**
     * 失败重试状态机（单测锁定；批C 342 笔返工修正取消分支）：
     * - 成功 → success（携带最终文件名）；
     * - 已入库但挂靠失败（挂靠批）→ **success 携带挂靠失败标志**——文件已入库，重试
     *   worker 等于重复上传文件（挂靠批失败语义红线）；failure 同样会级联杀链（同取消
     *   通道依据），故走 success 载荷：KEY_FINAL_FILE_NAME + KEY_ATTACH_FAILED +
     *   KEY_ERROR_MESSAGE（补挂指引文案），下游任务正常解锁执行；
     * - 永久失败（服务端 4xx）→ failure（透传文案，不重试）；
     * - 用户取消（C-2）→ **success 携带 CANCELLED 标志**——取消绝不能映射 failure：
     *   WorkManager 引擎对 failure 会走 iterativelyFailWorkAndDependents 级联，把
     *   unique 链上该任务之后的全部排队任务标 FAILED（outputData 为空、永不执行、
     *   UI 显示「失败：未知原因」），直接违反「单任务取消」冻结语义（reviewer
     *   反汇编 work-runtime 2.11.2 实证，批C 341 笔 P1）；success 不级联，下游
     *   正常解锁执行，取消语义由输出键 [KEY_CANCELLED] 承载；
     * - 可重试失败 → 未耗尽重试额度走 retry（官方退避重试语义），耗尽转 failure
     *   （重试耗尽的 failure 同样会级联杀链——既有行为，串行队列全链共享同一
     *   重试命运在「耗尽」场景可接受；单任务取消场景已由 success 通道避开）。
     */
    fun outcomeToResult(outcome: UploadOutcome, runAttemptCount: Int): ListenableWorker.Result =
        when (outcome) {
            is UploadOutcome.Success -> ListenableWorker.Result.success(
                workDataOf(KEY_FINAL_FILE_NAME to outcome.finalFileName),
            )

            is UploadOutcome.AttachFailed ->
                // 绝不 retry/failure：文件已入库（重试=重复上传），failure 会级联杀链——
                // 唯一终态 = success + 挂靠失败标志（UI 据此落 ATTACH_FAILED 专项状态）
                ListenableWorker.Result.success(
                    workDataOf(
                        KEY_FINAL_FILE_NAME to outcome.finalFileName,
                        KEY_ATTACH_FAILED to true,
                        KEY_ERROR_MESSAGE to attachFailedMessage(outcome.attachMessage),
                    ),
                )

            is UploadOutcome.Permanent -> ListenableWorker.Result.failure(
                workDataOf(KEY_ERROR_MESSAGE to outcome.serverMessage),
            )

            is UploadOutcome.Cancelled -> ListenableWorker.Result.success(
                workDataOf(
                    KEY_CANCELLED to true,
                    KEY_ERROR_MESSAGE to CANCELLED_MESSAGE,
                ),
            )

            is UploadOutcome.Retryable ->
                if (isRetryExhausted(runAttemptCount)) {
                    ListenableWorker.Result.failure(
                        workDataOf(
                            KEY_ERROR_MESSAGE to "重试 $MAX_RETRIES 次后仍失败：${outcome.reason}",
                        ),
                    )
                } else {
                    ListenableWorker.Result.retry()
                }
        }

    /** 用户取消的队列行文案（errorMessage 与通知共用，单一来源） */
    const val CANCELLED_MESSAGE = "已取消"

    /** 挂靠失败补挂指引（队列行 errorMessage 与完成通知共用，单一来源；含失败环节原因） */
    fun attachFailedMessage(attachMessage: String): String =
        "已入库但挂靠失败：$attachMessage；请到 作品详情→作者→编辑 补挂"

    /** 进度百分比（0..100 钳制；总长未知（<=0）恒 0，通知退化为不定进度文案）。 */
    fun progressPercent(bytesDone: Long, totalBytes: Long): Int {
        if (totalBytes <= 0) return 0
        val pct = (bytesDone * 100 / totalBytes).toInt()
        return pct.coerceIn(0, 100)
    }

    /** WorkInfo tag -> localId（非本队列 tag 返回 null）。 */
    fun localIdFromTag(tags: Set<String>): String? =
        tags.firstOrNull { it.startsWith(TAG_ITEM_PREFIX) }?.removePrefix(TAG_ITEM_PREFIX)
}
