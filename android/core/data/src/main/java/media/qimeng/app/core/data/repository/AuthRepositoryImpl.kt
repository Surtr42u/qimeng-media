package media.qimeng.app.core.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import media.qimeng.app.core.data.embedded.EmbeddedServerController
import media.qimeng.app.core.data.embedded.LocalServerWarmup
import media.qimeng.app.core.network.AuthApiFactory
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.network.ServerConfigDataSource
import media.qimeng.app.core.network.SessionEventBus
import media.qimeng.sdk.infrastructure.ClientException
import media.qimeng.sdk.infrastructure.ServerException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 登出时吊销服务端会话的时限（毫秒）。为什么 3 秒：切换目标常是「旧地址已不通」场景
 * （内嵌本机服务已停 / 旧 NAS 已关机），若沿用全局客户端 10s 读超时会拖死登出/切换流程；
 * 3s 覆盖局域网正常往返，超时即放弃吊销——本地 token 照清，残留会话由服务端过期策略兜底。
 */
internal const val logoutRevokeTimeoutMs = 3_000L

/**
 * [AuthRepository] 实现：编排探活→登录→持久化三步；不持有任何服务端地址假设
 * （地址或来自用户输入、或来自 ServerConfigDataSource，绝无第二处常量）。
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val serverConfig: ServerConfigDataSource,
    private val authApiFactory: AuthApiFactory,
    sessionEventBus: SessionEventBus,
    private val embeddedServerController: EmbeddedServerController,
    private val localServerWarmup: LocalServerWarmup,
) : AuthRepository {

    override val serverUrl: Flow<String> = serverConfig.serverUrl

    override val rememberedNasUrl: Flow<String> = serverConfig.rememberedNasUrl

    override val rememberedLocalUrl: Flow<String> = serverConfig.rememberedLocalUrl

    override val isLoggedIn: Flow<Boolean> = serverConfig.token
        .map { it != null }
        .distinctUntilChanged()

    override val unauthorizedEvents: Flow<Unit> = sessionEventBus.unauthorized

    override suspend fun login(rawAddress: String, password: String): LoginResult {
        val baseUrl = ServerAddress.normalize(rawAddress)
            ?: return LoginResult.Failure(LoginError.InvalidAddress)
        // 批S8 用户实测死锁修复：本机模式地址须在发请求前先把内嵌服务端拉起来（详见
        // [warmUpLocalServerIfNeeded]）——旧链路登录成功才 updateServerUrl(18430)，服务没起登录必败，死锁
        warmUpLocalServerIfNeeded(baseUrl)
        val api = authApiFactory.create(baseUrl)

        // 第一步：探活（协议面路径 /api/v1/healthz）。不通即「地址不通」，不把网络问题误报成密码错
        try {
            api.probe()
        } catch (e: IOException) {
            return LoginResult.Failure(LoginError.ServerUnreachable)
        } catch (e: ClientException) {
            // 探针端点免鉴权且恒 200，收到 4xx 说明对端不是绮梦服务端——对用户而言等同地址不通
            return LoginResult.Failure(LoginError.ServerUnreachable)
        } catch (e: ServerException) {
            // 5xx：地址可达但服务端自身故障（探针恒 200 的协议语义下 5xx 即不可用）——
            // 对登录阶段的用户仍是「这台服务现在用不了」，归入地址不通而非细节错误
            return LoginResult.Failure(LoginError.ServerUnreachable)
        }

        // 第二步：登录（密码非空走密码登录；空密码走 dev-login 免密通道——仅 dev 模式服务端可用）；
        // 成功后先写地址再写 token（壳层由 token 流驱动跳壳，两键都落盘才算完成）
        return try {
            val token = if (password.isEmpty()) api.devLogin() else api.login(password)
            serverConfig.updateServerUrl(baseUrl)
            // 批S3 服务器地址固化：登录成功（dev-login 免密链路同为「成功登录」）按端型分流记忆——
            // 本机模式登录只写本地记忆槽，NAS 记忆不被覆盖。写记忆放在写 token 前：壳层由 token
            // 流驱动跳壳，地址相关的持久化（主键+记忆槽）都完成后再翻登录态。
            serverConfig.rememberLoginAddress(baseUrl)
            serverConfig.updateToken(token)
            LoginResult.Success
        } catch (e: IOException) {
            LoginResult.Failure(LoginError.ServerUnreachable)
        } catch (e: ClientException) {
            when {
                // dev-login 未开启时服务端恒 404（协议面语义，server auth_dev_test 锁定；协议侧改动须同步）
                password.isEmpty() && e.statusCode == HTTP_NOT_FOUND ->
                    LoginResult.Failure(LoginError.DevLoginUnavailable)
                e.statusCode == HTTP_UNAUTHORIZED -> LoginResult.Failure(LoginError.WrongPassword)
                else -> LoginResult.Failure(LoginError.Other("login client error ${e.statusCode}"))
            }
        } catch (e: ServerException) {
            LoginResult.Failure(LoginError.Other("login server error ${e.statusCode}"))
        }
    }

    /**
     * 批S8 用户实测死锁修复（M6 形态 B 主入口）：未登录态经登录页「本机模式」进入时，
     * serverUrl 主键仍是 NAS——壳层自检链（MainViewModel 的 serverUrl collect）在登录
     * 成功前永远 collect 到 NAS，ensureStartedIfLocalMode 不会触发 18430 拉起；而旧链路
     * 是「登录成功才 updateServerUrl(18430)」→ 死锁：服务没起 → 探活必败 → 登录必败 →
     * 主键永不切换。故在本方法构造出 baseUrl 后、发登录请求前就地拉起（幂等，Service
     * 收到重复 START intent 无副作用）并等端口就绪（见 [LocalServerWarmup]）。
     * 与壳层自检链互补不冲突：登录成功后主键切换，collector 再触发 ensure 为幂等 no-op。
     * 等待超时不造新错误——继续走既有探活/登录链，由其报 ServerUnreachable「地址不通」。
     */
    private suspend fun warmUpLocalServerIfNeeded(baseUrl: String) {
        if (!ServerAddress.isLocalModePreset(baseUrl)) return
        embeddedServerController.ensureStartedIfLocalMode(baseUrl)
        localServerWarmup.awaitReady()
    }

    // 清 token 前尽力吊销当前会话（ADR-0021 多设备并发会话：登录不再挤掉其他设备，本设备的
    // 会话也就必须显式吊销，否则服务端 auth_sessions 行会一直活到过期）。尽力语义：吊销失败/
    // 超时一律忽略、本地照清——登出是本地状态切换，不许被网络问题（旧地址已停机等）阻塞。
    // 已知且无害的副作用：吊销请求若收 401（token 已过期/已被吊销）会触发 AuthInterceptor 的
    // sessionExpired 广播——登出流中壳层本来就要切登录页，冗余事件无实害。
    private suspend fun revokeCurrentSessionBestEffort() {
        val baseUrl = serverConfig.currentServerUrl() ?: return // 从未登录过：无会话可吊销
        runCatching {
            withTimeoutOrNull(logoutRevokeTimeoutMs) {
                authApiFactory.create(baseUrl).logout()
            }
        }
    }

    override suspend fun logout() {
        revokeCurrentSessionBestEffort()
        serverConfig.clearToken()
    }

    // 吊销用的是此刻仍是旧值的 token/地址（先吊销再切地址），随后按既定次序先写地址再清 token
    // ——顺序理由见接口 KDoc（换登录页回填的确定性）
    override suspend fun logoutWithStagedUrl(url: String) {
        revokeCurrentSessionBestEffort()
        serverConfig.updateServerUrl(url)
        serverConfig.clearToken()
    }

    private companion object {
        /** 未认证状态码（openapi.yaml components.responses.Unauthorized；协议侧改动须同步）。 */
        const val HTTP_UNAUTHORIZED = 401

        /** 资源不存在状态码（dev-login 未开启时服务端返回 404 不泄露信息；协议侧改动须同步）。 */
        const val HTTP_NOT_FOUND = 404
    }
}
