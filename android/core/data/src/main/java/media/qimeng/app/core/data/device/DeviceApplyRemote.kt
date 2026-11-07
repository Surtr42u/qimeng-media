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
 * 只暴露 Worker 需要的五个操作，不把生成 SDK 类型泄漏到纯逻辑/feature。
 */
class DeviceApplyRemote internal constructor(private val api: DefaultApi) {

    /**
     * 按状态查询本机目标摘要（2026-11-08 task-21）：Runner 分别查 pending 与 claimed，
     * claimed 用于恢复「领取后进程被杀/回传失败」的任务（服务端 claim 幂等）。
     */
    fun listByStatus(
        targetDeviceId: String,
        status: DeviceTaskStatus,
        limit: Int = 100,
    ): List<DeviceApplySummary> {
        val sdkStatus = when (status) {
            DeviceTaskStatus.PENDING -> DeviceApplyTaskStatus.DeviceApplyStatusPending
            DeviceTaskStatus.CLAIMED -> DeviceApplyTaskStatus.DeviceApplyStatusClaimed
            else -> return emptyList()
        }
        return api.apiV1DeviceApplyTasksGet(
            targetDevice = targetDeviceId,
            status = sdkStatus,
            limit = limit,
            cursor = null,
        ).items.map { it.toDomain() }
    }

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
}

private fun DeviceApplyTaskSummary.toDomain(): DeviceApplySummary = DeviceApplySummary(
    id = id.toString(),
    targetDeviceId = targetDeviceId,
    status = status.toDomain(),
    protocolVersion = protocolVersion,
    rulesFingerprint = rulesFingerprint,
    // 领取前客户端再过滤一次过期（服务端已惰性标记，旧服务端/响应替换时防御）。
    expiresAtMillis = expiresAt.toInstant().toEpochMilli(),
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
