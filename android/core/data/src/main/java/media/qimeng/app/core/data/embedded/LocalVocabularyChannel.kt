package media.qimeng.app.core.data.embedded

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.di.IoDispatcher
import media.qimeng.app.core.data.repository.VocabularySyncError
import media.qimeng.app.core.data.repository.VocabularySyncException
import media.qimeng.app.core.network.AuthApi
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.network.ServerConfigDataSource
import media.qimeng.app.core.network.di.LocalDirectClient
import media.qimeng.sdk.apis.DefaultApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本机词表通道（ADR-0034 词表同步 + ADR-0035 词表维护共享底层，2026-10-04）：
 * 「按需拉起内嵌服务端 → 等端口就绪 → dev-login 换本地 token → 接线到本机 DefaultApi →
 * 用完停回」的编排收口。消费方只须 [use] 一个口子，block 内拿到的 localApi 已就绪。
 *
 * 401 陷阱防线继承 ADR-0034：底层是 @LocalDirectClient 零拦截器客户端，本地请求任何
 * 失败都不会触发全局 AuthInterceptor 的 clearToken+登出广播（NAS 会话无感）。
 *
 * 错误分类直接抛 [VocabularySyncException]（LocalServerUnavailable 一支）：词表同步与
 * 词表维护共用同一底层通道、同一失败模式，不另立平行分类；同步侧的远端失败分支由
 * VocabularySyncRepositoryImpl 自行补充，维护侧只消费本地两支。
 */
@Singleton
class LocalVocabularyChannel @Inject constructor(
    private val serverConfig: ServerConfigDataSource,
    @LocalDirectClient private val localApi: DefaultApi,
    @LocalDirectClient private val localAuthApi: AuthApi,
    private val embeddedServerController: EmbeddedServerController,
    private val localServerWarmup: LocalServerWarmup,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * 拉起本机通道并执行 [block]（收发本机词表请求），收尾停回（成功失败一律）。
     * 停回守卫：若此刻主键已切成本机预设（用户操作中途换端，极罕见）则不停，交给
     * 壳层 collector 照常管理——恢复壳层「主键非本机预设=停服」不变量。
     */
    suspend fun <T> use(block: suspend (DefaultApi) -> T): T {
        prepare()
        try {
            return block(localApi)
        } finally {
            shutdownIfRemoteSession()
        }
    }

    /**
     * 幂等拉起内嵌服务端 → 等端口就绪 → dev-login 换本地 token 并接线。
     * awaitReady 超时不是失败语义的例外：本链路里端口不通=内嵌服务端起不来，就是
     * [VocabularySyncError.LocalServerUnavailable]（与 AuthRepositoryImpl 登录链「超时
     * 继续走探活」不同——那边有探活兜底文案，这边端口不通即无路可走）。
     */
    private suspend fun prepare() {
        // runCatching 同 MainViewModel collector 口径：吞后台态 FGS 启动限制等平台异常
        // （本流程恒在前台，理论上不触发；兜底转 LocalServerUnavailable 而非崩溃）
        val started = runCatching {
            embeddedServerController.ensureStartedIfLocalMode(ServerAddress.LOCAL_MODE_PRESET)
        }.getOrDefault(false)
        if (!started || !localServerWarmup.awaitReady()) {
            throw VocabularySyncException(VocabularySyncError.LocalServerUnavailable)
        }
        val token = try {
            withContext(ioDispatcher) { localAuthApi.devLogin() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw VocabularySyncException(VocabularySyncError.LocalServerUnavailable, e)
        }
        // 本地 Bearer 接线：SDK 生成物的实例级 provider（每 DefaultApi 实例一份，不共享
        // 全局静态字段）；无拦截器客户端唯一注入口。下一次 dev-login 覆盖旧值，无累积。
        localApi.accessTokenProvider = { token }
    }

    private fun shutdownIfRemoteSession() {
        val url = serverConfig.currentServerUrl()
        if (url.isNullOrEmpty() || !ServerAddress.isLocalModePreset(url)) {
            runCatching { embeddedServerController.stop() }
        }
    }
}
