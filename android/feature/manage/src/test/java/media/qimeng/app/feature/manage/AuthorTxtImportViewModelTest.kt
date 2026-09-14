package media.qimeng.app.feature.manage

import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.model.AuthorSummary
import media.qimeng.app.core.testing.MainDispatcherRule
import media.qimeng.sdk.models.TxtImportedFile
import media.qimeng.sdk.models.TxtImportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 作者 TXT 导入状态机锁定（U10-6b）：导入成功计数/非 txt 拦截/空 content 不出网/
 * rebuild 禁用与成功/移除后刷新/读文件失败。fake 只在测试源集内（LibraryManageViewModelTest
 * 同款模式）；成功反馈文案逐字对齐 Web TxtAuthorImportCard 的 toast。
 */
class AuthorTxtImportViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun driveIdle() = mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

    /** 服务端导入响应：作者 N / 匹配文件 M（测试内可编程） */
    private val importResult = TxtImportResult(authorsImported = 3, filesMatched = 2)

    private fun newViewModel(repository: FakeAuthorRepository = FakeAuthorRepository()) =
        Pair(AuthorTxtImportViewModel(repository).also { driveIdle() }, repository)

    @Test
    fun `init加载片段列表无错误`() {
        val (viewModel, _) = newViewModel(
            FakeAuthorRepository().apply { seed += listOf("a.txt", "b.txt") },
        )
        val state = viewModel.uiState.value
        assertEquals(listOf("a.txt", "b.txt"), state.files)
        assertNull(state.errorMessage)
        assertNull(state.noticeMessage)
    }

    @Test
    fun `导入成功置计数反馈并刷新列表`() {
        val (viewModel, repository) = newViewModel()
        viewModel.onFilePicked("清单.txt", "作者A;作者B")
        driveIdle()
        assertEquals("清单.txt" to "作者A;作者B", repository.importTxtCalls.single())
        val state = viewModel.uiState.value
        // Web L59 逐字：导入完成：作者 N，匹配文件 M
        assertEquals("「清单.txt」导入完成：作者 3，匹配文件 2", state.noticeMessage)
        assertNull(state.errorMessage)
        // 导入的片段已进刷新后的列表（fake 模拟服务端同名覆盖落库）
        assertEquals(listOf("清单.txt"), state.files)
        assertTrue(!state.importing)
    }

    @Test
    fun `非txt扩展名前置拦截不出网`() {
        val (viewModel, repository) = newViewModel()
        viewModel.onFilePicked("清单.md", "内容")
        driveIdle()
        // Web L50 逐字文案 + 零出网
        assertEquals("仅支持 .txt 作者清单文件", viewModel.uiState.value.errorMessage)
        assertTrue(repository.importTxtCalls.isEmpty())
    }

    @Test
    fun `空content不出网且无反馈`() {
        val (viewModel, repository) = newViewModel()
        viewModel.onFilePicked("空.txt", "")
        driveIdle()
        assertTrue(repository.importTxtCalls.isEmpty())
        assertNull(viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.noticeMessage)
    }

    @Test
    fun `无片段时重新匹配禁用且不触发出网`() {
        val (viewModel, repository) = newViewModel()
        assertTrue(!viewModel.uiState.value.canRebuild)
        viewModel.rebuild()
        driveIdle()
        assertTrue(repository.rebuildCalls.isEmpty())
    }

    @Test
    fun `有片段时重放成功置计数反馈`() {
        val (viewModel, repository) = newViewModel(
            FakeAuthorRepository().apply { seed += "a.txt" },
        )
        assertTrue(viewModel.uiState.value.canRebuild)
        viewModel.rebuild()
        driveIdle()
        assertEquals(1, repository.rebuildCalls.size)
        val state = viewModel.uiState.value
        // Web L78 逐字：重放完成：N 位作者 · 关联 M 个文件
        assertEquals("重放完成：5 位作者 · 关联 7 个文件", state.noticeMessage)
        assertNull(state.errorMessage)
        assertTrue(!state.rebuilding)
    }

    @Test
    fun `移除片段成功并刷新列表`() {
        val (viewModel, repository) = newViewModel(
            FakeAuthorRepository().apply { seed += listOf("a.txt", "b.txt") },
        )
        viewModel.remove("a.txt")
        driveIdle()
        assertEquals(listOf("a.txt"), repository.removeCalls)
        val state = viewModel.uiState.value
        // Web L70 逐字：已移除「name」，并从剩余片段重建作者关联
        assertEquals("已移除「a.txt」，并从剩余片段重建作者关联", state.noticeMessage)
        // 移除后列表刷新（fake 已从 seed 摘除）
        assertEquals(listOf("b.txt"), state.files)
    }

    @Test
    fun `导入失败置错误态且不出反馈`() {
        val (viewModel, _) = newViewModel(
            FakeAuthorRepository().apply { importError = RuntimeException("boom") },
        )
        viewModel.onFilePicked("清单.txt", "内容")
        driveIdle()
        assertEquals("导入失败，请重试", viewModel.uiState.value.errorMessage)
        assertNull(viewModel.uiState.value.noticeMessage)
        assertTrue(!viewModel.uiState.value.importing)
    }

    @Test
    fun `读文件失败置错误横幅`() {
        val (viewModel, repository) = newViewModel()
        viewModel.onReadFailed()
        assertEquals("读取文件失败，请重试", viewModel.uiState.value.errorMessage)
        assertTrue(repository.importTxtCalls.isEmpty())
    }
}

/**
 * [AuthorRepository] 测试替身：authors/setFollowed 为接口既有方法（本页不消费，给空实现）；
 * TXT 族 seed 模拟服务端片段表（import 同名覆盖落库/remove 摘除），各端点可编程抛错，
 * 调用记录供断言。
 */
private class FakeAuthorRepository : AuthorRepository {

    val seed = mutableListOf<String>()
    var importError: Exception? = null
    var removeError: Exception? = null

    val importTxtCalls = mutableListOf<Pair<String, String>>()
    val removeCalls = mutableListOf<String>()
    val rebuildCalls = mutableListOf<Unit>()

    override suspend fun authors(): List<AuthorSummary> = emptyList()

    override suspend fun setFollowed(authorId: String, followed: Boolean) = Unit

    override suspend fun importedTxtFiles(): List<TxtImportedFile> = seed.map { TxtImportedFile(filename = it) }

    override suspend fun importTxt(filename: String, content: String): TxtImportResult {
        importError?.let { throw it }
        importTxtCalls += filename to content
        // 服务端语义：同名片段覆盖（列表保持一份）
        seed.removeAll { it == filename }
        seed.add(filename)
        return TxtImportResult(authorsImported = 3, filesMatched = 2)
    }

    override suspend fun removeImportedTxt(filename: String) {
        removeCalls.add(filename)
        removeError?.let { throw it }
        seed.removeAll { it == filename }
    }

    override suspend fun rebuildTxt(): TxtImportResult {
        rebuildCalls.add(Unit)
        return TxtImportResult(authorsImported = 5, filesMatched = 7)
    }
}
