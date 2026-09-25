package media.qimeng.app.core.data.upload

import kotlinx.coroutines.test.runTest
import media.qimeng.app.core.data.repository.AuthorRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上传后自动挂靠序列锁定（挂靠批）：先 authors（PUT /assets/{id}/authors 单项）后
 * sources（PUT /authors/{id}/sources mode=append）；任一失败收敛为 Failed 且**不再有
 * 重试概念**（重试 = 重复上传文件，失败语义红线——不 retry 由 outcomeToResult 单测锁定）。
 * worker 编排层（resolveOutcome 的 outcome 透传/收敛）为两行分支，行为面由本测 +
 * UploadWorkSpecTest 联合覆盖。本模块测试不依赖 :core:testing，替身就地定义。
 */
class UploadAttacherTest {

    /** 时序记录替身：跨方法执行顺序断言（先 authors 后 sources） */
    private class RecordingAuthorRepository : AuthorRepository {
        val callLog = mutableListOf<String>()
        var replaceAssetAuthorsError: Exception? = null
        var appendAuthorSourcesError: Exception? = null

        override suspend fun authors(): List<media.qimeng.app.core.model.AuthorSummary> = emptyList()

        override suspend fun setFollowed(authorId: String, followed: Boolean) {
            callLog.add("follow:$authorId=$followed")
        }

        override suspend fun replaceAuthorSources(authorId: String, sources: List<String>) {
            callLog.add("sources-replace:$authorId=$sources")
        }

        override suspend fun appendAuthorSources(authorId: String, sources: List<String>) {
            appendAuthorSourcesError?.let { throw it }
            callLog.add("sources-append:$authorId=$sources")
        }

        override suspend fun replaceAssetAuthors(assetId: String, authorIds: List<String>) {
            replaceAssetAuthorsError?.let { throw it }
            callLog.add("asset-authors:$assetId=$authorIds")
        }
    }

    private val repository = RecordingAuthorRepository()
    private val attacher = UploadAttacher(repository)

    private fun assetAuthorLog() = repository.callLog.filter { it.startsWith("asset-authors:") }
    private fun appendLog() = repository.callLog.filter { it.startsWith("sources-append:") }

    /** Failed 原因提取（assertTrue 无 contract 不产生 smart cast，就地取原因） */
    private fun AttachOutcome.failedMessage(): String? = (this as? AttachOutcome.Failed)?.message

    @Test
    fun `作者与来源按序挂靠且参数正确`() = runTest {
        val outcome = attacher.attach("asset-uuid", "author-1", listOf("kemono", "r34"))
        assertTrue(outcome is AttachOutcome.Done)
        // 时序红线：authors 先（可能触发服务端建作者块），sources append 依赖块存在
        assertEquals(1, assetAuthorLog().size)
        assertEquals(1, appendLog().size)
        assertTrue(repository.callLog.indexOfFirst { it.startsWith("asset-authors:") } <
            repository.callLog.indexOfFirst { it.startsWith("sources-append:") })
        assertEquals("asset-authors:asset-uuid=[author-1]", assetAuthorLog().single())
        assertEquals("sources-append:author-1=[kemono, r34]", appendLog().single())
    }

    @Test
    fun `仅作者无来源只调authors`() = runTest {
        val outcome = attacher.attach("asset-uuid", "author-1", null)
        assertTrue(outcome is AttachOutcome.Done)
        assertEquals(1, assetAuthorLog().size)
        assertTrue(appendLog().isEmpty())
    }

    @Test
    fun `作者与来源皆空直接完成零调用`() = runTest {
        val outcome = attacher.attach("asset-uuid", null, null)
        assertTrue(outcome is AttachOutcome.Done)
        assertTrue(repository.callLog.isEmpty())
    }

    @Test
    fun `有来源无作者归为Failed且零调用`() = runTest {
        val outcome = attacher.attach("asset-uuid", null, listOf("kemono"))
        assertTrue(outcome is AttachOutcome.Failed)
        assertTrue(outcome.failedMessage()!!.contains("未选作者"))
        assertTrue(repository.callLog.isEmpty())
    }

    @Test
    fun `作者挂靠失败归为Failed且不触发sources调用`() = runTest {
        repository.replaceAssetAuthorsError = RuntimeException("HTTP 500")
        val outcome = attacher.attach("asset-uuid", "author-1", listOf("kemono"))
        assertTrue(outcome is AttachOutcome.Failed)
        assertEquals("HTTP 500", outcome.failedMessage())
        // sources append 依赖作者块：authors 失败后不得继续调 sources
        assertTrue(appendLog().isEmpty())
    }

    @Test
    fun `来源挂靠失败归为Failed携带原因`() = runTest {
        repository.appendAuthorSourcesError = RuntimeException("HTTP 500")
        val outcome = attacher.attach("asset-uuid", "author-1", listOf("kemono"))
        assertTrue(outcome is AttachOutcome.Failed)
        assertEquals("HTTP 500", outcome.failedMessage())
        // authors 已成功（文件入库 + 作者已挂），仅 sources 环节失败
        assertEquals(1, assetAuthorLog().size)
    }

    @Test
    fun `来源词trim与空串过滤`() = runTest {
        val outcome = attacher.attach("asset-uuid", "author-1", listOf(" kemono ", "  ", ""))
        assertTrue(outcome is AttachOutcome.Done)
        assertEquals("sources-append:author-1=[kemono]", appendLog().single())
    }

    @Test
    fun `纯空来源词视为不带挂靠`() = runTest {
        val outcome = attacher.attach("asset-uuid", "author-1", listOf("   "))
        assertTrue(outcome is AttachOutcome.Done)
        // 全空来源 = 无来源可挂：只调 authors
        assertEquals(1, assetAuthorLog().size)
        assertTrue(appendLog().isEmpty())
    }
}
