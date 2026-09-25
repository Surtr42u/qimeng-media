package media.qimeng.app.core.testing

import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.model.AuthorSummary
import media.qimeng.app.core.model.AuthorType

/**
 * [AuthorRepository] 测试替身：词表/来源/挂靠调用全部记录可断言（挂靠批 UploadViewModel
 * 与 UploadAttacher 单测共用；生产实现 = SdkAuthorRepository）。
 */
class FakeAuthorRepository : AuthorRepository {

    /** GET /authors 返回值（可编程） */
    var authorsResult: List<AuthorSummary> = emptyList()

    /** GET /authors/source-vocabulary 返回值（可编程） */
    var vocabularyResult: List<String> = emptyList()

    /** GET /authors/{id}/sources 回显（可编程） */
    var sourcesByIdResult: (String) -> List<String> = { emptyList() }

    /** PUT /authors/{id}/sources（replace）调用记录 */
    val replaceSourceCalls = mutableListOf<Pair<String, List<String>>>()

    /** PUT /authors/{id}/sources（append）调用记录（挂靠批自动挂靠通道） */
    val appendSourceCalls = mutableListOf<Pair<String, List<String>>>()

    /** PUT /assets/{id}/authors 调用记录（assetId to authorIds） */
    val replaceAssetAuthorCalls = mutableListOf<Pair<String, List<String>>>()

    /** 各调用抛错模拟（挂靠失败语义用例；顺序 = 执行序：authors 先、sources 后） */
    var replaceAssetAuthorsError: Exception? = null
    var appendAuthorSourcesError: Exception? = null

    /** 时序日志（跨方法执行顺序断言；先 authors 后 sources 的锁定位） */
    val callLog = mutableListOf<String>()

    override suspend fun authors(): List<AuthorSummary> = authorsResult

    override suspend fun setFollowed(authorId: String, followed: Boolean) {
        callLog.add("follow:$authorId=$followed")
    }

    override suspend fun sourceVocabulary(): List<String> = vocabularyResult

    override suspend fun authorSourcesById(authorId: String): List<String> = sourcesByIdResult(authorId)

    override suspend fun replaceAuthorSources(authorId: String, sources: List<String>) {
        callLog.add("sources-replace:$authorId=$sources")
        replaceSourceCalls.add(authorId to sources)
    }

    override suspend fun appendAuthorSources(authorId: String, sources: List<String>) {
        appendAuthorSourcesError?.let { throw it }
        callLog.add("sources-append:$authorId=$sources")
        appendSourceCalls.add(authorId to sources)
    }

    override suspend fun replaceAssetAuthors(assetId: String, authorIds: List<String>) {
        replaceAssetAuthorsError?.let { throw it }
        callLog.add("asset-authors:$assetId=$authorIds")
        replaceAssetAuthorCalls.add(assetId to authorIds)
    }

    /** 便捷构造（多数用例只关心挂靠通道） */
    companion object {
        fun summary(id: String, name: String) = AuthorSummary(
            id = id,
            displayName = name,
            type = AuthorType.REGULAR,
            fileCount = 0,
            followed = false,
            viewCount = null,
        )
    }
}
