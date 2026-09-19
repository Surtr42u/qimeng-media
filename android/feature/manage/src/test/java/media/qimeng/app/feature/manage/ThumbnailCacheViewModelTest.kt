package media.qimeng.app.feature.manage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import media.qimeng.app.core.data.coil.CachePool
import media.qimeng.app.core.data.prefetch.PrefetchUiState
import media.qimeng.app.core.data.prefetch.ThumbnailPrefetchMonitor
import media.qimeng.app.core.data.repository.CoilCacheManager
import media.qimeng.app.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 缩略图缓存页 VM 状态机锁定（批S5 2026-09-19 分池重写）：两池四口径（NAS/本地池 ×
 * 字节/条目数）读失败降级 null、分池清空只清目标池并重读归零、预取状态只读透出
 * （预取为纯默认自动行为，VM 无启停意图面——批S4 删；批S5 起档位/服务端进度口径退场）。
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

    private class FakeCoilCacheManager(
        var nasSize: Long = 1234L,
        var nasCount: Int = 42,
        var localSize: Long = 500L,
        var localCount: Int = 7,
    ) : CoilCacheManager {
        val clearedPools = mutableListOf<CachePool>()
        /** 非空时读口径抛错（模拟磁盘扫描失败，VM 降级 null 路径） */
        var errorOnRead: Throwable? = null

        override fun clearPool(pool: CachePool) {
            clearedPools += pool
            // 真实子池 DiskCache.clear() 清空后 size/条目数归零，VM 重读核对依赖该语义
            when (pool) {
                CachePool.NAS -> { nasSize = 0L; nasCount = 0 }
                CachePool.LOCAL -> { localSize = 0L; localCount = 0 }
            }
        }

        override fun poolSizeBytes(pool: CachePool): Long {
            errorOnRead?.let { throw it }
            return when (pool) {
                CachePool.NAS -> nasSize
                CachePool.LOCAL -> localSize
            }
        }

        override fun poolFileCount(pool: CachePool): Int {
            errorOnRead?.let { throw it }
            return when (pool) {
                CachePool.NAS -> nasCount
                CachePool.LOCAL -> localCount
            }
        }
    }

    private class FakePrefetchMonitor(
        initial: PrefetchUiState = PrefetchUiState.Idle,
    ) : ThumbnailPrefetchMonitor {
        override val state = MutableStateFlow<PrefetchUiState>(initial)
    }

    private fun newViewModel(
        coil: FakeCoilCacheManager = FakeCoilCacheManager(),
        prefetch: FakePrefetchMonitor = FakePrefetchMonitor(),
    ) = ThumbnailCacheViewModel(coil, prefetch, ioDispatcher)

    @Test
    fun `init读取两池四口径`() {
        val viewModel = newViewModel()
        driveIdle()
        val state = viewModel.uiState.value
        assertEquals(1234L, state.nasSizeBytes)
        assertEquals(42, state.nasFileCount)
        assertEquals(500L, state.localSizeBytes)
        assertEquals(7, state.localFileCount)
    }

    @Test
    fun `读失败两池四口径降级null不崩`() {
        val viewModel = newViewModel(coil = FakeCoilCacheManager().apply { errorOnRead = RuntimeException("boom") })
        driveIdle()
        val state = viewModel.uiState.value
        assertNull(state.nasSizeBytes)
        assertNull(state.nasFileCount)
        assertNull(state.localSizeBytes)
        assertNull(state.localFileCount)
    }

    @Test
    fun `清空服务器缓存只清NAS池并归零核对`() {
        val coil = FakeCoilCacheManager()
        val viewModel = newViewModel(coil = coil)
        driveIdle()
        viewModel.clearNasCache()
        driveIdle()
        assertEquals(listOf(CachePool.NAS), coil.clearedPools)
        assertEquals(0L, viewModel.uiState.value.nasSizeBytes)
        assertEquals(0, viewModel.uiState.value.nasFileCount)
        // 本地池不受牵连
        assertEquals(500L, viewModel.uiState.value.localSizeBytes)
        assertEquals(7, viewModel.uiState.value.localFileCount)
    }

    @Test
    fun `清空本地缓存只清本地池并归零核对`() {
        val coil = FakeCoilCacheManager()
        val viewModel = newViewModel(coil = coil)
        driveIdle()
        viewModel.clearLocalCache()
        driveIdle()
        assertEquals(listOf(CachePool.LOCAL), coil.clearedPools)
        assertEquals(0L, viewModel.uiState.value.localSizeBytes)
        assertEquals(0, viewModel.uiState.value.localFileCount)
        // NAS 池不受牵连
        assertEquals(1234L, viewModel.uiState.value.nasSizeBytes)
        assertEquals(42, viewModel.uiState.value.nasFileCount)
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
