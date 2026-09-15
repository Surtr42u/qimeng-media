package media.qimeng.app.feature.settings

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
import media.qimeng.app.core.data.repository.AuthRepository
import media.qimeng.app.core.data.repository.RecommendPrefsRepository
import media.qimeng.app.core.data.repository.StatsRepository
import media.qimeng.app.core.data.repository.SystemInfoRepository
import media.qimeng.app.core.model.RecommendPrefsValues
import media.qimeng.app.core.model.RecommendPreset
import media.qimeng.app.core.model.StatsOverviewValues
import media.qimeng.app.core.model.TrendPoint
import media.qimeng.app.core.model.toPrefsValues
import media.qimeng.app.core.testing.FakeAuthRepository
import media.qimeng.app.core.testing.MainDispatcherRule

/** 与 SettingsViewModel 私有常量对齐的反馈文案（文案属反馈契约，ViewModel 侧改动须同步此处） */
private const val SAVE_FAILED_TEXT = "保存失败，请重试"

/**
 * 我的页 ViewModel 单测（M4-6）：登出会话闭环（M4-1 原语义）+ 预设应用（C4）+
 * 服务端版本（C6）。
 * 缓存档位持久化/清空/切档失败（C5）用例 2026-09-16 用户反馈随「缓存区」退役删除
 * （迁往数据管理→缩略图缓存页，行为由 feature:manage 侧承接）。
 * X5 批 2026-09-12：作者总览卡退役（我的页改收藏同款入口行），总览聚合/读失败用例
 * 与 FakeAuthorRepository 随之移除；纯计数/排序仍由 :core:model 单测锁定。
 * U10-4：本机模式入口用例迁 ServerSettingsViewModelTest（入口移服务器子页，
 * fillLocalModeForNextLogin 自本页 ViewModel 删除）。
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

        /** 可编程：非空时 prefs 抛出（2026-09-13 修复批：GET 失败 → prefsLoadFailed 态） */
        var prefsError: Throwable? = null

        override suspend fun prefs(): RecommendPrefsValues {
            prefsError?.let { throw it }
            return current ?: RecommendPreset.BALANCED.toPrefsValues()
        }

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

    private fun viewModel(
        auth: AuthRepository = FakeAuthRepository(initialServerUrl = "http://10.0.2.2:8420", initialLoggedIn = true),
        prefs: RecommendPrefsRepository = FakePrefsRepository(),
        version: String? = "v0.9.0",
        stats: StatsRepository = FakeStatsRepository(
            StatsOverviewValues(totalFiles = 6135, imageCount = 5721, videoCount = 414, totalSizeBytes = 0L, todayViews = 0, totalViews = 0L),
        ),
    ): SettingsViewModel = SettingsViewModel(
        authRepository = auth,
        statsRepository = stats,
        prefsRepository = prefs,
        systemInfoRepository = FakeSystemInfoRepository(version),
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

    // ---------- 本机模式快捷入口用例随 U10-4 迁 ServerSettingsViewModelTest（入口移服务器子页） ----------

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

    // ---------- 推荐偏好加载失败/重试（2026-09-13「点击无反应」修复批回归锁定） ----------

    @Test
    fun `偏好拉取失败置失败态且重试成功后恢复高亮`() = runTest {
        val prefs = FakePrefsRepository().apply { prefsError = RuntimeException("network down") }
        val settingsViewModel = viewModel(prefs = prefs)
        advanceUntilIdle()
        // GET 失败终态：无值、失败态置位（Sheet 显「加载失败/重试」，四行仍可点应用）
        assertNull(settingsViewModel.uiState.value.prefsValues)
        assertTrue(settingsViewModel.uiState.value.prefsLoadFailed)
        assertFalse(settingsViewModel.uiState.value.prefsLoading)
        assertNull(settingsViewModel.uiState.value.appliedPreset)

        // 重试恢复：失败清除、值与高亮落地
        prefs.prefsError = null
        settingsViewModel.retryLoadPrefs()
        advanceUntilIdle()
        assertEquals(RecommendPreset.BALANCED.toPrefsValues(), settingsViewModel.uiState.value.prefsValues)
        assertFalse(settingsViewModel.uiState.value.prefsLoadFailed)
        assertEquals(RecommendPreset.BALANCED, settingsViewModel.uiState.value.appliedPreset)
    }

    @Test
    fun `偏好拉取失败时应用预设仍走 PUT 并本地高亮`() = runTest {
        // Sheet 修复语义：预设四行恒可点（应用走 PUT 不依赖本次 GET 结果）
        val prefs = FakePrefsRepository().apply { prefsError = RuntimeException("network down") }
        val settingsViewModel = viewModel(prefs = prefs)
        advanceUntilIdle()
        assertTrue(settingsViewModel.uiState.value.prefsLoadFailed)

        settingsViewModel.applyPreset(RecommendPreset.DEEP_EXPLORATION)
        advanceUntilIdle()
        assertEquals(1, prefs.putCalls.size)
        assertEquals(RecommendPreset.DEEP_EXPLORATION, settingsViewModel.uiState.value.appliedPreset)
        // PUT 成功即持权威值：先前的 GET 失败态随之消除（Sheet 撤重试态）
        assertFalse(settingsViewModel.uiState.value.prefsLoadFailed)
    }

    @Test
    fun `打开关闭偏好Sheet置位开合状态`() = runTest {
        val settingsViewModel = viewModel()
        advanceUntilIdle()
        assertFalse(settingsViewModel.uiState.value.prefsSheetOpen)
        settingsViewModel.openPrefsSheet()
        assertTrue(settingsViewModel.uiState.value.prefsSheetOpen)
        settingsViewModel.closePrefsSheet()
        assertFalse(settingsViewModel.uiState.value.prefsSheetOpen)
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

}
