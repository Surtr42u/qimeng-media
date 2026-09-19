package media.qimeng.app.feature.manage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import media.qimeng.app.core.data.prefetch.PrefetchUiState
import media.qimeng.app.core.data.prefetch.ThumbnailPrefetchMonitor
import media.qimeng.app.core.data.repository.CoilCacheManager
import media.qimeng.app.core.data.repository.DiskCachePrefsRepository
import media.qimeng.app.core.data.repository.ThumbnailProgressRepository
import media.qimeng.app.core.model.DiskCacheQuota
import media.qimeng.app.core.model.ThumbnailCacheProgress
import media.qimeng.app.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 缩略图缓存页 VM 状态机锁定（批S4 2026-09-19）：本机两口径（字节/条目数）读失败
 * 降级 null、服务端进度读失败降级 null 不弹横幅、清空后归零核对、档位写成败两路、
 * 刷新防重、预取状态只读透出（预取为纯默认自动行为，VM 无启停意图面——批S4 删）。
 * fake 只在测试源集内（LibraryManageViewModelTest 同款先例）。
 *
 * 调度口径：Main 由 [MainDispatcherRule] 换成测试调度器；VM 协程全在该调度器上，
 * 测试体显式 `scheduler.advanceUntilIdle()` 驱动（不进 runTest——VM 作用域协程
 * 不是 runTest 子协程，双调度器语义易错，同模块既有 VM 测试同款）。
 */
class ThumbnailCacheViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** IO 调度器测试替身：与 Main 共享同一调度器时钟，虚拟时间可控 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val ioDispatcher = UnconfinedTestDispatcher(mainDispatcherRule.testDispatcher.scheduler)

    private fun driveIdle() = mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

    private class FakeDiskCachePrefsRepository : DiskCachePrefsRepository {
        override val quota = MutableStateFlow(DiskCacheQuota.DEFAULT)
        var setError: Throwable? = null
        override suspend fun setQuota(quota: DiskCacheQuota) {
            setError?.let { throw it }
            this.quota.value = quota
        }
    }

    private class FakeCoilCacheManager(
        var size: Long? = 1234L,
        var count: Int? = 42,
    ) : CoilCacheManager {
        var clearCalls = 0
        override fun clear() {
            clearCalls++
            // 真实 DiskCache.clear() 清空后 size/条目数归零，VM 重读核对依赖该语义
            size = 0L
            count = 0
        }

        override fun sizeBytes(): Long? = size ?: error("size 未就绪")
        override fun fileCount(): Int? = count ?: error("count 未就绪")
        override fun capacityBytes(): Long? = null
    }

    private class FakeProgressRepository : ThumbnailProgressRepository {
        var progress: ThumbnailCacheProgress? = null
        var error: Throwable? = null
        /** 非空时挂起等待，模拟在途响应（防重/竞态用例） */
        var gate: CompletableDeferred<Unit>? = null
        var calls = 0
        override suspend fun progress(): ThumbnailCacheProgress? {
            calls++
            gate?.await()
            error?.let { throw it }
            return progress
        }
    }

    private class FakePrefetchMonitor(
        initial: PrefetchUiState = PrefetchUiState.Idle,
    ) : ThumbnailPrefetchMonitor {
        override val state = MutableStateFlow<PrefetchUiState>(initial)
    }

    private fun newViewModel(
        prefs: FakeDiskCachePrefsRepository = FakeDiskCachePrefsRepository(),
        coil: FakeCoilCacheManager = FakeCoilCacheManager(),
        progress: FakeProgressRepository = FakeProgressRepository(),
        prefetch: FakePrefetchMonitor = FakePrefetchMonitor(),
    ) = ThumbnailCacheViewModel(prefs, coil, progress, prefetch, ioDispatcher)

    @Test
    fun `init读取本机占用与文件数`() {
        val viewModel = newViewModel()
        driveIdle()
        val state = viewModel.uiState.value
        assertEquals(1234L, state.cacheSizeBytes)
        assertEquals(42, state.cacheFileCount)
        assertNull(state.writeError)
    }

    @Test
    fun `本机读数失败降级null不崩`() {
        val viewModel = newViewModel(coil = FakeCoilCacheManager(size = null, count = null))
        driveIdle()
        val state = viewModel.uiState.value
        assertNull(state.cacheSizeBytes)
        assertNull(state.cacheFileCount)
    }

    @Test
    fun `refresh拉取服务端进度`() {
        val progress = FakeProgressRepository().apply {
            this.progress = ThumbnailCacheProgress(totalAssets = 6341, thumbsOnDisk = 6477)
        }
        val viewModel = newViewModel(progress = progress)
        driveIdle()
        val state = viewModel.uiState.value
        assertNotNull(state.progress)
        assertEquals(6341, state.progress?.totalAssets)
        assertEquals(6477, state.progress?.thumbsOnDisk)
        assertEquals(1, progress.calls)
        assertTrue(!state.progressLoading)
    }

    @Test
    fun `进度读失败降级null不弹横幅`() {
        val progress = FakeProgressRepository().apply { error = RuntimeException("boom") }
        val viewModel = newViewModel(progress = progress)
        driveIdle()
        val state = viewModel.uiState.value
        assertNull(state.progress)
        assertNull(state.writeError)
        assertTrue(!state.progressLoading)
    }

    @Test
    fun `刷新防重进行中忽略后续点击`() {
        val progress = FakeProgressRepository().apply { gate = CompletableDeferred() }
        val viewModel = newViewModel(progress = progress)
        driveIdle()
        // 进页那次刷新已在途挂起（loading=true）
        assertEquals(1, progress.calls)
        viewModel.refresh()
        progress.gate?.complete(Unit)
        driveIdle()
        // 防重命中：在途期间的「刷新」未进仓库
        assertEquals(1, progress.calls)
        assertTrue(!viewModel.uiState.value.progressLoading)
    }

    @Test
    fun `清空后本机两口径归零核对`() {
        val coil = FakeCoilCacheManager()
        val viewModel = newViewModel(coil = coil)
        driveIdle()
        viewModel.clearCache()
        driveIdle()
        assertEquals(1, coil.clearCalls)
        assertEquals(0L, viewModel.uiState.value.cacheSizeBytes)
        assertEquals(0, viewModel.uiState.value.cacheFileCount)
    }

    @Test
    fun `切档成功更新档位`() {
        val prefs = FakeDiskCachePrefsRepository()
        val viewModel = newViewModel(prefs = prefs)
        driveIdle()
        viewModel.setQuota(DiskCacheQuota.GB2)
        driveIdle()
        assertEquals(DiskCacheQuota.GB2, viewModel.uiState.value.quota)
        assertEquals(DiskCacheQuota.GB2, prefs.quota.value)
    }

    @Test
    fun `切档失败置横幅且档位不动`() {
        val prefs = FakeDiskCachePrefsRepository().apply { setError = RuntimeException("boom") }
        val viewModel = newViewModel(prefs = prefs)
        driveIdle()
        viewModel.setQuota(DiskCacheQuota.GB5)
        driveIdle()
        val state = viewModel.uiState.value
        assertEquals("保存失败，请重试", state.writeError)
        assertEquals(DiskCacheQuota.DEFAULT, state.quota)
        viewModel.dismissWriteError()
        assertNull(viewModel.uiState.value.writeError)
    }

    @Test
    fun `预取状态只读透出`() {
        val viewModel = newViewModel(
            prefetch = FakePrefetchMonitor(PrefetchUiState.Running(done = 10, total = 20)),
        )
        driveIdle()
        val state = viewModel.prefetchState.value
        assertTrue(state is PrefetchUiState.Running)
        val running = state as PrefetchUiState.Running
        assertEquals(10, running.done)
        assertEquals(20, running.total)
    }
}
