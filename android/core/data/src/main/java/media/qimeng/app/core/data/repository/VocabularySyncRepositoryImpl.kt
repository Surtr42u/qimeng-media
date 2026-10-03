package media.qimeng.app.core.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.di.IoDispatcher
import media.qimeng.app.core.data.embedded.EmbeddedServerController
import media.qimeng.app.core.data.embedded.LocalServerWarmup
import media.qimeng.app.core.network.AuthApi
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.network.ServerConfigDataSource
import media.qimeng.app.core.network.di.LocalDirectClient
import media.qimeng.sdk.apis.DefaultApi
import media.qimeng.sdk.models.CustomSourceGroups
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [VocabularySyncRepository] 实现：全链路编排单点（ADR-0034）。
 *
 * 链路（preview 与 apply 同构，apply 多一步本地 PUT）：
 * 1. 门禁：当前必须为远程登录态（[ServerConfigDataSource.currentServerUrl] 非本机预设）——
 *    本机模式没有权威源，直接 [VocabularySyncError.NoAuthoritativeSource]；
 * 2. 远端 GET /sources/custom-groups：标准鉴权通道（BusinessApiFactory + 全局 OkHttp
 *    自带 AuthInterceptor Bearer），地址唯一来源 ServerConfigDataSource；
 * 3. 本机通道准备：ensureStartedIfLocalMode(预设地址) 幂等拉起内嵌服务端 + LocalServerWarmup
 *    等端口就绪（AuthRepositoryImpl.warmUpLocalServerIfNeeded 同款先例）→ 本地 dev-login
 *    （@LocalDirectClient 无拦截器客户端 + 密钥内存槽现取）换本地 token，经 SDK 实例级
 *    accessTokenProvider 接线到本机 DefaultApi；
 * 4. 本地 GET（预览对照）/ 本地 PUT（显式覆盖下发）；
 * 5. 用完即停：内嵌服务端是本次操作的一次性工具，成功与失败路径一律收尾停回
 *    （try/finally，恢复壳层「主键非本机预设=停服」不变量，MainViewModel collector
 *    同口径；预览与执行各自独立拉起/回收——stop→再 start 的竞态由 Service 的
 *    ensure 语义兜住：START intent 取消销毁时原子进程继续服务）。
 *
 * 401 陷阱防线：第 3-4 步全部走 @LocalDirectClient 客户端（零拦截器），本地请求的任何
 * 失败都不会触发全局 AuthInterceptor 的 clearToken+登出广播（NAS 会话无感，见
 * NetworkModule.provideLocalDirectOkHttpClient 注释）。
 */
@Singleton
class VocabularySyncRepositoryImpl @Inject constructor(
    private val serverConfig: ServerConfigDataSource,
    private val apiFactory: BusinessApiFactory,
    @LocalDirectClient private val localApi: DefaultApi,
    @LocalDirectClient private val localAuthApi: AuthApi,
    private val embeddedServerController: EmbeddedServerController,
    private val localServerWarmup: LocalServerWarmup,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : VocabularySyncRepository {

    override suspend fun preview(): Result<VocabularySyncPreview> = try {
        val remote = fetchRemoteVocabulary()
        val local = try {
            prepareLocalChannel()
            fetchLocalVocabulary()
        } finally {
            shutdownLocalServerAfterUse()
        }
        // 本机独有判定：canonical trim + 忽略大小写（对齐引擎大小写不敏感折叠口径；
        // 这是预览对照的 UX 数字，非引擎行为）
        val remoteCanonicals = remote.mapToCanonicalSet()
        val localOnly = local.groups.count { it.canonical.toCanonicalKey() !in remoteCanonicals }
        Result.success(
            VocabularySyncPreview(
                remoteGroupCount = remote.groups.size,
                localGroupCount = local.groups.size,
                localOnlyGroupCount = localOnly,
                remoteStopWordCount = remote.stopWords.orEmpty().size,
                localStopWordCount = local.stopWords.orEmpty().size,
            ),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: VocabularySyncException) {
        Result.failure(e)
    }

    override suspend fun apply(): Result<VocabularySyncResult> = try {
        val remote = fetchRemoteVocabulary()
        // 同步显式覆盖（协议语义见 CustomSourceGroups KDoc）：groups 整体替换；stopWords
        // 恒传显式数组——远端无追加层时传空数组=清空本机追加层，而非缺省语义的「保持现值」，
        // 保证同步后「本机=远端」。PUT 成功即完成（服务端同步替换引擎 + 后台全常规库重算）。
        try {
            prepareLocalChannel()
            withContext(ioDispatcher) {
                localApi.apiV1SourcesCustomGroupsPut(
                    CustomSourceGroups(groups = remote.groups, stopWords = remote.stopWords.orEmpty()),
                )
            }
        } finally {
            shutdownLocalServerAfterUse()
        }
        Result.success(
            VocabularySyncResult(
                appliedGroupCount = remote.groups.size,
                appliedStopWordCount = remote.stopWords.orEmpty().size,
            ),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: VocabularySyncException) {
        Result.failure(e)
    }

    /**
     * 远端权威词表拉取（标准鉴权通道）。业务请求必须发生在登录之后（currentServerUrl
     * 已就绪），门禁在 [requireRemoteSession] 先行。
     */
    private suspend fun fetchRemoteVocabulary(): CustomSourceGroups {
        requireRemoteSession()
        return try {
            withContext(ioDispatcher) { apiFactory.create().apiV1SourcesCustomGroupsGet() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 网络/HTTP/响应解析失败统一归远端失败（含 401 过期——届时全局 AuthInterceptor
            // 照常广播登出，本分类只是本功能的文案口径）
            throw VocabularySyncException(VocabularySyncError.RemoteFetchFailed, e)
        }
    }

    /**
     * 门禁：远程登录态校验（本机模式/未登录 = 无权威源）。地址在登出后仍保留
     * （ServerConfigDataSource 契约），只看 URL 会把「已登出但留着 NAS 地址」的
     * 状态误判为可同步（远端 GET 无 Bearer 必 401），故须同时校验 token 在场。
     */
    private fun requireRemoteSession() {
        val url = serverConfig.currentServerUrl()
        val hasRemoteSession = !url.isNullOrEmpty() &&
            !ServerAddress.isLocalModePreset(url) &&
            serverConfig.currentToken() != null
        if (!hasRemoteSession) {
            throw VocabularySyncException(VocabularySyncError.NoAuthoritativeSource)
        }
    }

    /**
     * 本机通道准备：幂等拉起内嵌服务端 → 等端口就绪 → dev-login 换本地 token 并接线。
     * awaitReady 超时不是失败语义的例外：本链路里端口不通=内嵌服务端起不来，就是
     * [VocabularySyncError.LocalServerUnavailable]（与 AuthRepositoryImpl 登录链「超时
     * 继续走探活」不同——那边有探活兜底文案，这边端口不通即无路可走）。
     */
    private suspend fun prepareLocalChannel() {
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

    private suspend fun fetchLocalVocabulary(): CustomSourceGroups = try {
        withContext(ioDispatcher) { localApi.apiV1SourcesCustomGroupsGet() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw VocabularySyncException(VocabularySyncError.LocalServerUnavailable, e)
    }

    /**
     * 用完即停（恢复壳层不变量）：词表同步恒发生在远程登录态，内嵌服务端是本次操作的
     * 一次性工具——收尾停回去，回收前台通知。守卫：若此刻主键已切成本机预设（用户同步
     * 中途换端，极罕见）则不停，交给壳层 collector 照常管理。
     */
    private fun shutdownLocalServerAfterUse() {
        val url = serverConfig.currentServerUrl()
        if (url.isNullOrEmpty() || !ServerAddress.isLocalModePreset(url)) {
            runCatching { embeddedServerController.stop() }
        }
    }

    /** canonical 折叠集合：忽略大小写（引擎折叠口径）、trim 对齐服务端规范化 */
    private fun CustomSourceGroups.mapToCanonicalSet(): Set<String> =
        groups.mapTo(mutableSetOf()) { it.canonical.toCanonicalKey() }

    private fun String.toCanonicalKey(): String = trim().lowercase()
}
