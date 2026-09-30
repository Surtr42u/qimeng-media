package media.qimeng.app.core.data.upload

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.repository.BusinessApiFactory
import media.qimeng.app.core.model.UploadRules
import media.qimeng.app.core.network.di.UploadClient
import okhttp3.OkHttpClient

/**
 * 分片会话通道上传执行器（ADR-0028 Android 接入批）：POST /uploads 创建会话 → 每轮
 * GET 探测权威 offset → 按片 PATCH → complete，返回与直传完全同构的 [UploadOutcome]
 * （worker 的挂靠/归档后处理管线与终态状态机零改动复用）。分流判定在
 * [UploadRouting]（worker 入口）；既有直传（[AssetUploader]）分毫未动。
 *
 * 为什么不整类走生成 SDK：见 [OkHttpUploadSessionClient] KDoc（PATCH 只收 java.io.File
 * 与 content:// 源冲突、4xx 响应体文案被 SDK 异常丢弃、409 权威 offset 需自行解析）。
 * IO 全程 Dispatchers.IO；永不抛异常，失败全部收敛为 [UploadOutcome]。
 */
@Singleton
class ChunkedUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    @UploadClient private val okHttpClient: OkHttpClient,
    private val apiFactory: BusinessApiFactory,
    private val sessionRegistry: UploadSessionRegistry,
) {

    /**
     * 分片会话流上传一个文件到目标库的 [dir] 子目录（空 = 库根；dir 已入分片协议，
     * create 透传、服务端按与直传同规则校验后随会话落位）。会话 id 记入
     * [UploadSessionRegistry]：Retryable 保留（下轮探测续传），其余终局即忘。
     */
    suspend fun upload(
        localId: String,
        uri: String,
        fileName: String,
        sizeBytes: Long,
        libraryId: String,
        dir: String,
        isCancelled: () -> Boolean = { false },
        onProgress: AssetUploader.ProgressListener,
    ): UploadOutcome = withContext(Dispatchers.IO) {
        val outcome = ChunkedUploadSession(
            client = OkHttpUploadSessionClient(
                // 地址未就绪/不合法收敛为 Transient（直传同口径：非终局，重试窗口内可恢复）
                baseUrl = { runCatching { apiFactory.currentBaseUrl() }.getOrNull() },
                okHttpClient = okHttpClient,
            ),
            readChunk = { offset -> openChunk(uri, offset) },
            totalBytes = sizeBytes,
            libraryId = libraryId,
            fileName = fileName,
            dir = dir,
            isCancelled = isCancelled,
            previousSessionId = sessionRegistry.lookup(localId),
            onSessionCreated = { sessionRegistry.remember(localId, it) },
            onProgress = { done ->
                onProgress.onProgress(done, sizeBytes, UploadWorkSpec.progressPercent(done, sizeBytes))
            },
        ).run()
        if (outcome !is UploadOutcome.Retryable) sessionRegistry.forget(localId)
        outcome
    }

    /**
     * 打开定位到 [offset] 的源分片流：content:// 每片重开流并 skip 到位（SAF 流不可
     * 随机 seek 的稳妥口径；FileInputStream.skip 同样适用）。openSource 逻辑镜像
     * AssetUploader（直传文件冻结不动故就地镜像，双侧同步义务记档）。
     */
    private fun openChunk(uri: String, offset: Long): InputStream? {
        val stream = openSource(uri) ?: return null
        return try {
            var remaining = offset
            val fallback = ByteArray(SKIP_FALLBACK_BYTES)
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                    continue
                }
                // skip 不前进（底层流语义允许返回 0）：退化用 read 推进防死循环；
                // 流比声明短（文件被截断）按「暂不可读」口径收敛为可重试
                val read = stream.read(fallback, 0, minOf(fallback.size.toLong(), remaining).toInt())
                if (read == -1) throw IOException("源流长度不足（无法定位到 $offset）")
                remaining -= read
            }
            stream
        } catch (e: Exception) {
            runCatching { stream.close() }
            null
        }
    }

    /** 镜像 AssetUploader.openSource：绝对路径类走 FileInputStream，其余走 ContentResolver。 */
    private fun openSource(uri: String): InputStream? = try {
        if (UploadRules.isAbsoluteFilePath(uri)) {
            FileInputStream(uri)
        } else {
            context.contentResolver.openInputStream(Uri.parse(uri))
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        /** skip 不前进时的 read 兜底块大小 */
        const val SKIP_FALLBACK_BYTES = 64 * 1024
    }
}
