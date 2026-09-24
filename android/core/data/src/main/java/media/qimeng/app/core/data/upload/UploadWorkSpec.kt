package media.qimeng.app.core.data.upload

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.workDataOf

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

    // ---- 挂靠可选键位（REQ §3.1：authorId/authorName 互斥只写其一；缺键 = 不挂靠，向后兼容） ----
    const val KEY_AUTHOR_ID = "authorId"
    const val KEY_AUTHOR_NAME = "authorName"
    const val KEY_SOURCES = "sources"

    // ---- 过程/输出 Data 键位（worker setProgress / Result.outputData） ----
    const val KEY_PROGRESS_PERCENT = "progressPercent"
    const val KEY_FINAL_FILE_NAME = "finalFileName"
    const val KEY_ERROR_MESSAGE = "errorMessage"

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

    /** 入队载荷：一个任务的全部执行参数（worker 侧反解见 [specFromInputData]）。 */
    data class UploadRequestSpec(
        val localId: String,
        val uri: String,
        val libraryId: String,
        val dir: String,
        val displayName: String,
        val sizeBytes: Long,
        /**
         * 挂靠目标=已有作者 ID（GET /authors/suggest 点选）；与 [authorName] 互斥。
         * null = 不挂靠（留空上传行为与旧版完全一致，REQ §3.1 留空口径）。
         */
        val authorId: String? = null,
        /** 挂靠目标=新建作者显示名（联想无结果回车新建）；与 [authorId] 互斥 */
        val authorName: String? = null,
        /** 作者来源词多选（仅挂靠时随批并入；空列表 = 不改动该作者来源） */
        val sources: List<String> = emptyList(),
    )

    fun itemToInputData(spec: UploadRequestSpec): Data = Data.Builder()
        .putString(KEY_LOCAL_ID, spec.localId)
        .putString(KEY_URI, spec.uri)
        .putString(KEY_LIBRARY_ID, spec.libraryId)
        .putString(KEY_DIR, spec.dir)
        .putString(KEY_DISPLAY_NAME, spec.displayName)
        .putLong(KEY_SIZE_BYTES, spec.sizeBytes)
        // 可选键只在有值时写（缺键即反解为 null/空，与旧载荷双向兼容）
        .apply {
            if (spec.authorId != null) putString(KEY_AUTHOR_ID, spec.authorId)
            if (spec.authorName != null) putString(KEY_AUTHOR_NAME, spec.authorName)
            if (spec.sources.isNotEmpty()) putStringArray(KEY_SOURCES, spec.sources.toTypedArray())
        }
        .build()

    /** 反解入队载荷；缺任一必需键返回 null（worker 直接终局失败——防御性兜底）。 */
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
            authorId = data.getString(KEY_AUTHOR_ID),
            authorName = data.getString(KEY_AUTHOR_NAME),
            sources = data.getStringArray(KEY_SOURCES)?.toList().orEmpty(),
        )
    }

    /**
     * 可重试失败的重试额度判定（worker 与状态机共用同一条件，单一事实源）。
     */
    fun isRetryExhausted(runAttemptCount: Int): Boolean = runAttemptCount >= MAX_RETRIES

    /**
     * 失败重试状态机（单测锁定；批C 342 笔返工修正取消分支）：
     * - 成功 → success（携带最终文件名）；
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
