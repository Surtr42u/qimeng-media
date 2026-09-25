package media.qimeng.app.feature.settings

import media.qimeng.app.core.data.repository.InboxDirEntry
import media.qimeng.app.core.testing.FakeStagingRepository
import media.qimeng.app.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 下载收件箱设置页 ViewModel 锁定（2026-09-25 暂存区重做）：授权门控（未授权整页引导态、
 * 不发起目录浏览）、目录导航（下钻/上一级/根目录钳制）、收件箱选定与清除（持久化写路径）。
 * 目录列表数据面是 File 真实现（InboxFileStoreTest 临时目录直测），本类锁导航编排逻辑。
 */
class InboxSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun driveIdle() = mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

    private fun fakeStaging(granted: Boolean = true) = FakeStagingRepository().apply {
        allFilesAccess = granted
        directories = { path ->
            when (path) {
                "/storage/emulated/0" -> listOf(
                    InboxDirEntry(name = ".download", path = "/storage/emulated/0/.download"),
                    InboxDirEntry(name = "DCIM", path = "/storage/emulated/0/DCIM"),
                )
                "/storage/emulated/0/.download" -> emptyList()
                else -> emptyList()
            }
        }
    }

    @Test
    fun `init授权且回放持久化收件箱并从根目录起步`() {
        val staging = fakeStaging().apply { seedInboxPath("/storage/emulated/0/.download") }
        val viewModel = InboxSettingsViewModel(staging)
        driveIdle()
        val state = viewModel.uiState.value
        assertTrue(state.allFilesGranted)
        assertEquals("/storage/emulated/0", state.storageRoot)
        assertEquals("/storage/emulated/0", state.browsingPath)
        assertEquals(listOf(".download", "DCIM"), state.entries.map { it.name })
        assertEquals("/storage/emulated/0/.download", state.selectedInboxPath)
        assertNull(state.errorMessage)
    }

    @Test
    fun `未授权进引导态且不发目录浏览请求`() {
        var browseCalls = 0
        val staging = fakeStaging(granted = false).apply {
            directories = { browseCalls++; emptyList() }
        }
        val viewModel = InboxSettingsViewModel(staging)
        driveIdle()
        val state = viewModel.uiState.value
        assertFalse(state.allFilesGranted)
        assertEquals(0, browseCalls)
        assertTrue(state.entries.isEmpty())
    }

    @Test
    fun `点选子目录下钻且选定当前目录持久化`() {
        val staging = fakeStaging()
        val viewModel = InboxSettingsViewModel(staging)
        driveIdle()

        viewModel.enter("/storage/emulated/0/.download")
        driveIdle()
        assertEquals("/storage/emulated/0/.download", viewModel.uiState.value.browsingPath)

        viewModel.selectCurrentAsInbox()
        driveIdle()
        assertEquals(listOf("/storage/emulated/0/.download"), staging.inboxPathCalls)
        assertEquals("/storage/emulated/0/.download", viewModel.uiState.value.selectedInboxPath)
    }

    @Test
    fun `上一级回父目录且根目录再上一级不动`() {
        val viewModel = InboxSettingsViewModel(fakeStaging())
        driveIdle()

        viewModel.enter("/storage/emulated/0/.download")
        driveIdle()
        viewModel.goUp()
        driveIdle()
        assertEquals("/storage/emulated/0", viewModel.uiState.value.browsingPath)

        // 根目录上一级：仍停留根目录（不越出主存储根）
        viewModel.goUp()
        driveIdle()
        assertEquals("/storage/emulated/0", viewModel.uiState.value.browsingPath)
    }

    @Test
    fun `清除收件箱写null且选定归空`() {
        val staging = fakeStaging().apply { seedInboxPath("/storage/emulated/0/.download") }
        val viewModel = InboxSettingsViewModel(staging)
        driveIdle()
        viewModel.clearInbox()
        driveIdle()
        assertNull(viewModel.uiState.value.selectedInboxPath)
        assertEquals(listOf<String?>(null), staging.inboxPathCalls)
    }
}
