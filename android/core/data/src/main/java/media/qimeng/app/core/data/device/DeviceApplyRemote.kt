package media.qimeng.app.core.data.device

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import media.qimeng.sdk.apis.DefaultApi
import media.qimeng.sdk.models.DeviceApplyLocalHash
import media.qimeng.sdk.models.DeviceApplyResultRequest
import media.qimeng.sdk.models.DeviceApplyResultStatus
import media.qimeng.sdk.models.DeviceApplySegmentKind as SdkSegmentKind
import media.qimeng.sdk.models.DeviceApplyTask
import media.qimeng.sdk.models.DeviceApplyTaskStatus
import media.qimeng.sdk.models.DeviceApplyTaskSummary
import okhttp3.OkHttpClient

/**
 * 设备端点专用的 SDK 客户端（task-17）。
 *
 * 为什么不能复用全局 OkHttp/业务工厂：全局客户端挂 AuthInterceptor，会把**登录会话
 * token** 注入请求；设备结果包端点必须用专用设备 AI 令牌（device.apply + device_ids），
 * 且设备令牌 401/403 绝不能触发 AuthInterceptor 的「清会话 token + 登出广播」。
 * 因此这里使用独立客户端、实例级 accessTokenProvider 注入设备令牌、关闭重定向
 * （避免凭据被带到其他 host），日志/异常都不打印令牌。
 *
 * 超时：段最大 64MiB、v2 备份导入是长处理；读 300s、总时限关闭（与 BackupClient 同口径）。
 */
@Singleton
class DeviceApplyRemoteFactory @Inject constructor() {

    fun create(serverUrl: String, deviceToken: String): DeviceApplyRemote {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_DISABLED, TimeUnit.MILLISECONDS)
            .build()
        val api = DefaultApi(serverUrl, client)
        // 实例级 provider：不写 ApiClient 的全局静态 token，避免与其他 DefaultApi 串用。
        api.accessTokenProvider = { deviceToken }
        return DeviceApplyRemote(api)
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 300L
        const val WRITE_TIMEOUT_SECONDS = 60L
        const val CALL_TIMEOUT_DISABLED = 0L
    }
}

/**
 * 设备结果包端点的最小 SDK 包装（同步实现；调用方负责 Dispatchers.IO）。
 * 只暴露 Worker/设置页需要的操作，不把生成 SDK 类型泄漏到纯逻辑/feature。
 */
class DeviceApplyRemote internal constructor(private val api: DefaultApi) {

    /**
     * 按状态查询本机目标摘要（2026-11-08 task-21）：Runner 分别查 pending 与 claimed，
     * claimed 用于恢复「领取后进程被杀/回传失败」的任务（服务端 claim 幂等）。
     *
     * 2026-11-08 分页修复：服务端列表是 keyset 分页（缺省 limit 50），只取第一页会让
     * 第 51 条之后的 pending/claimed 任务永远轮不到。这里沿 [nextCursor] 读到 null，
     * 最多 [DeviceApplyTaskPaging.MAX_PAGES] 页；超过上限抛
     * [DeviceApplyPageLimitException]（Runner 记录明确提示后结束本轮，重试不会收敛）。
     * 页序原样拼接，保持服务端「新建倒序」语义；目标/状态/过期仍由 Runner 二次过滤。
     */
    fun listByStatus(
        targetDeviceId: String,
        status: DeviceTaskStatus,
        limit: Int = DeviceApplyTaskPaging.DEFAULT_PAGE_SIZE,
        maxPages: Int = DeviceApplyTaskPaging.MAX_PAGES,
    ): List<DeviceApplySummary> {
        val sdkStatus = when (status) {
            DeviceTaskStatus.PENDING -> DeviceApplyTaskStatus.DeviceApplyStatusPending
            DeviceTaskStatus.CLAIMED -> DeviceApplyTaskStatus.DeviceApplyStatusClaimed
            else -> return emptyList()
        }
        val source = DeviceApplyTaskPageSource { cursor ->
            val page = api.apiV1DeviceApplyTasksGet(
                targetDevice = targetDeviceId,
                status = sdkStatus,
                limit = limit,
                cursor = cursor,
            )
            DeviceApplyTaskPage(
                items = page.items.map { it.toDomain() },
                nextCursor = page.nextCursor,
            )
        }
        return DeviceApplyTaskPaging.collectAll(source, maxPages)
    }

    /**
     * 最近任务（只读）：设置页「最近设备任务」用，不带 status 过滤、只取第一页
     * （limit 20，服务端新建倒序）。不参与领取/应用，也不跟随 nextCursor。
     */
    fun listRecent(targetDeviceId: String, limit: Int = RECENT_LIMIT): List<DeviceApplySummary> =
        api.apiV1DeviceApplyTasksGet(
            targetDevice = targetDeviceId,
            status = null,
            limit = limit,
            cursor = null,
        ).items.map { it.toDomain() }

    fun claim(taskId: String): DeviceApplyDetail =
        api.apiV1DeviceApplyTasksIdClaimPost(UUID.fromString(taskId)).toDomain()

    fun get(taskId: String): DeviceApplyDetail =
        api.apiV1DeviceApplyTasksIdGet(UUID.fromString(taskId)).toDomain()

    fun downloadSegment(taskId: String, kind: DeviceSegmentKind): File =
        api.apiV1DeviceApplyTasksIdSegmentsKindGet(UUID.fromString(taskId), kind.toSdk())

    fun report(taskId: String, report: DeviceApplyReport) {
        api.apiV1DeviceApplyTasksIdResultPost(
            UUID.fromString(taskId),
            DeviceApplyResultRequest(
                status = report.status.toSdk(),
                readback = report.readback.takeIf { it.isNotEmpty() },
                deviceLocalHashes = report.deviceLocalHashes.takeIf { it.isNotEmpty() }?.map {
                    DeviceApplyLocalHash(kind = it.kind.toSdk(), sha256 = it.sha256)
                },
                message = report.message?.takeIf { it.isNotBlank() },
            ),
        )
    }

    private companion object {
        /** 设置页「最近设备任务」读取条数（服务端 limit 上限 100；只读一页即可）。 */
        const val RECENT_LIMIT = 20
    }
}

/** 设备结果包列表的单页域模型；[nextCursor] 为 null 表示已到底。 */
data class DeviceApplyTaskPage(
    val items: List<DeviceApplySummary>,
    val nextCursor: String?,
)

/**
 * 分页读取口（生产实现走 SDK；JVM 单测注入假实现验证多页遍历与页数上限）。
 * 单独抽出的原因：生成 SDK 的 [DefaultApi] 方法不可覆写，直接替身远端不可行。
 */
fun interface DeviceApplyTaskPageSource {
    fun fetch(cursor: String?): DeviceApplyTaskPage
}

/**
 * 列表分页上限异常：Runner 捕获后记录明确提示并结束本轮。
 * 不继承 IOException/ClientException：这是「数据量超出本机处理边界」的确定性终态，
 * 不应进入重试链（重试只会再次撞上限）。
 */
class DeviceApplyPageLimitException(
    val maxPages: Int,
    val collected: Int,
) : Exception(
    "设备结果包列表超过分页上限（最多 $maxPages 页，已读取 $collected 条）；" +
        "请先在 PC 端处理或取消历史任务后重试",
)

/**
 * 设备结果包列表分页收集（2026-11-08 修复「只看第一页」）。
 *
 * 沿 nextCursor 读到 null，页序原样拼接（服务端新建倒序）。硬上限 [maxPages] 页
 * （默认 20 × 100 条 = 2000 条），防止服务端游标异常时无限翻页；超过上限抛
 * [DeviceApplyPageLimitException]。
 */
object DeviceApplyTaskPaging {

    /** 硬分页上限（20 页）。 */
    const val MAX_PAGES = 20

    /** 单页条数（服务端 limit 上限 100）。 */
    const val DEFAULT_PAGE_SIZE = 100

    fun collectAll(
        source: DeviceApplyTaskPageSource,
        maxPages: Int = MAX_PAGES,
    ): List<DeviceApplySummary> {
        require(maxPages > 0) { "maxPages 必须为正数" }
        val collected = ArrayList<DeviceApplySummary>()
        var cursor: String? = null
        var pages = 0
        while (true) {
            if (pages >= maxPages) {
                throw DeviceApplyPageLimitException(maxPages = maxPages, collected = collected.size)
            }
            val page = source.fetch(cursor)
            collected += page.items
            pages++
            cursor = page.nextCursor ?: return collected
        }
    }
}

private fun DeviceApplyTaskSummary.toDomain(): DeviceApplySummary = DeviceApplySummary(
    id = id.toString(),
    targetDeviceId = targetDeviceId,
    status = status.toDomain(),
    protocolVersion = protocolVersion,
    rulesFingerprint = rulesFingerprint,
    // 领取前客户端再过滤一次过期（服务端已惰性标记，旧服务端/响应替换时防御）。
    expiresAtMillis = expiresAt.toInstant().toEpochMilli(),
    createdAtMillis = createdAt.toInstant().toEpochMilli(),
    segmentCount = segmentCount,
)

private fun DeviceApplyTask.toDomain(): DeviceApplyDetail = DeviceApplyDetail(
    id = id.toString(),
    targetDeviceId = targetDeviceId,
    status = status.toDomain(),
    protocolVersion = protocolVersion,
    rulesFingerprint = rulesFingerprint,
    segments = segments.map { segment ->
        DeviceApplySegment(
            kind = segment.kind.toDomain(),
            bytes = segment.bytes,
            sha256 = segment.sha256,
            expectedReadback = segment.expectedReadback,
        )
    },
)

private fun DeviceApplyTaskStatus.toDomain(): DeviceTaskStatus = when (this) {
    DeviceApplyTaskStatus.DeviceApplyStatusPending -> DeviceTaskStatus.PENDING
    DeviceApplyTaskStatus.DeviceApplyStatusClaimed -> DeviceTaskStatus.CLAIMED
    DeviceApplyTaskStatus.DeviceApplyStatusApplied -> DeviceTaskStatus.APPLIED
    DeviceApplyTaskStatus.DeviceApplyStatusPartial -> DeviceTaskStatus.PARTIAL
    DeviceApplyTaskStatus.DeviceApplyStatusFailed -> DeviceTaskStatus.FAILED
    DeviceApplyTaskStatus.DeviceApplyStatusCancelled -> DeviceTaskStatus.CANCELLED
    DeviceApplyTaskStatus.DeviceApplyStatusExpired -> DeviceTaskStatus.EXPIRED
}

private fun SdkSegmentKind.toDomain(): DeviceSegmentKind = when (this) {
    SdkSegmentKind.DeviceApplySegmentKindMasterTxt -> DeviceSegmentKind.MASTER_TXT
    SdkSegmentKind.DeviceApplySegmentKindV2Backup -> DeviceSegmentKind.V2_BACKUP
}

private fun DeviceSegmentKind.toSdk(): SdkSegmentKind = when (this) {
    DeviceSegmentKind.MASTER_TXT -> SdkSegmentKind.DeviceApplySegmentKindMasterTxt
    DeviceSegmentKind.V2_BACKUP -> SdkSegmentKind.DeviceApplySegmentKindV2Backup
}

private fun DeviceResultStatus.toSdk(): DeviceApplyResultStatus = when (this) {
    DeviceResultStatus.APPLIED -> DeviceApplyResultStatus.DeviceApplyResultStatusApplied
    DeviceResultStatus.PARTIAL -> DeviceApplyResultStatus.DeviceApplyResultStatusPartial
    DeviceResultStatus.FAILED -> DeviceApplyResultStatus.DeviceApplyResultStatusFailed
}
