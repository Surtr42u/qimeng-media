package media.qimeng.app.core.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.upload.UploadWorker
import media.qimeng.app.core.data.upload.UploadWorkSpec
import media.qimeng.app.core.model.DirNode
import media.qimeng.app.core.model.LibraryChoice
import media.qimeng.app.core.model.UploadItem
import media.qimeng.app.core.model.UploadLimits
import media.qimeng.app.core.model.UploadQueueEntry
import media.qimeng.app.core.model.UploadStatus
import media.qimeng.sdk.models.ApiV1DirsPostRequest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [UploadRepository] 的实现（M4-5）：目录/配置/库列表走生成 SDK，队列走 WorkManager
 * 串行 unique 链（并发=1 冻结口径，串行性由 [UploadWorkSpec.UNIQUE_WORK_NAME] 保证）。
 * 出网请求打 logcat（文本证据协议 HANDOVER_APP §4.7）。
 */
@Singleton
class SdkUploadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiFactory: BusinessApiFactory,
) : UploadRepository {

    /** localId -> 展示名（本会话入队记忆；进程重启后由 worker 进度数据补齐） */
    private val knownNames = LinkedHashMap<String, String>()

    /** 入队顺序（WorkInfo 流无顺序保证，按此排序还原队列时间线） */
    private val queueOrder = mutableListOf<String>()

    override suspend fun libraries(): List<LibraryChoice> {
        Log.d(SdkMediaRepository.LOG_TAG, "GET /libraries")
        val libs = withContext(Dispatchers.IO) { apiFactory.create().apiV1LibrariesGet() }
        return libs.mapNotNull { lib ->
            val id = lib.id ?: return@mapNotNull null
            LibraryChoice(id = id, name = lib.name ?: id)
        }
    }

    override suspend fun dirTree(libraryId: String): DirNode {
        Log.d(SdkMediaRepository.LOG_TAG, "GET /dirs libraryId=$libraryId")
        val tree = withContext(Dispatchers.IO) { apiFactory.create().apiV1DirsGet(libraryId) }
        return tree.toDirNode()
    }

    override suspend fun createDir(libraryId: String, path: String) {
        Log.d(SdkMediaRepository.LOG_TAG, "POST /dirs libraryId=$libraryId path=$path")
        withContext(Dispatchers.IO) {
            apiFactory.create().apiV1DirsPost(ApiV1DirsPostRequest(path = path, libraryId = libraryId))
        }
    }

    override suspend fun uploadLimits(): UploadLimits {
        Log.d(SdkMediaRepository.LOG_TAG, "GET /config")
        val config = withContext(Dispatchers.IO) { apiFactory.create().apiV1ConfigGet() }
        return UploadLimits(
            maxBytesMb = config.upload.maxBytesMb.toLong(),
            autoAccept = config.upload.autoAccept,
        )
    }

    override suspend fun describe(uris: List<String>): List<UploadItem> = withContext(Dispatchers.IO) {
        uris.map { uriString -> describeOne(Uri.parse(uriString)) }
    }

    override fun enqueue(items: List<UploadItem>, libraryId: String, dir: String): List<QueuedUpload> {
        val workManager = WorkManager.getInstance(context)
        return items.map { item ->
            val localId = UUID.randomUUID().toString()
            knownNames[localId] = item.displayName
            queueOrder.add(localId)
            val spec = UploadWorkSpec.UploadRequestSpec(
                localId = localId,
                uri = item.uri,
                libraryId = libraryId,
                dir = dir,
                displayName = item.displayName,
                sizeBytes = item.sizeBytes,
            )
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(UploadWorkSpec.itemToInputData(spec))
                .addTag(UploadWorkSpec.TAG_UPLOAD)
                .addTag(UploadWorkSpec.TAG_ITEM_PREFIX + localId)
                // 断网/中断恢复走 WorkManager 官方 retry 语义（冻结口径）：任务立即执行，
                // 网络不通时上传抛 IOException → Result.retry() 指数退避，恢复后续跑。
                // 不加 CONNECTED 约束：约束依赖系统网络 VALIDATED 判定，弱网/隔离网段下
                // 会让任务悬置不跑（2026-09-06 模拟器实测），retry 路径覆盖同一恢复语义。
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .build()
            // 同一 unique 链尾追加 = 严格串行（WorkManager 官方语义）；APPEND_OR_REPLACE
            // 让重试耗尽的死链不阻塞后续入队（新链顶替旧链）
            workManager
                .beginUniqueWork(UploadWorkSpec.UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
                .enqueue()
            Log.i(
                SdkMediaRepository.LOG_TAG,
                "enqueue upload localId=$localId file=${item.displayName} dir=$dir",
            )
            QueuedUpload(localId = localId, displayName = item.displayName)
        }
    }

    override fun queueUpdates(): Flow<List<UploadQueueEntry>> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(UploadWorkSpec.UNIQUE_WORK_NAME)
            .map { infos ->
                infos.mapNotNull(::toEntry).sortedBy { entry ->
                    queueOrder.indexOf(entry.localId).let { if (it < 0) Int.MAX_VALUE else it }
                }
            }

    private fun toEntry(info: WorkInfo): UploadQueueEntry? {
        val localId = UploadWorkSpec.localIdFromTag(info.tags) ?: return null
        val displayName = info.progress.getString(UploadWorkSpec.KEY_DISPLAY_NAME)
            ?: knownNames[localId]
            ?: "上传任务"
        val status = when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> UploadStatus.QUEUED
            WorkInfo.State.RUNNING -> UploadStatus.UPLOADING
            WorkInfo.State.SUCCEEDED -> UploadStatus.SUCCEEDED
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> UploadStatus.FAILED
        }
        return UploadQueueEntry(
            localId = localId,
            displayName = displayName,
            status = status,
            progressPercent = info.progress.getInt(UploadWorkSpec.KEY_PROGRESS_PERCENT, -1)
                .takeIf { it >= 0 },
            finalFileName = info.outputData.getString(UploadWorkSpec.KEY_FINAL_FILE_NAME),
            errorMessage = info.outputData.getString(UploadWorkSpec.KEY_ERROR_MESSAGE),
        )
    }

    private suspend fun describeOne(uri: Uri): UploadItem {
        var displayName = "未命名"
        var sizeBytes = -1L
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) cursor.getString(nameIdx)?.let { displayName = it }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) sizeBytes = cursor.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            // 元数据读不到不拦入队：displayName 兜底、size -1 交服务端 413 兜底
            Log.w(SdkMediaRepository.LOG_TAG, "describe 失败 uri=$uri", e)
        }
        return UploadItem(uri = uri.toString(), displayName = displayName, sizeBytes = sizeBytes)
    }
}

/** SDK DirTree -> 领域 DirNode（协议口径：path '/' 分隔、根为空串、fileCount 直接子文件数） */
private fun media.qimeng.sdk.models.DirTree.toDirNode(): DirNode = DirNode(
    path = path.orEmpty(),
    fileCount = fileCount ?: 0,
    children = dirs.orEmpty().map { it.toDirNode() },
)
