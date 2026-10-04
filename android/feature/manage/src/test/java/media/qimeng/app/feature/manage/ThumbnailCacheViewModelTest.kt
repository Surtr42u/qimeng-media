package media.qimeng.app.feature.manage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import media.qimeng.app.core.data.coil.CachePool
import media.qimeng.app.core.data.prefetch.PrefetchUiState
import media.qimeng.app.core.data.prefetch.PrefetchRevisionRecord
import media.qimeng.app.core.data.prefetch.PrefetchRevisionStore
import media.qimeng.app.core.data.prefetch.ThumbnailPrefetchMonitor
import media.qimeng.app.core.data.repository.CoilCacheManager
import media.qimeng.app.core.data.repository.ThumbnailProgressRepository
import media.qimeng.app.core.model.ThumbnailCacheProgress
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.testing.FakeAuthRepository
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

    private fun driveIdle() = mainDispatcherRule.testDispatcher.scheduler.let {
        // 有界虚拟时间推进（2026-10-04 批）：VM init 的本地端生成进度轮询是
        // while(true)+delay(1s) 的无限循环，advanceUntilIdle 的「跑到无任务」语义
        // 会永不返回；advanceTimeBy(有界) 让循环在虚拟时间窗内执行 N 轮后自然停。
        // 页内既有断言（init 读取/清空核对）全部落在首秒内，语义不变。
        it.advanceTimeBy(TEST_VIRTUAL_BUDGET_MS)
        it.runCurrent()
    }

    /** 覆盖进度端口桩：progress() 返回值可编程；result=null 模拟「读失败降级 null」 */
    private class FakeThumbnailProgressRepository(
        var result: ThumbnailCacheProgress? = ThumbnailCacheProgress(totalAssets = 0, thumbsOnDisk = 0),
    ) : ThumbnailProgressRepository {
        var callCount = 0
        override suspend fun progress(): ThumbnailCacheProgress? {
            callCount++
            return result
        }
    }

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

    /** 预取修订号仓状态桩：记录当前值与 clear 调用次数（清池失效接线验证用；接口扩为
     *  三元组后的最小机械适配——本页只消费 clear 语义，样本/lastDone 桩给空实现） */
    private class FakePrefetchRevisionStore : PrefetchRevisionStore {
        var stored: PrefetchRevisionRecord? = null
        var clearedCount = 0

        override suspend fun lastDone(): PrefetchRevisionRecord? = stored

        override suspend fun setLastDone(record: PrefetchRevisionRecord, sampleUrls: Set<String>) {
            stored = record
        }

        override suspend fun storedSample(): Set<String> = emptySet()

        override suspend fun clear() {
            stored = null
            clearedCount++
        }
    }

    private fun newViewModel(
        coil: FakeCoilCacheManager = FakeCoilCacheManager(),
        prefetch: FakePrefetchMonitor = FakePrefetchMonitor(),
        revisionStore: FakePrefetchRevisionStore = FakePrefetchRevisionStore(),
        auth: FakeAuthRepository = FakeAuthRepository(initialServerUrl = ServerAddress.LOCAL_MODE_PRESET),
        progress: FakeThumbnailProgressRepository = FakeThumbnailProgressRepository(),
    ) = ThumbnailCacheViewModel(coil, prefetch, revisionStore, progress, auth, ioDispatcher)

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
    fun `清空任一缓存池后预取修订号记录失效`() {
        // P1（清池失效接线）：预置「上一轮完成」记录模拟稳态，清 NAS 池 → clear 一次且
        // 记录读回 null（下轮门判 FULL 补拉）；清本地池同样失效（修订号全局不分池）
        val coil = FakeCoilCacheManager()
        val store = FakePrefetchRevisionStore().apply {
            stored = PrefetchRevisionRecord(serverKey = "http://stub.local", revision = 42L)
        }
        val viewModel = newViewModel(coil = coil, revisionStore = store)
        driveIdle()

        viewModel.clearNasCache()
        driveIdle()
        assertEquals(1, store.clearedCount)
        assertEquals(null, store.stored)

        viewModel.clearLocalCache()
        driveIdle()
        assertEquals(2, store.clearedCount)
        assertEquals(null, store.stored)
    }

    @Test
    fun `本地端模式生成进度透出`() {
        val viewModel = newViewModel(
            auth = FakeAuthRepository(initialServerUrl = ServerAddress.LOCAL_MODE_PRESET),
            progress = FakeThumbnailProgressRepository(
                ThumbnailCacheProgress(totalAssets = 6350, thumbsOnDisk = 2256),
            ),
        )
        driveIdle()
        val state = viewModel.uiState.value
        assertEquals(6350, state.localGenTotal)
        assertEquals(2256, state.localGenCovered)
    }

    @Test
    fun `NAS模式生成进度整块隐藏`() {
        val viewModel = newViewModel(
            auth = FakeAuthRepository(initialServerUrl = "http://192.0.2.8:8420"),
            progress = FakeThumbnailProgressRepository(
                ThumbnailCacheProgress(totalAssets = 6350, thumbsOnDisk = 2256),
            ),
        )
        driveIdle()
        assertNull(viewModel.uiState.value.localGenCovered)
        assertNull(viewModel.uiState.value.localGenTotal)
    }

    @Test
    fun `生成进度读失败降级null不崩`() {
        val viewModel = newViewModel(
            auth = FakeAuthRepository(initialServerUrl = ServerAddress.LOCAL_MODE_PRESET),
            progress = FakeThumbnailProgressRepository(result = null),
        )
        driveIdle()
        assertNull(viewModel.uiState.value.localGenCovered)
        assertNull(viewModel.uiState.value.localGenTotal)
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

    @Test
    fun `预取跳过态只读透出`() {
        // revision 未变整轮跳过（2026-09-30）：VM 对新终态只透出不干预，也不触发重采样
        val coil = FakeCoilCacheManager(nasCount = 42)
        val monitor = FakePrefetchMonitor()
        val viewModel = newViewModel(coil = coil, prefetch = monitor)
        driveIdle()
        monitor.state.value = PrefetchUiState.Skipped
        driveIdle()
        assertTrue(viewModel.prefetchState.value is PrefetchUiState.Skipped)
        assertEquals(42, viewModel.uiState.value.nasFileCount)
    }

    @Test
    fun `预取进行中文件数占用随进度重采样刷新`() {
        // 第三百六十六笔：数字跟着进度条走——Running 发射触发重采样（total=50 → 步长 1 逐条刷），
        // Done 终采一次收口
        val coil = FakeCoilCacheManager(nasCount = 0)
        val monitor = FakePrefetchMonitor()
        val viewModel = newViewModel(coil = coil, prefetch = monitor)
        driveIdle()
        assertEquals(0, viewModel.uiState.value.nasFileCount)

        monitor.state.value = PrefetchUiState.Running(done = 1, total = 50)
        coil.nasCount = 1
        driveIdle()
        assertEquals(1, viewModel.uiState.value.nasFileCount)

        monitor.state.value = PrefetchUiState.Done(done = 50, total = 50)
        coil.nasCount = 50
        driveIdle()
        assertEquals(50, viewModel.uiState.value.nasFileCount)
    }
}

/**
 * 测试虚拟时间预算（driveIdle 有界推进窗口）：60s = 60 轮进度轮询，既有断言
 * 全部落在首秒内；有界推进是 while(true) 轮询循环下测试可终止的前提。
 */
private const val TEST_VIRTUAL_BUDGET_MS = 60_000L
