package media.qimeng.app.core.data.device

import android.util.Log
import java.io.IOException
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.di.IoDispatcher
import media.qimeng.app.core.data.repository.BusinessApiFactory
import media.qimeng.app.core.data.util.runSuspendCatching
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.network.ServerConfigDataSource
import media.qimeng.sdk.infrastructure.ClientException
import media.qimeng.sdk.infrastructure.ServerException
import media.qimeng.sdk.models.AiClient
import media.qimeng.sdk.models.AiClientScope
import media.qimeng.sdk.models.CreateAiClientRequest

/**
 * 本机单机模式的设备令牌自助生成（2026-11-08）。
 *
 * 背景：设备结果包端点只认专用设备 AI 令牌（device.apply + device_ids），而内嵌单机
 * 形态没有 PC 主机可执行 `qimeng ai-client create`。本机模式下 App 自己就连着
 * 127.0.0.1:18430 的内嵌服务端，且持有该实例的管理员登录会话，因此可以用**会话鉴权**
 * 调用 POST /api/v1/ai-clients 给自己签发一张只绑定本机标签的 device.apply 令牌。
 *
 * 安全边界：
 * - 只在 [ServerAddress.isLocalModePreset] 地址下工作；远端 NAS 模式不提供该入口
 *   （PC/NAS 的凭据仍由服务器主机侧管理）。
 * - 绝不把登录会话 token 当作设备令牌保存；失败也不回退。
 * - 明文 token 只在本次调用内存在：只写 [DeviceApplySettingsRepository]（同一 DataStore
 *   字段），不返回给调用方、不进入 UI state、不写日志。
 * - 幂等路径：先 GET /ai-clients，找同名且 deviceIds 含本机标签、未撤销未过期且带
 *   device.apply 的客户端，命中则 rotate 换新 token；否则才创建。这样「创建成功但
 *   响应丢失/保存失败」后再次点击不会不断累积同名客户端。
 */
@Singleton
class DeviceApplyLocalTokenRepository @Inject constructor(
    private val serverConfig: ServerConfigDataSource,
    private val apiFactory: BusinessApiFactory,
    private val settingsRepository: DeviceApplySettingsRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** 单飞：连点/重复触发不并发创建或轮换同一客户端。 */
    private val mutex = Mutex()

    suspend fun generate(targetDeviceId: String): LocalDeviceTokenResult = mutex.withLock {
        val target = targetDeviceId.trim()
        if (!DeviceApplyPolicy.isValidTargetDeviceId(target)) {
            return@withLock LocalDeviceTokenResult.Rejected(DeviceApplyAuthorization.MISSING_TARGET_DEVICE_MESSAGE)
        }
        val serverUrl = serverConfig.currentServerUrl()
        if (serverUrl.isNullOrBlank() || !ServerAddress.isLocalModePreset(serverUrl)) {
            return@withLock LocalDeviceTokenResult.Rejected(NOT_LOCAL_MODE_MESSAGE)
        }
        if (serverConfig.currentToken().isNullOrBlank()) {
            return@withLock LocalDeviceTokenResult.Rejected(ADMIN_SESSION_REQUIRED_MESSAGE)
        }
        // 读失败不阻断：默认关闭后台领取/无领取服务端，令牌仍可保存（用户之后可手动开启/补填）。
        // 这里保留已保存的领取服务端地址，避免「生成本机令牌」顺手清掉跨端配置。
        val savedSettings = runSuspendCatching { settingsRepository.settings.first() }.getOrNull()
        val backgroundEnabled = savedSettings?.backgroundEnabled ?: false
        // 第一步：只做网络创建/轮换，错误按 HTTP/网络分类返回；明文 token 暂存在局部变量。
        val provisioned = try {
            withContext(ioDispatcher) {
                val api = apiFactory.forServer(serverUrl).create()
                val reusable = LocalDeviceTokenMatching.findReusable(
                    clients = api.apiV1AiClientsGet().items,
                    targetDeviceId = target,
                    now = OffsetDateTime.now(),
                )
                val secret = if (reusable != null) {
                    api.apiV1AiClientsClientIdRotatePost(reusable.id)
                } else {
                    api.apiV1AiClientsPost(
                        CreateAiClientRequest(
                            label = LocalDeviceTokenMatching.labelFor(target),
                            scopes = listOf(AiClientScope.AiScopeDeviceApply),
                            deviceIds = listOf(target),
                            expiresInSeconds = TOKEN_TTL_SECONDS,
                        ),
                    )
                }
                // 复核返回的绑定关系再落盘：代理/旧服务端错位返回时不能把无效令牌
                // 当成「已配置」，否则 Worker 会一直 401/403。
                if (!secret.client.deviceIds.contains(target) ||
                    !secret.client.scopes.contains(AiClientScope.AiScopeDeviceApply)
                ) {
                    error("返回的 AI 客户端绑定与请求不一致")
                }
                secret.token to (reusable != null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: ClientException) {
            return@withLock when (e.statusCode) {
                HTTP_UNAUTHORIZED, HTTP_FORBIDDEN ->
                    LocalDeviceTokenResult.Rejected(ADMIN_SESSION_REQUIRED_MESSAGE)
                HTTP_CONFLICT ->
                    LocalDeviceTokenResult.Rejected("同名 AI 客户端已被撤销，请再点一次重新创建")
                else ->
                    LocalDeviceTokenResult.Rejected("生成设备令牌被服务端拒绝（HTTP ${e.statusCode}），请稍后重试")
            }
        } catch (e: ServerException) {
            return@withLock LocalDeviceTokenResult.Rejected(
                "生成设备令牌失败：服务端错误（HTTP ${e.statusCode}），请稍后重试",
            )
        } catch (e: IOException) {
            return@withLock LocalDeviceTokenResult.Rejected(
                "生成设备令牌失败：网络不可用，请确认本机服务端已启动后重试",
            )
        } catch (e: IllegalStateException) {
            return@withLock LocalDeviceTokenResult.Rejected("服务端返回的 AI 客户端绑定不一致，请重试")
        } catch (e: Exception) {
            Log.w(TAG, "生成本机设备令牌失败", e)
            return@withLock LocalDeviceTokenResult.Rejected("生成设备令牌失败，请重试")
        }
        // 第二步：只持久化 token。写盘失败时 token 已存在服务端，用户再点一次会走
        // rotate 换新 token（不会每次新增客户端）。
        try {
            settingsRepository.save(
                targetDeviceId = target,
                deviceToken = provisioned.first,
                backgroundEnabled = backgroundEnabled,
                deviceServiceUrl = savedSettings?.deviceServiceUrl.orEmpty(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "保存本机设备令牌失败", e)
            return@withLock LocalDeviceTokenResult.Rejected(
                "已取得设备令牌但保存失败，请重试；再次生成会轮换同名客户端",
            )
        }
        LocalDeviceTokenResult.Saved(rotated = provisioned.second)
    }

    companion object {
        /** 本机令牌有效期：30 天。到期后下次生成会新建（旧客户端留给 PC 端撤销）。 */
        const val TOKEN_TTL_SECONDS = 30 * 24 * 60 * 60

        /** 非本机模式提示（按钮本身按本机模式隐藏，这里是防误调的兜底）。 */
        const val NOT_LOCAL_MODE_MESSAGE =
            "只有本机单机模式（127.0.0.1:18430）可用当前会话生成本机设备令牌"

        /**
         * 401/403 提示：本机端点要求应用自己的管理员会话；设备令牌或远端会话都不能替代。
         * 不自动回退到「用会话 token 当设备 token」。
         */
        const val ADMIN_SESSION_REQUIRED_MESSAGE =
            "本机单机模式需要应用的本机管理员登录会话；请先在本机模式登录后再生成设备令牌。" +
                "设备令牌与会话 token 不能互相替代。"

        private const val TAG = "DeviceApplyLocalToken"
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_CONFLICT = 409
    }
}

/** 生成结果：成功只报「是否走了轮换」，不携带明文 token。 */
sealed interface LocalDeviceTokenResult {
    data class Saved(val rotated: Boolean) : LocalDeviceTokenResult
    data class Rejected(val message: String) : LocalDeviceTokenResult
}

/**
 * 本机设备客户端的匹配规则（纯函数，JVM 单测锁定）。
 *
 * 标签固定为 `android-local-<设备标签>`；还要求 deviceIds 精确包含本机标签、
 * scope 含 device.apply、未撤销且未过期——只按标签命中会复用错绑/已失效的客户端。
 */
internal object LocalDeviceTokenMatching {

    fun labelFor(targetDeviceId: String): String = "android-local-$targetDeviceId"

    fun findReusable(
        clients: List<AiClient>,
        targetDeviceId: String,
        now: OffsetDateTime,
    ): AiClient? = clients.firstOrNull { client ->
        val expiresAt = client.expiresAt
        client.label == labelFor(targetDeviceId) &&
            client.deviceIds.contains(targetDeviceId) &&
            client.scopes.contains(AiClientScope.AiScopeDeviceApply) &&
            client.revokedAt == null &&
            (expiresAt == null || expiresAt.isAfter(now))
    }
}
