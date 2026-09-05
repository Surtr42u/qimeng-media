package media.qimeng.app.core.data.upload

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import media.qimeng.app.core.data.repository.BusinessApiFactory
import media.qimeng.app.core.model.UploadItem
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单文件流式上传执行器（M4-5 冻结架构：OkHttp 流式 `application/octet-stream` 直传）。
 *
 * 为什么不走生成 SDK：SDK 的 apiV1AssetsUploadPost 只收 `java.io.File`，而 SAF/系统分享
 * 给的是 content:// URI（无文件路径）——"先拷缓存再上传"会双倍占空间（HANDOVER_APP M4-5
 * 存疑停手项的权衡），故按冻结口径用注入的 OkHttpClient（带 AuthInterceptor 的同款客户端）
 * 流式直传。请求路径/参数与 api/openapi.yaml 的 `/api/v1/assets/upload` 双同步：
 * 协议侧改动须同步此处，反之亦然。
 *
 * 进度桥接说明：RequestBody.writeTo 运行在 OkHttp 阻塞线程，不能直接调挂起回调——
 * 用 CONFLATED channel 把"最新已传字节"单向推给消费侧协程（写端永不阻塞、不积压）。
 */
@Singleton
class AssetUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val apiFactory: BusinessApiFactory,
) {

    /** 进度回调（bytesDone/totalBytes 字节口径、percent 0..100；总长未知时 percent=0） */
    fun interface ProgressListener {
        suspend fun onProgress(bytesDone: Long, totalBytes: Long, percent: Int)
    }

    /**
     * 上传一个文件到 目标库+目录。服务端冲突自动重命名：成功返回最终文件名。
     * IO 全程 Dispatchers.IO；永不抛异常，失败全部收敛为 [UploadOutcome]。
     */
    suspend fun upload(
        item: UploadItem,
        libraryId: String,
        dir: String,
        onProgress: ProgressListener,
    ): UploadOutcome = withContext(Dispatchers.IO) {
        val base = apiFactory.currentBaseUrl().toHttpUrlOrNull()
            ?: return@withContext UploadOutcome.Retryable("服务端地址不合法")
        val url = base.newBuilder()
            .addPathSegments(UPLOAD_PATH)
            .addQueryParameter("libraryId", libraryId)
            .addQueryParameter("dir", dir)
            .addQueryParameter("filename", item.displayName)
            .build()

        val source = openSource(item.uri)
            ?: return@withContext UploadOutcome.Retryable(OPEN_FAILED_MESSAGE)

        coroutineScope {
            val updates = Channel<Long>(capacity = Channel.CONFLATED)
            val body = StreamingRequestBody(source, item.sizeBytes) { done -> updates.trySend(done) }
            val forwarder = launch {
                for (done in updates) {
                    val total = item.sizeBytes
                    onProgress.onProgress(done, total, UploadWorkSpec.progressPercent(done, total))
                }
            }
            val outcome = try {
                execute(Request.Builder().url(url).post(body).build())
            } finally {
                updates.close()
                source.closeQuietly()
            }
            forwarder.join()
            outcome
        }
    }

    private suspend fun execute(request: Request): UploadOutcome = withContext(Dispatchers.IO) {
        try {
            okHttpClient.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> UploadOutcome.Success(
                        UploadApiBodies.parseFinalFileName(bodyText) ?: FALLBACK_FINAL_NAME,
                    )

                    response.code in CLIENT_ERROR_MIN..CLIENT_ERROR_MAX ->
                        UploadOutcome.Permanent(
                            UploadApiBodies.serverErrorMessage(bodyText, response.code),
                        )

                    else -> UploadOutcome.Retryable("服务端错误 HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            // 断网/中断：交给 WorkManager 官方 retry 语义（退避 + 网络约束）
            UploadOutcome.Retryable("网络异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun openSource(uri: String): InputStream? = try {
        context.contentResolver.openInputStream(Uri.parse(uri))
    } catch (e: Exception) {
        // SecurityException（授权失效）/FileNotFoundException（文件被移走）都归为可重试：
        // 授权到重启前有效，队列重试窗口内通常可恢复；持续失败由重试上限兜底
        null
    }

    private fun InputStream.closeQuietly() {
        try {
            close()
        } catch (e: IOException) {
            // 关闭失败不影响上传结果，静默（流由进程兜底回收）
        }
    }

    private companion object {
        /**
         * 上传端点路径（openapi.yaml `/api/v1/assets/upload`；协议侧改动须同步此处，反之亦然）。
         */
        const val UPLOAD_PATH = "api/v1/assets/upload"

        /** 4xx 区间（透传文案只对客户端错误成立；5xx 走重试） */
        const val CLIENT_ERROR_MIN = 400
        const val CLIENT_ERROR_MAX = 499

        /** 本地流打不开的可重试文案（文件被移动/授权失效等瞬时态） */
        const val OPEN_FAILED_MESSAGE = "本地文件暂不可读（可能已被移动），稍后自动重试"

        /** 响应体缺 fileName 字段时的展示回退（协议 AssetDetail.fileName 理论恒在） */
        const val FALLBACK_FINAL_NAME = "（服务端未返回文件名）"
    }
}

/**
 * 上传响应体解析（internal：单测直接锁定透传/重命名文案逻辑）。
 * moshi 反射（SDK 生成物同款 KotlinJsonAdapterFactory，不另引 codegen）。
 */
internal object UploadApiBodies {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    /** 2xx 响应体的 AssetDetail.fileName（冲突重命名后的最终名）；解析失败回退 null。 */
    fun parseFinalFileName(body: String): String? = try {
        moshi.adapter(UploadResultDto::class.java).fromJson(body)?.fileName
    } catch (e: Exception) {
        null
    }

    /** 4xx 文案透传：服务端 Error{code,message}；非 JSON 体回退为带状态码的兜底中文。 */
    fun serverErrorMessage(body: String, httpCode: Int): String = try {
        val err = moshi.adapter(ApiErrorDto::class.java).fromJson(body)
        val text = listOfNotNull(err?.code, err?.message).joinToString(" ").trim()
        text.ifEmpty { defaultClientError(httpCode) }
    } catch (e: Exception) {
        defaultClientError(httpCode)
    }

    private fun defaultClientError(httpCode: Int): String = "服务端拒绝（HTTP $httpCode）"

    /** 2xx 响应只关心的字段（AssetDetail 子集；字段名与协议一致） */
    private class UploadResultDto(val fileName: String? = null)

    /** 4xx 响应体（协议 components.schemas.Error） */
    private class ApiErrorDto(val code: String? = null, val message: String? = null)
}

/** 请求体媒体类型（协议 requestBody 声明 application/octet-stream） */
private val OCTET_STREAM: MediaType = "application/octet-stream".toMediaType()

/**
 * 流式请求体：从 content:// 输入流分块写向网络，不整文件落内存/缓存。
 * contentLength 来自 describe 阶段（未知传 -1 → OkHttp 走 chunked）。
 */
private class StreamingRequestBody(
    private val source: InputStream,
    private val totalBytes: Long,
    private val onChunk: (bytesDone: Long) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = OCTET_STREAM

    override fun contentLength(): Long = totalBytes

    override fun writeTo(sink: BufferedSink) {
        val buffer = ByteArray(CHUNK_BYTES)
        var done = 0L
        while (true) {
            val read = source.read(buffer)
            if (read == -1) break
            sink.write(buffer, 0, read)
            done += read
            onChunk(done)
        }
        sink.flush()
    }

    private companion object {
        /** 64KB 分块：网络写入的常规粒度（过小系统调用过多、过大无收益） */
        const val CHUNK_BYTES = 64 * 1024
    }
}
