package media.qimeng.app.core.data.upload

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.repository.InboxFileStore
import media.qimeng.app.core.data.repository.StagingRepository
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadRules

/**
 * 上传 Worker（M4-5 串行队列的执行端）：
 * - 执行前升级前台服务（dataSync 类型，API 34+ 强制声明类型），进度走通知；
 * - 上传中节流回写 WorkManager progress（UI 观察队列状态用）；
 * - 通道分流（ADR-0028）：≥16MB 走 [ChunkedUploader] 分片会话流
 *   （弱网按服务端权威 offset 续传；dir 已入分片协议，子目录目标同样续传），
 *   其余走 [AssetUploader] 既有整文件直传；
 * - 201 之后按入队载荷执行自动挂靠序列（[UploadAttacher]：先 authors 后 sources append）；
 * - 终局判定全部收敛在 [UploadWorkSpec.outcomeToResult]（纯函数，单测锁定）。
 *
 * 前台服务升级失败（系统后台限制等）不阻断上传：catch 后继续以普通后台任务执行
 * （WorkManager 官方对 setForeground 失败的建议姿势——降级不失败）。
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val uploader: AssetUploader,
    private val chunkedUploader: ChunkedUploader,
    private val attacher: UploadAttacher,
    private val cancelRegistry: UploadCancelRegistry,
    private val inboxFileStore: InboxFileStore,
    private val stagingRepository: StagingRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val spec = UploadWorkSpec.specFromInputData(inputData)
        if (spec == null) {
            Log.e(LOG_TAG, "任务载荷缺失，终局失败")
            return Result.failure(workDataOf(UploadWorkSpec.KEY_ERROR_MESSAGE to "任务数据异常（载荷缺失）"))
        }

        // C-2 用户取消：排队中任务被标记取消时，轮到执行即直接落取消终态（不发起上传）。
        // 不调 WorkManager cancelWorkById 的原因见 [UploadCancelRegistry]（链级联取消违单任务语义）。
        if (cancelRegistry.isCancelled(spec.localId)) {
            Log.i(LOG_TAG, "cancelled-before-start file=${spec.displayName}")
            cancelRegistry.consume(spec.localId)
            return UploadWorkSpec.outcomeToResult(UploadOutcome.Cancelled, runAttemptCount)
        }

        Log.i(
            LOG_TAG,
            "start attempt=${runAttemptCount + 1} file=${spec.displayName} lib=${spec.libraryId} dir=${spec.dir}",
        )
        setForegroundSafely(spec.displayName)

        // 进度回调（直传/分片共用同一映射：setProgress + 前台通知 + 取证日志）。
        // 分片通道按片粒度回调（每片完成推进一次），UI 语义粗粒度化（ADR-0028 任务书记档）。
        val onProgress = AssetUploader.ProgressListener { done, total, percent ->
            setProgress(
                workDataOf(
                    UploadWorkSpec.KEY_PROGRESS_PERCENT to percent,
                    UploadWorkSpec.KEY_DISPLAY_NAME to spec.displayName,
                ),
            )
            updateForegroundNotification(spec.displayName, percent)
            if (percent % LOG_PROGRESS_STEP == 0) {
                Log.d(LOG_TAG, "progress file=${spec.displayName} $done/$total ($percent%)")
            }
        }
        // ADR-0028 阈值分流：大文件（≥16MB）走分片会话流——弱网中断按服务端
        // 权威 offset 续传，不再整文件归零；dir 已入分片协议（create 透传、
        // 服务端按直传同规则落位子目录），子目录目标不再被迫直传。complete
        // 返回与直传同构的资产详情，下方挂靠/归档后处理管线与终态状态机原样
        // 复用。小文件维持既有直传（路径行为一字节不变，任务书红线）。
        val chunked = UploadRouting.useChunkedSession(spec.sizeBytes)
        if (chunked) {
            Log.i(LOG_TAG, "chunked channel file=${spec.displayName} size=${spec.sizeBytes} dir=${spec.dir}")
        }
        val outcome = resolveOutcome(
            uploadOutcome = if (chunked) {
                chunkedUploader.upload(
                    localId = spec.localId,
                    uri = spec.uri,
                    fileName = spec.uploadFileName,
                    sizeBytes = spec.sizeBytes,
                    libraryId = spec.libraryId,
                    dir = spec.dir,
                    isCancelled = { cancelRegistry.isCancelled(spec.localId) },
                    onProgress,
                )
            } else {
                uploader.upload(
                    UploadItem(
                        uri = spec.uri,
                        displayName = spec.displayName,
                        sizeBytes = spec.sizeBytes,
                        uploadFileName = spec.uploadFileName,
                    ),
                    spec.libraryId,
                    spec.dir,
                    isCancelled = { cancelRegistry.isCancelled(spec.localId) },
                    onProgress,
                )
            },
            spec = spec,
        )
        cancelRegistry.consume(spec.localId)

        val result = UploadWorkSpec.outcomeToResult(outcome, runAttemptCount)
        when (outcome) {
            is UploadOutcome.Success -> {
                Log.i(LOG_TAG, "success file=${spec.displayName} final=${outcome.finalFileName}")
                notifyDone(
                    spec.localId,
                    spec.displayName,
                    "上传完成：${outcome.finalFileName}" + archiveNote(spec),
                )
            }

            is UploadOutcome.AttachFailed -> {
                // 挂靠批失败语义：文件已入库，绝不重试（重试=重复上传）；success+标志终态
                Log.w(LOG_TAG, "attach-failed file=${spec.displayName} final=${outcome.finalFileName}")
                notifyDone(
                    spec.localId,
                    spec.displayName,
                    "上传完成：${outcome.finalFileName}，" +
                        UploadWorkSpec.attachFailedMessage(outcome.attachMessage) + archiveNote(spec),
                )
            }

            is UploadOutcome.Permanent -> {
                Log.w(LOG_TAG, "rejected file=${spec.displayName} msg=${outcome.serverMessage}")
                notifyDone(spec.localId, spec.displayName, "上传失败：${outcome.serverMessage}")
            }

            is UploadOutcome.Cancelled -> {
                // C-2 取消：状态行翻「已取消」+ 完成通知同步；前台进度通知随 worker
                // 正常结束由 WorkManager 自动撤销。终态映射为 success+取消标志
                // （outcomeToResult）——failure 会级联杀链，342 笔返工修正
                Log.i(LOG_TAG, "cancelled file=${spec.displayName}")
                notifyDone(spec.localId, spec.displayName, "上传已取消：${spec.displayName}")
            }

            is UploadOutcome.Retryable -> {
                // 终局失败（重试耗尽）时才有完成通知；中途 retry 由退避调度自动续跑
                // （判定与 outcomeToResult 共用 isRetryExhausted，不触碰受限的 Result.Failure 类型）
                if (UploadWorkSpec.isRetryExhausted(runAttemptCount)) {
                    Log.w(LOG_TAG, "exhausted file=${spec.displayName} reason=${outcome.reason}")
                    notifyDone(
                        spec.localId,
                        spec.displayName,
                        "上传失败：重试 ${UploadWorkSpec.MAX_RETRIES} 次后仍失败",
                    )
                } else {
                    Log.w(
                        LOG_TAG,
                        "retry scheduled file=${spec.displayName} attempt=${runAttemptCount + 1} reason=${outcome.reason}",
                    )
                }
            }
        }
        return result
    }

    /**
     * 上传终局 → 本次执行终局：上传成功且载荷带挂靠参数时执行挂靠序列（先 authors 后
     * sources，见 [UploadAttacher]），失败收敛为 [UploadOutcome.AttachFailed]；上传非成功
     * 或无挂靠参数时原样透传（既有重试/取消/永久失败路径分毫不动）。
     *
     * 为什么挂靠失败不走 Result.retry：本函数返回后 outcomeToResult 对 AttachFailed 落
     * success+标志——worker 级重试会连文件一起重传（挂靠批失败语义红线）；挂靠调用本身
     * 一次机会，补挂由用户到作品编辑页完成。进程死亡窗口（201 落库后、挂靠完成前）由
     * WorkManager 重跑任务 + 服务端冲突自动重命名兜底，不引入主动重试路径。
     */
    private suspend fun resolveOutcome(
        uploadOutcome: UploadOutcome,
        spec: UploadWorkSpec.UploadRequestSpec,
    ): UploadOutcome {
        val needsAttach = spec.attachAuthorId != null || !spec.attachSources.isNullOrEmpty()
        if (uploadOutcome !is UploadOutcome.Success || !needsAttach) return uploadOutcome
        val assetId = uploadOutcome.assetId
            ?: return UploadOutcome.AttachFailed(uploadOutcome.finalFileName, ATTACH_NO_ASSET_ID)
        return when (val attach = attacher.attach(assetId, spec.attachAuthorId, spec.attachSources)) {
            is AttachOutcome.Done -> uploadOutcome
            is AttachOutcome.Failed -> UploadOutcome.AttachFailed(uploadOutcome.finalFileName, attach.message)
        }
    }

    /**
     * 源文件归档分派（2026-09-25 暂存区重做上传成功归档；2026-09-28 上传归档文件夹
     * 功能改三分派）：文件已入库（上传成功/挂靠失败两态皆是——文件本体已 201 落库）
     * 且源为绝对路径类时执行，相册（content://）来源无文件路径可移、任何分支都不动。
     * - a) 归档文件夹已设置 且 载荷库名非空 → 移入 <归档文件夹>/<库名>/<文件名>
     *   （用户手动复制同步到电脑的自留归档区；收件箱与文件浏览器两种路径来源同权）；
     * - b) 未设置归档根：仅收件箱来源维持 uploaded/ 旧行为（源父目录 == 收件箱目录，
     *   判定见 [isInboxSource]），其它路径来源不动源文件（浏览文件选中的 Download/
     *   私人文件夹等不属于收件箱，擅自在其内建 uploaded/ 子目录移走文件超出旧行为）；
     * - c) 已设置但载荷库名为空（旧在途载荷缺键/入队时解析不到库名）→ 回退 b 语义：
     *   收件箱来源仍归 uploaded/（缺名不丢文件），其它路径来源同样不动；
     * - 0) 载荷 alreadyArchived=true（2026-10-01 归档一键上传条目）→ 整体跳过归档：
     *   源文件本就在归档根的库文件夹内（风险说明见方法体注释）。
     * 归档路径读取：doWork 本身即 suspend 协程，直接 archivePath/inboxPath.first() 取
     * 首快照，无需 runBlocking（Worker 无 DataStore 常驻 Flow 场景，单值即所需）。
     * 文件操作段（copy/内容比对可达 GB 级视频）切 [Dispatchers.IO]：CoroutineWorker
     * 默认跑 Default 调度器，在其上阻塞既拖住完成通知也占满 CPU 线程池。
     * 移动失败不阻断上传完成，只记日志与完成通知提示（与原 uploaded/ 归档同口径）。
     */
    private suspend fun archiveNote(spec: UploadWorkSpec.UploadRequestSpec): String {
        // 一键重传条目（2026-10-01 归档一键上传，载荷 alreadyArchived=true）：源文件本就在
        // 归档根的库文件夹内，整体跳过归档移动——若照常归档，archiveToLibraryRoot 会把
        // 「源 == 目标同一路径」判成同名同内容走「删旧放新」（先删目标位再落新），删除的
        // 就是源文件本身，改名/copy 再失败即丢文件；跳过后上传/挂靠/清理行为全不变。
        if (spec.alreadyArchived) return ""
        if (!UploadRules.isAbsoluteFilePath(spec.uri)) return ""
        val archiveRoot = stagingRepository.archivePath.first()
        val useArchiveRoot = archiveRoot != null && spec.libraryName.isNotBlank()
        val sourceFile = File(spec.uri)
        val archived = withContext(Dispatchers.IO) {
            when {
                useArchiveRoot -> inboxFileStore.archiveToLibraryRoot(
                    requireNotNull(archiveRoot),
                    spec.libraryName,
                    sourceFile,
                )
                isInboxSource(sourceFile) -> inboxFileStore.archiveToUploaded(spec.uri)
                // 非收件箱来源：不动源文件、返回空注记（语义见 KDoc b 分支）
                else -> null
            }
        }
        if (archived == null) return ""
        if (!archived) {
            Log.w(LOG_TAG, "source-archive-failed file=${spec.displayName} src=${spec.uri} toArchiveRoot=$useArchiveRoot")
        }
        return when {
            archived -> ""
            useArchiveRoot -> ARCHIVE_ROOT_FAILED_NOTE
            else -> ARCHIVE_FAILED_NOTE
        }
    }

    /**
     * 收件箱来源判定：源文件父目录与收件箱目录 canonicalFile 精确相等。scanInbox 只扫
     * 收件箱一级文件，收件箱来源的父目录必为收件箱本身；文件浏览器选中的任意目录
     * （Download、私人文件夹等）不满足此判定。canonical 抛异常按非收件箱处理——
     * 宁可不动作也不误搬。收件箱未设置（null）同理恒非收件箱来源。
     */
    private suspend fun isInboxSource(sourceFile: File): Boolean {
        val inboxDir = stagingRepository.inboxPath.first()?.let(::File) ?: return false
        return runCatching {
            sourceFile.parentFile?.canonicalFile == inboxDir.canonicalFile
        }.getOrDefault(false)
    }

    /** 前台服务升级：API 34+ 必须带 dataSync 类型；任何失败降级为普通后台任务。 */
    private suspend fun setForegroundSafely(title: String) {
        try {
            UploadNotifications.ensureChannel(applicationContext)
            val notification = UploadNotifications.progress(applicationContext, title, 0)
            val info = if (Build.VERSION.SDK_INT >= 34) {
                ForegroundInfo(
                    UploadNotifications.FOREGROUND_NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                ForegroundInfo(UploadNotifications.FOREGROUND_NOTIF_ID, notification)
            }
            setForeground(info)
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "前台服务升级失败，降级为后台任务继续上传", t)
        }
    }

    /** 前台通知进度刷新（未获通知权限/构建失败一律静默跳过，不影响上传） */
    private fun updateForegroundNotification(title: String, percent: Int) {
        if (!UploadNotifications.canNotify(applicationContext)) return
        try {
            val manager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(
                UploadNotifications.FOREGROUND_NOTIF_ID,
                UploadNotifications.progress(applicationContext, title, percent),
            )
        } catch (t: Throwable) {
            Log.d(LOG_TAG, "通知刷新失败（忽略）", t)
        }
    }

    private fun notifyDone(localId: String, title: String, text: String) {
        if (!UploadNotifications.canNotify(applicationContext)) return
        try {
            val manager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(
                UploadNotifications.doneNotifId(localId),
                UploadNotifications.done(applicationContext, title, text),
            )
        } catch (t: Throwable) {
            Log.d(LOG_TAG, "完成通知失败（忽略）", t)
        }
    }

    companion object {
        /** logcat 证据标签（grep 'QimengUpload' 得上传时间线，文本证据协议 §4.7） */
        const val LOG_TAG = "QimengUpload"

        /** 进度日志步长（%）：全量打点刷屏，25% 粒度足够串行时间线举证 */
        private const val LOG_PROGRESS_STEP = 25

        /** 201 响应体缺 id 时的挂靠失败原因（协议 AssetDetail.id 理论恒在，防御性兜底） */
        private const val ATTACH_NO_ASSET_ID = "服务端未返回资产 id，无法挂靠"

        /** 收件箱源文件归档失败的完成通知提示（不阻断上传完成口径；b/c 回退路径用） */
        private const val ARCHIVE_FAILED_NOTE = "（收件箱归档失败：源文件保留在收件箱）"

        /** 归档文件夹路径归档失败的完成通知提示（a 新路径用；不阻断上传完成口径） */
        private const val ARCHIVE_ROOT_FAILED_NOTE = "（归档到归档文件夹失败：源文件保留在原位置）"
    }
}
