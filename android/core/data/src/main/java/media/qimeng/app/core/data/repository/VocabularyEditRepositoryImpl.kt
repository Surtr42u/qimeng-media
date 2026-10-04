package media.qimeng.app.core.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import media.qimeng.app.core.data.di.IoDispatcher
import media.qimeng.app.core.data.embedded.LocalVocabularyChannel
import media.qimeng.sdk.models.CustomSourceGroups
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [VocabularyEditRepository] 实现（ADR-0035）：本机词表读写的编排单点。链路全在
 * LocalVocabularyChannel（拉起内嵌服务端 → 端口就绪 → dev-login → 接线 → 收尾停回），
 * 本类只补请求本身与错误归类——无远端参与、无门禁（编辑对象恒为本机内嵌库）。
 */
@Singleton
class VocabularyEditRepositoryImpl @Inject constructor(
    private val localChannel: LocalVocabularyChannel,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : VocabularyEditRepository {

    override suspend fun load(): Result<CustomSourceGroups> = runMapped {
        localChannel.use { api ->
            try {
                withContext(ioDispatcher) { api.apiV1SourcesCustomGroupsGet() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw VocabularySyncException(VocabularySyncError.LocalServerUnavailable, e)
            }
        }
    }

    override suspend fun save(payload: CustomSourceGroups): Result<Unit> = runMapped {
        localChannel.use { api ->
            try {
                withContext(ioDispatcher) { api.apiV1SourcesCustomGroupsPut(payload) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw VocabularySyncException(VocabularySyncError.LocalApplyFailed, e)
            }
        }
        Unit
    }

    /** 统一 Result 包装：领域异常按分类失败，取消异常原样上抛（协程取消纪律） */
    private inline fun <T> runMapped(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: VocabularySyncException) {
        Result.failure(e)
    }
}
