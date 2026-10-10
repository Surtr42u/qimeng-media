package media.qimeng.app.core.data.upload

import android.util.Log
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/**
 * 会话通道请求/响应体编解码（moshi 反射，SDK 生成物同款 KotlinJsonAdapterFactory，
 * 不另引 codegen）。字段名与 api/openapi.yaml 的 CreateUploadRequest/UploadSession
 * 双同步：协议侧改动须同步此处，反之亦然。
 */
internal object UploadSessionBodies {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    /**
     * 创建会话请求体（POST /api/v1/uploads）。contentType 协议可选且服务端不信任
     * 声明（魔数嗅探为准），客户端不发（省去 MIME 推断链）；dir 与直传同语义
     * （库内相对路径，空 = 库根），恒发送（空串 = 库根，服务端 create 时校验）。
     */
    fun encodeCreate(libraryId: String, fileName: String, dir: String, sizeBytes: Long): String =
        moshi.adapter(CreateUploadDto::class.java).toJson(
            CreateUploadDto(libraryId = libraryId, fileName = fileName, dir = dir, size = sizeBytes),
        )

    /** UploadSession 关心字段（POST/GET 2xx 与 PATCH 409 响应体同构） */
    data class SessionInfo(val id: String?, val offset: Long?, val size: Long?)

    /** 解析失败回退 null（调用方收敛为 Transient——响应异常按可重试处理） */
    fun parseSession(body: String): SessionInfo? = try {
        moshi.adapter(SessionDto::class.java).fromJson(body)
            ?.let { SessionInfo(id = it.id, offset = it.offset, size = it.size) }
    } catch (e: Exception) {
        null
    }

    /** 字段名与协议一致（size 为 JSON 键，非 Kotlin 保留字；dir 可选但恒发空串=库根） */
    internal class CreateUploadDto(
        @Json(name = "libraryId") val libraryId: String,
        @Json(name = "fileName") val fileName: String,
        @Json(name = "dir") val dir: String,
        @Json(name = "size") val size: Long,
    )

    internal class SessionDto(
        @Json(name = "id") val id: String?,
        @Json(name = "offset") val offset: Long?,
        @Json(name = "size") val size: Long?,
    )
}

/**
 * 断点续传会话通道的 OkHttp 直连实现。沿用直传冻结架构的「不走生成 SDK」先例
 * （AssetUploader KDoc 同源论证），且在本通道三条理由全部成立：
 * ① SDK 的 apiV1UploadsIdPatch 只收 java.io.File，本端主力源是 content:// URI
 *    （无文件路径），「分片先拷缓存文件」对 GB 级文件是持续双倍 IO；
 * ② SDK 4xx 异常只带 HTTP 状态行文案（Error{code,message} 响应体被生成客户端
 *    丢弃），而会话通道 4xx 必须与直传同口径透传服务端原因（条目标错带原因）；
 * ③ PATCH 409 的权威 offset 在响应体 UploadSession 里，必须自行解析。
 * 客户端用注入的 @UploadClient（带 AuthInterceptor + 60s 读超时，大分片落盘不被
 * 默认 10s 掐断）。请求路径/参数与 api/openapi.yaml /api/v1/uploads* 三模板双同步：
 * 协议侧改动须同步此处，反之亦然。
 */
internal class OkHttpUploadSessionClient(
    /** 当前服务端根地址供给源（null = 未登录/地址未就绪 → Transient）；唯一来源 BusinessApiFactory */
    private val baseUrl: () -> String?,
    private val okHttpClient: OkHttpClient,
) : UploadSessionClient {

    override suspend fun create(libraryId: String, fileName: String, dir: String, sizeBytes: Long): SessionCall<String> {
        val base = resolveBaseUrl() ?: return SessionCall.Transient(ADDRESS_UNREADY_MESSAGE)
        val request = Request.Builder()
            .url(sessionUrl(base))
            .post(UploadSessionBodies.encodeCreate(libraryId, fileName, dir, sizeBytes).toRequestBody(JSON))
            .build()
        return send(request) { response, bodyText ->
            when {
                response.isSuccessful -> UploadSessionBodies.parseSession(bodyText)?.id
                    ?.let { SessionCall.Ok(it) }
                    ?: SessionCall.Transient("创建会话响应体不可解析")
                else -> statusCall(response.code, bodyText)
            }
        }
    }

    override suspend fun probe(sessionId: String): SessionCall<Long> {
        val base = resolveBaseUrl() ?: return SessionCall.Transient(ADDRESS_UNREADY_MESSAGE)
        val request = Request.Builder()
            .url(sessionUrl(base, sessionId))
            .get()
            .build()
        return send(request) { response, bodyText ->
            when {
                response.isSuccessful -> UploadSessionBodies.parseSession(bodyText)?.offset
                    ?.let { SessionCall.Ok(it) }
                    ?: SessionCall.Transient("断点探测响应体不可解析")
                else -> statusCall(response.code, bodyText)
            }
        }
    }

    override suspend fun patch(
        sessionId: String,
        offset: Long,
        source: InputStream,
        length: Long,
        isCancelled: () -> Boolean,
    ): SessionCall<Long> {
        val base = resolveBaseUrl() ?: return SessionCall.Transient(ADDRESS_UNREADY_MESSAGE)
        val request = Request.Builder()
            .url(
                sessionUrl(base, sessionId)
                    .newBuilder()
                    .addQueryParameter(OFFSET_QUERY, offset.toString())
                    .build(),
            )
            .patch(ChunkRequestBody(source, length, isCancelled))
            .build()
        return send(request) { response, bodyText ->
            when {
                response.isSuccessful -> UploadSessionBodies.parseSession(bodyText)?.offset
                    ?.let { SessionCall.Ok(it) }
                    ?: SessionCall.Transient("分片响应体不可解析")

                // 409 响应体即权威 UploadSession：取 offset 供编排器立即重同步（协议语义）
                response.code == HTTP_CONFLICT -> SessionCall.ClientError(
                    code = HTTP_CONFLICT,
                    message = CHUNK_STALE_MESSAGE,
                    authorityOffset = UploadSessionBodies.parseSession(bodyText)?.offset,
                )

                else -> statusCall(response.code, bodyText)
            }
        }
    }

    override suspend fun complete(sessionId: String): SessionCall<UploadApiBodies.UploadResult> {
        val base = resolveBaseUrl() ?: return SessionCall.Transient(ADDRESS_UNREADY_MESSAGE)
        val request = Request.Builder()
            .url(sessionUrl(base, sessionId, COMPLETE_SEGMENT))
            .post(EMPTY_BODY)
            .build()
        return send(request) { response, bodyText ->
            when {
                response.isSuccessful -> UploadApiBodies.parseUploadResult(bodyText)
                    ?.let { SessionCall.Ok(it) }
                    ?: SessionCall.Transient("完结响应体不可解析")
                else -> statusCall(response.code, bodyText)
            }
        }
    }

    override suspend fun abandon(sessionId: String) {
        val base = resolveBaseUrl() ?: return
        val request = Request.Builder()
            .url(sessionUrl(base, sessionId))
            .delete()
            .build()
        try {
            withContext(Dispatchers.IO) {
                // 204/404 皆视为已放弃（会话可能已被 TTL 清扫），不区分
                okHttpClient.newCall(request).execute().use { /* 尽力语义：不读不判 */ }
            }
        } catch (t: Throwable) {
            // 放弃失败不阻断取消路径：服务端无活动 24h TTL 清扫兜底（ADR-0028 决策 5）
            Log.d(UploadWorker.LOG_TAG, "abandon 失败（忽略）", t)
        }
    }

    private fun resolveBaseUrl(): HttpUrl? = baseUrl()?.toHttpUrlOrNull()

    /** /api/v1/uploads[/{seg}]（协议路径单点；协议侧改动须同步此处，反之亦然） */
    private fun sessionUrl(base: HttpUrl, vararg segments: String): HttpUrl =
        base.newBuilder().addPathSegments(SESSIONS_PATH).apply {
            segments.forEach { addPathSegment(it) }
        }.build()

    /**
     * 出网统一通道：IO 调度 + 响应体一次读取 + 异常收敛。取消信号异常是唯一穿透
     * （编排器据此放弃会话落取消终态），其余 IOException 一律收敛为 Transient。
     */
    private suspend fun <T> send(
        request: Request,
        map: (response: okhttp3.Response, bodyText: String) -> SessionCall<T>,
    ): SessionCall<T> = withContext(Dispatchers.IO) {
        try {
            okHttpClient.newCall(request).execute().use { response ->
                map(response, response.body?.string().orEmpty())
            }
        } catch (e: UploadCancelledException) {
            throw e
        } catch (e: IOException) {
            SessionCall.Transient("网络异常：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** 4xx → ClientError（Error{code,message} 文案透传，与直传同口径）；5xx/其它 → Transient */
    private fun statusCall(code: Int, bodyText: String): SessionCall<Nothing> =
        if (code in CLIENT_ERROR_MIN..CLIENT_ERROR_MAX) {
            SessionCall.ClientError(code, UploadApiBodies.serverErrorMessage(bodyText, code))
        } else {
            SessionCall.Transient("服务端错误 HTTP $code")
        }

    private companion object {
        /** 会话通道端点路径（openapi.yaml /api/v1/uploads*；协议侧改动须同步此处，反之亦然） */
        const val SESSIONS_PATH = "api/v1/uploads"

        const val COMPLETE_SEGMENT = "complete"

        /** PATCH 分片起点 query 参数名（协议 offset；协议侧改动须同步此处，反之亦然） */
        const val OFFSET_QUERY = "offset"

        const val HTTP_CONFLICT = 409

        /** 4xx 区间（文案透传只对客户端错误成立；5xx 走重试） */
        const val CLIENT_ERROR_MIN = 400
        const val CLIENT_ERROR_MAX = 499

        const val ADDRESS_UNREADY_MESSAGE = "服务端地址未就绪"

        /** 409 文案（编排器重同步后不展示，仅日志口径） */
        const val CHUNK_STALE_MESSAGE = "分片起点与服务端权威 offset 不符（HTTP 409）"
    }
}

/** 协议 requestBody 媒体类型（create=application/json；PATCH=application/octet-stream） */
private val JSON: MediaType = "application/json".toMediaType()

private val OCTET_STREAM: MediaType = "application/octet-stream".toMediaType()

private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody()

/**
 * 单片流式请求体：从已定位到起点的 [source] 精确写 [length] 字节（8MB 分片逐 64KB
 * 块直写网络，不整片落内存）。[isCancelled] 每块写完检查（直传同款 64KB 粒度协作式
 * 取消），命中抛 [UploadCancelledException] 断流。源流提前结束（文件被截断/授权失效）
 * 抛 IOException → Transient 重试路径。
 */
private class ChunkRequestBody(
    private val source: InputStream,
    private val length: Long,
    private val isCancelled: () -> Boolean,
) : RequestBody() {

    override fun contentType(): MediaType = OCTET_STREAM

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        val buffer = ByteArray(WRITE_CHUNK_BYTES)
        var written = 0L
        while (written < length) {
            if (isCancelled()) throw UploadCancelledException()
            val read = source.read(buffer, 0, minOf(buffer.size.toLong(), length - written).toInt())
            if (read == -1) throw IOException("本地源流提前结束（分片声明 $length 字节）")
            sink.write(buffer, 0, read)
            written += read
        }
        sink.flush()
    }

    private companion object {
        /** 64KB 网络写入粒度（镜像 AssetUploader 64KB 分块口径——直传文件冻结不动，双侧同步） */
        const val WRITE_CHUNK_BYTES = 64 * 1024
    }
}
