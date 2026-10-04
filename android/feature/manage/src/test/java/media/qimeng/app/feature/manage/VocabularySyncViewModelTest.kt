package media.qimeng.app.feature.manage

import media.qimeng.app.core.data.repository.VocabularySyncError
import media.qimeng.app.core.data.repository.VocabularySyncException
import media.qimeng.app.core.data.repository.VocabularySyncRepository
import media.qimeng.app.core.data.repository.VocabularySyncResult
import media.qimeng.app.core.testing.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 词表合并同步 ViewModel 状态机锁定（ADR-0035 定稿语义：一键合并、无方向/无预览段）：
 * 忙态防重 / 成功提示带两端收敛规模与补入增量 / 错误分类→文案映射（门禁/远端拉取/
 * 远端写入/本机通道/本机写入五支）。fake 只在测试源集内（BackupViewModelTest 同款模式）。
 */
class VocabularySyncViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun driveIdle() = mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

    private class FakeVocabularySyncRepository : VocabularySyncRepository {
        var syncCalls = 0
        var syncResult: Result<VocabularySyncResult> = Result.success(
            VocabularySyncResult(
                mergedGroupCount = 135,
                mergedStopWordCount = 7,
                newFromRemoteCount = 2,
                newFromLocalCount = 4,
            ),
        )

        /** 非空时 sync 挂起直到完成（忙态防重用例的栅栏） */
        var syncGate: CompletableDeferred<Unit>? = null

        override suspend fun sync(): Result<VocabularySyncResult> {
            syncCalls += 1
            syncGate?.await()
            return syncResult
        }
    }

    private fun newViewModel(
        repository: FakeVocabularySyncRepository = FakeVocabularySyncRepository(),
    ) = Pair(repository, VocabularySyncViewModel(repository).also { driveIdle() })

    @Test
    fun `一键同步成功提示含两端规模与补入增量`() = runTest {
        val (_, vm) = newViewModel()
        vm.sync()
        driveIdle()
        val notice = vm.uiState.value.noticeMessage
        assertTrue(notice!!.contains("135 组"))
        assertTrue(notice.contains("7 个停用词"))
        assertTrue(notice.contains("远端补入 2 组"))
        assertTrue(notice.contains("本机补入 4 组"))
        assertFalse(vm.uiState.value.syncing)
    }

    @Test
    fun `忙态防重不重复出网`() = runTest {
        val (repo, vm) = newViewModel()
        repo.syncGate = CompletableDeferred()
        vm.sync()
        driveIdle()
        vm.sync()
        driveIdle()
        assertEquals(1, repo.syncCalls)
        assertTrue(vm.uiState.value.syncing)
        repo.syncGate!!.complete(Unit)
        driveIdle()
        assertFalse(vm.uiState.value.syncing)
    }

    @Test
    fun `本机模式门禁映射为登录提示`() = runTest {
        val (repo, vm) = newViewModel()
        repo.syncResult = Result.failure(
            VocabularySyncException(VocabularySyncError.NoAuthoritativeSource),
        )
        vm.sync()
        driveIdle()
        assertTrue(vm.uiState.value.errorMessage!!.contains("请先登录"))
        assertNull(vm.uiState.value.noticeMessage)
        assertFalse(vm.uiState.value.syncing)
    }

    @Test
    fun `远端写入失败映射专属文案`() = runTest {
        val (repo, vm) = newViewModel()
        repo.syncResult = Result.failure(
            VocabularySyncException(VocabularySyncError.RemoteApplyFailed),
        )
        vm.sync()
        driveIdle()
        assertTrue(vm.uiState.value.errorMessage!!.contains("写入 NAS/电脑端词表失败"))
    }

    @Test
    fun `本机通道失败映射专属文案`() = runTest {
        val (repo, vm) = newViewModel()
        repo.syncResult = Result.failure(
            VocabularySyncException(VocabularySyncError.LocalServerUnavailable),
        )
        vm.sync()
        driveIdle()
        assertTrue(vm.uiState.value.errorMessage!!.contains("本机词表服务不可用"))
    }

    @Test
    fun `错误后可重试且状态收口`() = runTest {
        val (repo, vm) = newViewModel()
        repo.syncResult = Result.failure(
            VocabularySyncException(VocabularySyncError.RemoteFetchFailed),
        )
        vm.sync()
        driveIdle()
        assertTrue(vm.uiState.value.errorMessage!!.contains("拉取远端词表失败"))
        repo.syncResult = Result.success(VocabularySyncResult(134, 6, 1, 1))
        vm.sync()
        driveIdle()
        assertEquals(2, repo.syncCalls)
        assertNull(vm.uiState.value.errorMessage)
        assertTrue(vm.uiState.value.noticeMessage!!.contains("合并完成"))
    }
}
