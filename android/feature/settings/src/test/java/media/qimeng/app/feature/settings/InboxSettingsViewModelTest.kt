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
 * 上传归档文件夹设置页 ViewModel 锁定（2026-09-29 直传化收窄为归档单目标）：
 * 授权门控（未授权整页引导态、不发起目录浏览）、目录导航（下钻/上一级/根目录钳制）、
 * 归档文件夹选定与清除（持久化写路径）、浏览器展开/收起/重开（回显决策语义保留）。
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
    fun `init授权且回放持久化归档路径并从根目录起步`() {
        val staging = fakeStaging().apply { seedArchivePath("/storage/emulated/0/archive") }
        val viewModel = InboxSettingsViewModel(staging)
        driveIdle()
        val state = viewModel.uiState.value
        assertTrue(state.allFilesGranted)
        assertEquals("/storage/emulated/0", state.storageRoot)
        assertEquals("/storage/emulated/0", state.browsingPath)
        assertEquals(listOf(".download", "DCIM"), state.entries.map { it.name })
        assertEquals("/storage/emulated/0/archive", state.selectedArchivePath)
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

        viewModel.selectCurrentAsArchive()
        driveIdle()
        assertEquals(listOf("/storage/emulated/0/.download"), staging.archivePathCalls)
        assertEquals("/storage/emulated/0/.download", viewModel.uiState.value.selectedArchivePath)
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
    fun `清除归档文件夹写null且选定归空`() {
        val staging = fakeStaging().apply { seedArchivePath("/storage/emulated/0/archive") }
        val viewModel = InboxSettingsViewModel(staging)
        driveIdle()
        viewModel.clearArchive()
        driveIdle()
        assertNull(viewModel.uiState.value.selectedArchivePath)
        assertEquals(listOf<String?>(null), staging.archivePathCalls)
    }

    // ---- 浏览器收起与再开（2026-09-28 装机直报：选完文件夹即收起，重新选择再开） ----

    @Test
    fun `初始浏览器可见且选定归档文件夹后收起再开保留浏览位置`() {
        val viewModel = InboxSettingsViewModel(fakeStaging())
        driveIdle()
        assertTrue(viewModel.uiState.value.browserVisible)

        viewModel.enter("/storage/emulated/0/.download")
        driveIdle()
        viewModel.selectCurrentAsArchive()
        driveIdle()
        assertFalse(viewModel.uiState.value.browserVisible)

        // 重新选择：只翻可见位，浏览位置不清零（免重新逐层下钻）
        viewModel.reopenBrowser()
        assertEquals(true, viewModel.uiState.value.browserVisible)
        assertEquals("/storage/emulated/0/.download", viewModel.uiState.value.browsingPath)
    }

    // ---- 回显决策（2026-09-29 修复，语义保留：未设归档文件夹才展开，清空重开） ----

    @Test
    fun `已设置归档文件夹进页浏览器不展开`() {
        val staging = fakeStaging().apply { seedArchivePath("/storage/emulated/0/archive") }
        val viewModel = InboxSettingsViewModel(staging)
        driveIdle()
        assertEquals("/storage/emulated/0/archive", viewModel.uiState.value.selectedArchivePath)
        // 已有选定：回显当前值卡 + 「重新选择」，不再回显「选择文件夹」浏览器
        assertFalse(viewModel.uiState.value.browserVisible)
    }

    @Test
    fun `清除唯一选定后浏览器重开保住再选入口`() {
        val staging = fakeStaging().apply { seedArchivePath("/storage/emulated/0/archive") }
        val viewModel = InboxSettingsViewModel(staging)
        driveIdle()
        assertFalse(viewModel.uiState.value.browserVisible)

        viewModel.clearArchive()
        driveIdle()
        assertNull(viewModel.uiState.value.selectedArchivePath)
        // 归空：当前值卡不再渲染「重新选择」，浏览器必须重开（唯一再选入口）
        assertTrue(viewModel.uiState.value.browserVisible)
    }
}
