package media.qimeng.app.feature.author

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.data.repository.AssetOrigUrlResolver
import media.qimeng.app.core.data.repository.AuthorManageRejectedException
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.model.AuthorSummary
import media.qimeng.app.core.model.AuthorType
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 作者管理页 ViewModel 单测（2026-10-13，ADR-0024 修订 5）：
 * 锁定新建/改名/删除的对话框状态机与调用口径——成功关闭对话框 + 重拉列表 +
 * 提示横幅；4xx 语义化拒绝保持对话框打开并透出服务端原因（自助修正重试）；
 * 输入 rune 封顶（协议 maxLength 同步）与空白提交拦下。
 */
/** 造数助手（非 inner 嵌套类不能引用外层实例方法，故置顶层）。 */
private fun testAuthor(id: String, displayName: String, fileCount: Int = 0) = AuthorSummary(
    id = id,
    displayName = displayName,
    type = AuthorType.REGULAR,
    fileCount = fileCount,
    followed = false,
    viewCount = null,
)

class AuthorViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private class FakeOrigUrlResolver : AssetOrigUrlResolver {
        override suspend fun origUrl(assetId: String): String? = null
    }

    private class FakeAuthorRepository : AuthorRepository {
        var list: List<AuthorSummary> = emptyList()
        var createError: Exception? = null
        var renameError: Exception? = null
        var deleteError: Exception? = null
        val createdNames = mutableListOf<String>()
        val renames = mutableListOf<Pair<String, String>>()
        val deletedIds = mutableListOf<String>()
        var listCalls = 0

        override suspend fun authors(): List<AuthorSummary> {
            listCalls++
            return list
        }

        override suspend fun setFollowed(authorId: String, followed: Boolean) = Unit

        override suspend fun createAuthor(displayName: String): AuthorSummary {
            createError?.let { throw it }
            createdNames += displayName
            return testAuthor(displayName, displayName)
        }

        override suspend fun renameAuthor(authorId: String, displayName: String): AuthorSummary {
            renameError?.let { throw it }
            renames += authorId to displayName
            return testAuthor(authorId, displayName)
        }

        override suspend fun deleteAuthor(authorId: String) {
            deleteError?.let { throw it }
            deletedIds += authorId
        }
    }

    private fun viewModel(repo: FakeAuthorRepository) = AuthorViewModel(repo, FakeOrigUrlResolver())

    @Test
    fun `新建成功：关闭对话框、透传名字、重拉列表并提示`() = runTest {
        val repo = FakeAuthorRepository()
        val vm = viewModel(repo)
        advanceUntilIdle()
        val firstListCalls = repo.listCalls

        vm.openCreateDialog()
        vm.onManageInputChange("  新作者甲  ")
        vm.submitManageDialog()
        advanceUntilIdle()

        assertEquals(listOf("新作者甲"), repo.createdNames.map { it.trim() }.map { it })
        assertNull(vm.uiState.value.manageDialog)
        assertEquals("", vm.uiState.value.manageInput)
        assertTrue(vm.uiState.value.noticeMessage!!.contains("新作者甲"))
        assertTrue(repo.listCalls > firstListCalls) // 成功后重拉
    }

    @Test
    fun `新建被 4xx 拒绝：保持对话框打开并透出服务端原因`() = runTest {
        val repo = FakeAuthorRepository().apply {
            createError = AuthorManageRejectedException("作者名非法：纯数字", 400)
        }
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.openCreateDialog()
        vm.onManageInputChange("12345")
        vm.submitManageDialog()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.manageDialog is AuthorManageDialog.Create) // 保持打开
        assertEquals("操作失败：作者名非法：纯数字", vm.uiState.value.errorMessage)
        assertTrue(repo.createdNames.isEmpty())
    }

    @Test
    fun `改名成功：预填原名、按 id 迁移、关闭并提示`() = runTest {
        val repo = FakeAuthorRepository()
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.openRenameDialog(testAuthor("hornyherring", "hornyherring / Horny Herring Studios", 1))
        assertEquals(
            "hornyherring / Horny Herring Studios",
            vm.uiState.value.manageInput, // 预填当前显示名
        )
        vm.onManageInputChange("hornyherring")
        vm.submitManageDialog()
        advanceUntilIdle()

        assertEquals(listOf("hornyherring" to "hornyherring"), repo.renames)
        assertNull(vm.uiState.value.manageDialog)
        assertTrue(vm.uiState.value.noticeMessage!!.contains("已改名"))
    }

    @Test
    fun `删除成功：透传 id、关闭并明示关联文件保留`() = runTest {
        val repo = FakeAuthorRepository()
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.openDeleteDialog(testAuthor("stray", "Horny Herring Studios", 0))
        vm.submitManageDialog()
        advanceUntilIdle()

        assertEquals(listOf("stray"), repo.deletedIds)
        assertNull(vm.uiState.value.manageDialog)
        assertTrue(vm.uiState.value.noticeMessage!!.contains("关联文件已保留"))
    }

    @Test
    fun `空白输入不提交；输入按 200 rune 封顶`() = runTest {
        val repo = FakeAuthorRepository()
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.openCreateDialog()
        vm.onManageInputChange("   ")
        vm.submitManageDialog()
        advanceUntilIdle()
        assertTrue(repo.createdNames.isEmpty())

        vm.onManageInputChange("甲")
        val tooLong = "甲".repeat(201)
        vm.onManageInputChange(tooLong)
        assertEquals("甲", vm.uiState.value.manageInput) // 超限整串拒收，保留原值
        val atLimit = "甲".repeat(200)
        vm.onManageInputChange(atLimit)
        assertEquals(atLimit, vm.uiState.value.manageInput)
    }

    @Test
    fun `在途提交期间关闭对话框被忽略`() = runTest {
        val repo = FakeAuthorRepository()
        val vm = viewModel(repo)
        advanceUntilIdle()

        vm.openDeleteDialog(testAuthor("x", "x"))
        // 直接置在途态（提交与关闭同帧竞争的场景；提交中忽略 dismiss）
        vm.openDeleteDialog(testAuthor("x", "x"))
        vm.submitManageDialog()
        vm.dismissManageDialog()
        advanceUntilIdle()
        assertEquals(listOf("x"), repo.deletedIds)
    }
}
