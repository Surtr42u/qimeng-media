package media.qimeng.app.feature.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.data.events.PendingViewEventDao
import media.qimeng.app.core.data.events.PendingViewEventEntity
import media.qimeng.app.core.data.events.ViewEventQueue
import media.qimeng.app.core.data.events.ViewEventSender
import media.qimeng.app.core.data.events.ViewEventSendResult
import media.qimeng.app.core.data.repository.AuthRepository
import media.qimeng.app.core.data.repository.CoilCacheManager
import media.qimeng.app.core.data.repository.DiskCachePrefsRepository
import media.qimeng.app.core.data.repository.RecommendPrefsRepository
import media.qimeng.app.core.data.repository.StatsRepository
import media.qimeng.app.core.data.repository.SystemInfoRepository
import media.qimeng.app.core.model.DiskCacheQuota
import media.qimeng.app.core.model.RecommendPrefsValues
import media.qimeng.app.core.model.RecommendPreset
import media.qimeng.app.core.model.StatsOverviewValues
import media.qimeng.app.core.model.TrendPoint
import media.qimeng.app.core.model.toPrefsValues
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.testing.FakeAuthRepository
import media.qimeng.app.core.testing.MainDispatcherRule

/** 与 SettingsViewModel 私有常量对齐的反馈文案（文案属反馈契约，ViewModel 侧改动须同步此处） */
private const val SAVE_FAILED_TEXT = "保存失败，请重试"

/**
 * 我的页 ViewModel 单测（M4-6）：登出会话闭环（M4-1 原语义）+ 预设应用（C4）+
 * 档位持久化/清空（C5）+ 服务端版本（C6）。
 * X5 批 2026-09-12：作者总览卡退役（我的页改收藏同款入口行），总览聚合/读失败用例
 * 与 FakeAuthorRepository 随之移除；纯计数/排序仍由 :core:model 单测锁定。
 */
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ---------- 测试替身（最小行为，只满足本页编排断言） ----------

    private class FakePrefsRepository : RecommendPrefsRepository {
        var current: RecommendPrefsValues? = RecommendPreset.BALANCED.toPrefsValues()
        val putCalls = mutableListOf<RecommendPrefsValues>()

        /** 可编程：非空时 putPrefs 抛出（P2-3 失败路径） */
        var putError: Throwable? = null

        override suspend fun prefs(): RecommendPrefsValues = current ?: RecommendPreset.BALANCED.toPrefsValues()

        override suspend fun putPrefs(values: RecommendPrefsValues) {
            putError?.let { throw it }
            putCalls += values
            current = values
        }
    }

    private class FakeSystemInfoRepository(private val version: String?) : SystemInfoRepository {
        override suspend fun serverVersion(): String? = version
    }

    /** 数量卡替身（I4）：overview 可编程抛出（读失败降级路径锁定）；trends 本页不消费 */
    private class FakeStatsRepository(
        private val overview: StatsOverviewValues?,
    ) : StatsRepository {
        override suspend fun overview(): StatsOverviewValues =
            overview ?: throw RuntimeException("network down")

        override suspend fun trends(range: String): List<TrendPoint> = emptyList()

        override suspend fun trends(range: String, mediaType: String?): List<TrendPoint> = emptyList()
    }

    private class FakeDiskCachePrefsRepository : DiskCachePrefsRepository {
        private val quotaFlow = MutableStateFlow(DiskCacheQuota.DEFAULT)

        /** 可编程：非空时 setQuota 抛出（P2-3 失败路径） */
        var setError: Throwable? = null

        override val quota: kotlinx.coroutines.flow.Flow<DiskCacheQuota> = quotaFlow

        override suspend fun setQuota(quota: DiskCacheQuota) {
            setError?.let { throw it }
            quotaFlow.value = quota
        }
    }

    private class FakeCoilCacheManager : CoilCacheManager {
        var cleared = 0
        var size: Long? = 5L * 1024 * 1024

        override fun clear() {
            cleared++
            size = 0L
        }

        override fun sizeBytes(): Long? = size

        override fun capacityBytes(): Long = DiskCacheQuota.DEFAULT.bytes
    }

    /** 内存事件队列 DAO（任务L L5：立即同步/导出用例；语义按接口契约复刻） */
    private class FakeEventDao : PendingViewEventDao {
        val rows = mutableListOf<PendingViewEventEntity>()
        private var nextId = 1L

        override suspend fun insert(entity: PendingViewEventEntity): Long {
            val id = nextId++
            rows += entity.copy(id = id)
            return id
        }

        override suspend fun evictBeyondLimit(limit: Int) {
            if (rows.size <= limit) return
            val keep = rows.sortedByDescending { it.id }.take(limit).map { it.id }.toSet()
            rows.removeAll { it.id !in keep }
        }

        override suspend fun selectDue(now: Long, limit: Int): List<PendingViewEventEntity> =
            rows.filter { !it.terminal && it.nextAttemptAt <= now }.sortedBy { it.id }.take(limit)

        override suspend fun deleteByIds(ids: List<Long>) {
            rows.removeAll { it.id in ids }
        }

        override suspend fun reschedule(id: Long, nextAttemptAt: Long) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = rows[i].copy(nextAttemptAt = nextAttemptAt)
        }

        override suspend fun markTerminal(id: Long) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = rows[i].copy(terminal = true)
        }

        override suspend fun updateClientEventId(id: Long, clientEventId: String) {
            val i = rows.indexOfFirst { it.id == id }
            if (i >= 0) rows[i] = rows[i].copy(clientEventId = clientEventId)
        }

        override suspend fun listAll(): List<PendingViewEventEntity> = rows.sortedBy { it.id }

        override suspend fun countPending(): Int = rows.count { !it.terminal }

        override suspend fun count(): Int = rows.size
    }

    /** 固定时钟（退避使行不可取件——本组用例只断言提示与计数，0 足够） */
private object FixedEventClock : media.qimeng.app.core.data.events.EventClock {
    override fun now(): Long = 0L
}

/** 可编程发送器：恒 202 或恒 IO 失败（立即同步摘要语义断言用） */
    private class FakeEventSender(private val fail: Boolean) : ViewEventSender {
        override suspend fun send(event: PendingViewEventEntity): ViewEventSendResult =
            if (fail) ViewEventSendResult.IoError else ViewEventSendResult.Http(202)
    }

    private fun viewModel(
        auth: AuthRepository = FakeAuthRepository(initialServerUrl = "http://10.0.2.2:8420", initialLoggedIn = true),
        prefs: RecommendPrefsRepository = FakePrefsRepository(),
        version: String? = "v0.9.0",
        cachePrefs: DiskCachePrefsRepository = FakeDiskCachePrefsRepository(),
        cacheManager: CoilCacheManager = FakeCoilCacheManager(),
        stats: StatsRepository = FakeStatsRepository(
            StatsOverviewValues(totalFiles = 6135, imageCount = 5721, videoCount = 414, totalSizeBytes = 0L, todayViews = 0, totalViews = 0L),
        ),
        queue: ViewEventQueue = ViewEventQueue(FakeEventDao(), FakeEventSender(fail = false), clock = FixedEventClock),
    ): SettingsViewModel = SettingsViewModel(
        authRepository = auth,
        statsRepository = stats,
        prefsRepository = prefs,
        systemInfoRepository = FakeSystemInfoRepository(version),
        diskCachePrefsRepository = cachePrefs,
        coilCacheManager = cacheManager,
        viewEventQueue = queue,
        // IO 位也走测试调度器：withContext 全链路可被 advanceUntilIdle 推进
        ioDispatcher = mainDispatcherRule.testDispatcher,
    )

    @Test
    fun `logout 触达仓库层并清除登录态`() {
        val auth = FakeAuthRepository(initialServerUrl = "http://10.0.2.2:8420", initialLoggedIn = true)
        val settingsViewModel = viewModel(auth = auth)
        settingsViewModel.logout()
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, auth.logoutCount)
        // 登录态已翻 false；地址保留（下次登录自动回填的「记忆上次」语义）
        assertFalse(runBlocking { auth.isLoggedIn.first() })
        assertEquals("http://10.0.2.2:8420", runBlocking { auth.serverUrl.first() })
    }

    // ---------- 本机模式快捷入口（任务T T3，ADR-0015 预设） ----------

    @Test
    fun `本机模式入口登出并预置下次登录带出的预设地址`() = runTest {
        val auth = FakeAuthRepository(initialServerUrl = "http://10.0.2.2:8420", initialLoggedIn = true)
        val settingsViewModel = viewModel(auth = auth)
        advanceUntilIdle()
        settingsViewModel.fillLocalModeForNextLogin()
        advanceUntilIdle()
        // 登出触达仓库层；地址预置为本机模式预设（登录页「记忆上次」回填数据源）
        assertEquals(1, auth.logoutCount)
        assertFalse(runBlocking { auth.isLoggedIn.first() })
        assertEquals(ServerAddress.LOCAL_MODE_PRESET, runBlocking { auth.serverUrl.first() })
    }

    @Test
    fun `应用预设发 9 维载荷并高亮当前项`() = runTest {
        val prefs = FakePrefsRepository()
        val settingsViewModel = viewModel(prefs = prefs)
        advanceUntilIdle()
        assertEquals(RecommendPreset.BALANCED, settingsViewModel.uiState.value.appliedPreset)

        settingsViewModel.applyPreset(RecommendPreset.FRESH_FIRST)
        advanceUntilIdle()
        assertEquals(1, prefs.putCalls.size)
        assertEquals(RecommendPreset.FRESH_FIRST.toPrefsValues(), prefs.putCalls.single())
        assertEquals(RecommendPreset.FRESH_FIRST, settingsViewModel.uiState.value.appliedPreset)
        // 成功路径不产生写失败反馈（P2-3：成功不弹）
        assertNull(settingsViewModel.uiState.value.writeError)
    }

    @Test
    fun `切档位持久化进 DataStore`() = runTest {
        val cachePrefs = FakeDiskCachePrefsRepository()
        val settingsViewModel = viewModel(cachePrefs = cachePrefs)
        advanceUntilIdle()
        settingsViewModel.setCacheQuota(DiskCacheQuota.GB2)
        advanceUntilIdle()
        assertEquals(DiskCacheQuota.GB2, cachePrefs.quota.first())
        assertEquals(DiskCacheQuota.GB2, settingsViewModel.uiState.value.cacheQuota)
    }

    @Test
    fun `清空缓存归零`() = runTest {
        val cacheManager = FakeCoilCacheManager()
        val settingsViewModel = viewModel(cacheManager = cacheManager)
        advanceUntilIdle()
        assertEquals(5L * 1024 * 1024, settingsViewModel.uiState.value.cacheSizeBytes)
        settingsViewModel.clearCache()
        advanceUntilIdle()
        assertEquals(1, cacheManager.cleared)
        assertEquals(0L, settingsViewModel.uiState.value.cacheSizeBytes)
    }

    @Test
    fun `服务端版本展示与未返回时置空`() = runTest {
        val withVersion = viewModel()
        advanceUntilIdle()
        assertEquals("v0.9.0", withVersion.uiState.value.serverVersion)
        val noVersion = viewModel(version = null)
        advanceUntilIdle()
        assertNull(noVersion.uiState.value.serverVersion)
    }

    // ---------- I4 页首数量卡（GET /stats/overview imageCount/videoCount 纯计数） ----------

    @Test
    fun `init 拉取库存数量 图片视频两卡计数落地`() = runTest {
        // 默认 fake 数据取实录 mine.txt 锚点（图片 5721 / 视频 414）
        val settingsViewModel = viewModel()
        advanceUntilIdle()
        assertEquals(5721, settingsViewModel.uiState.value.imageCount)
        assertEquals(414, settingsViewModel.uiState.value.videoCount)
        // 纯计数装饰卡失败不构成操作反馈：成功路径无横幅
        assertNull(settingsViewModel.uiState.value.writeError)
    }

    @Test
    fun `数量卡读失败降级置空不崩不弹横幅`() = runTest {
        val settingsViewModel = viewModel(stats = FakeStatsRepository(overview = null))
        advanceUntilIdle()
        // null → UI 数字位显「—」（降级），writeError 不承载（与操作反馈族不同口径）
        assertNull(settingsViewModel.uiState.value.imageCount)
        assertNull(settingsViewModel.uiState.value.videoCount)
        assertNull(settingsViewModel.uiState.value.writeError)
        // 其余初始化链路不受数量卡读失败牵连
        assertEquals("v0.9.0", settingsViewModel.uiState.value.serverVersion)
    }

    // ---------- P2-3 写失败反馈（原实现静默吞错的回归锁定） ----------

    @Test
    fun `应用预设失败给反馈且高亮保持原项`() = runTest {
        val prefs = FakePrefsRepository().apply { putError = RuntimeException("network down") }
        val settingsViewModel = viewModel(prefs = prefs)
        advanceUntilIdle()
        assertEquals(RecommendPreset.BALANCED, settingsViewModel.uiState.value.appliedPreset)

        settingsViewModel.applyPreset(RecommendPreset.FRESH_FIRST)
        advanceUntilIdle()
        assertEquals(SAVE_FAILED_TEXT, settingsViewModel.uiState.value.writeError)
        // 回滚语义：载荷未发出、高亮保持原项、行退出 applying
        assertEquals(0, prefs.putCalls.size)
        assertEquals(RecommendPreset.BALANCED, settingsViewModel.uiState.value.appliedPreset)
        assertFalse(settingsViewModel.uiState.value.prefsApplying)
        // 点按消除（一次性反馈）
        settingsViewModel.dismissWriteError()
        advanceUntilIdle()
        assertNull(settingsViewModel.uiState.value.writeError)
    }

    @Test
    fun `切档位失败给反馈且档位不动`() = runTest {
        val cachePrefs = FakeDiskCachePrefsRepository().apply { setError = RuntimeException("disk io") }
        val settingsViewModel = viewModel(cachePrefs = cachePrefs)
        advanceUntilIdle()

        settingsViewModel.setCacheQuota(DiskCacheQuota.GB2)
        advanceUntilIdle()
        assertEquals(SAVE_FAILED_TEXT, settingsViewModel.uiState.value.writeError)
        // 回滚语义：DataStore 未写入，UI 跟随原档位
        assertEquals(DiskCacheQuota.DEFAULT, cachePrefs.quota.first())
        assertEquals(DiskCacheQuota.DEFAULT, settingsViewModel.uiState.value.cacheQuota)
    }

    // ---------- 浏览数据同步（任务L L5） ----------

    /** 预置一条未上传事件（幂等键合规，避免触发懒回填路径干扰断言） */
    private suspend fun FakeEventDao.seed(rowId: String) = insert(
        PendingViewEventEntity(
            assetId = "00000000-0000-0000-0000-000000000001", kind = "OPEN",
            startedAt = 1L, durationMs = 0L, sessionId = "s", createdAt = 1L, clientEventId = rowId,
        ),
    )

    @Test
    fun `浏览数据同步 - 成功后提示同步完成且待上传数清零`() = runTest {
        val dao = FakeEventDao()
        dao.seed("00000000-0000-0000-0000-0000000000a1")
        dao.seed("00000000-0000-0000-0000-0000000000a2")
        val queue = ViewEventQueue(dao, FakeEventSender(fail = false), clock = FixedEventClock)
        val vm = viewModel(queue = queue)
        advanceUntilIdle() // init loadPendingEvents
        assertEquals(2, vm.uiState.value.pendingEvents)

        vm.syncEventsNow()
        advanceUntilIdle()

        assertEquals("同步完成", vm.uiState.value.eventSyncNote)
        assertEquals(0, vm.uiState.value.pendingEvents)
        assertFalse(vm.uiState.value.eventSyncing)
        // 导出取数：空队列导出 0 条
        val export = vm.exportPending()
        assertEquals(0, export?.count)
    }

    @Test
    fun `浏览数据同步 - 网络不通提示保留重试且行不离队`() = runTest {
        val dao = FakeEventDao()
        dao.seed("00000000-0000-0000-0000-0000000000b1")
        val queue = ViewEventQueue(dao, FakeEventSender(fail = true), clock = FixedEventClock)
        val vm = viewModel(queue = queue)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.pendingEvents)

        vm.syncEventsNow()
        advanceUntilIdle()

        assertEquals("网络不通，1 条稍后自动重试", vm.uiState.value.eventSyncNote)
        assertEquals(1, vm.uiState.value.pendingEvents)
        assertEquals(1, dao.rows.size) // 本地优先：失败不丢行
    }
}
