package media.qimeng.app.core.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.di.IoDispatcher
import media.qimeng.app.core.data.embedded.LocalVocabularyChannel
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.network.ServerConfigDataSource
import media.qimeng.sdk.apis.DefaultApi
import media.qimeng.sdk.models.CustomSourceGroups
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [VocabularySyncRepository] 实现：合并同步全链路编排单点（ADR-0035 定稿语义）。
 *
 * 链路（合并是纯函数，见 [VocabularyMerger]）：
 * 1. 门禁：远程登录态（URL 非本机预设 + token 在场）——本机模式没有远端可合并；
 * 2. 远端 GET（标准鉴权通道）+ 本机 GET（LocalVocabularyChannel.use：幂等拉起内嵌
 *    服务端 → 端口就绪 → dev-login → 接线，用完即停）；
 * 3. [VocabularyMerger.merge] 并集合并（远端在前）；
 * 4. 合并结果显式 PUT 回两端：先远端（标准鉴权）后本机（再开一次本机通道）——任一端
 *    失败两端短暂不一致但零丢失，合并幂等、重跑即收敛（ADR-0035 已知限制）。
 *
 * 401 陷阱防线：本机请求全部走 @LocalDirectClient 客户端（零拦截器，LocalVocabularyChannel
 * 收口），本地失败不会触发全局登出广播；远端请求走全局客户端是标准消费（401 过期照常
 * 广播登出，属正常会话语义）。
 */
@Singleton
class VocabularySyncRepositoryImpl @Inject constructor(
    private val serverConfig: ServerConfigDataSource,
    private val apiFactory: BusinessApiFactory,
    private val localChannel: LocalVocabularyChannel,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : VocabularySyncRepository {

    override suspend fun sync(): Result<VocabularySyncResult> = try {
        requireRemoteSession()
        val remote = fetchRemoteVocabulary()
        val local = fetchLocalVocabulary()
        val merged = VocabularyMerger.merge(remote, local)
        // 增量计数（结果提示用）：按折叠键判「对方没有的组」，与合并引擎同口径
        val remoteKeys = remote.groups.map { it.canonical.toCanonicalKey() }.toSet()
        val localKeys = local.groups.map { it.canonical.toCanonicalKey() }.toSet()
        val newFromRemote = remote.groups.count { it.canonical.toCanonicalKey() !in localKeys }
        val newFromLocal = local.groups.count { it.canonical.toCanonicalKey() !in remoteKeys }
        // 先远端后本机：顺序无碍收敛（合并幂等），远端先行与「服务端为权威源」的原
        // 语义延续一致
        putRemoteVocabulary(merged)
        putLocalVocabulary(merged)
        Result.success(
            VocabularySyncResult(
                mergedGroupCount = merged.groups.size,
                mergedStopWordCount = merged.stopWords.orEmpty().size,
                newFromRemoteCount = newFromRemote,
                newFromLocalCount = newFromLocal,
            ),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: VocabularySyncException) {
        Result.failure(e)
    }

    /**
     * 远端词表拉取（标准鉴权通道）。业务请求必须发生在登录之后（currentServerUrl
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

    /** 远端合并结果写入（标准鉴权通道） */
    private suspend fun putRemoteVocabulary(payload: CustomSourceGroups) {
        requireRemoteSession()
        try {
            withContext(ioDispatcher) {
                apiFactory.create().apiV1SourcesCustomGroupsPut(payload)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw VocabularySyncException(VocabularySyncError.RemoteApplyFailed, e)
        }
    }

    /** 本机 GET（通道一次会话：拉起→GET→停回） */
    private suspend fun fetchLocalVocabulary(): CustomSourceGroups = localChannel.use { api ->
        try {
            withContext(ioDispatcher) { api.apiV1SourcesCustomGroupsGet() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw VocabularySyncException(VocabularySyncError.LocalServerUnavailable, e)
        }
    }

    /** 本机合并结果写入（通道一次会话：拉起→PUT→停回；显式 groups+stopWords 整体替换） */
    private suspend fun putLocalVocabulary(payload: CustomSourceGroups) = localChannel.use { api ->
        try {
            withContext(ioDispatcher) { api.apiV1SourcesCustomGroupsPut(payload) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw VocabularySyncException(VocabularySyncError.LocalApplyFailed, e)
        }
    }

    /**
     * 门禁：远程登录态校验（本机模式/未登录 = 无远端可合并）。地址在登出后仍保留
     * （ServerConfigDataSource 契约），只看 URL 会把「已登出但留着 NAS 地址」的
     * 状态误判为可同步（远端请求无 Bearer 必 401），故须同时校验 token 在场。
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

    private fun String.toCanonicalKey(): String = trim().lowercase()
}
