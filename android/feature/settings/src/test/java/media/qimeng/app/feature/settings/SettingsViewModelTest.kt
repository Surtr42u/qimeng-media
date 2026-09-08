package media.qimeng.app.feature.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.data.repository.AuthRepository
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.data.repository.CoilCacheManager
import media.qimeng.app.core.data.repository.DiskCachePrefsRepository
import media.qimeng.app.core.data.repository.RecommendPrefsRepository
import media.qimeng.app.core.data.repository.StatsRepository
import media.qimeng.app.core.data.repository.SystemInfoRepository
import media.qimeng.app.core.model.AuthorSummary
import media.qimeng.app.core.model.AuthorType
import media.qimeng.app.core.model.DiskCacheQuota
import media.qimeng.app.core.model.RecommendPrefsValues
import media.qimeng.app.core.model.RecommendPreset
import media.qimeng.app.core.model.StatsOverviewValues
import media.qimeng.app.core.model.TrendPoint
import media.qimeng.app.core.model.toPrefsValues
import media.qimeng.app.core.testing.FakeAuthRepository
import media.qimeng.app.core.testing.MainDispatcherRule

/** 与 SettingsViewModel 私有常量对齐的反馈文案（文案属反馈契约，ViewModel 侧改动须同步此处） */
private const val SAVE_FAILED_TEXT = "保存失败，请重试"
private const val LOAD_AUTHORS_FAILED_TEXT = "作者列表加载失败，请重试"

/**
 * 我的页 ViewModel 单测（M4-6）：登出会话闭环（M4-1 原语义）+ 作者总览卡聚合（G2）+
 * 预设应用（C4）+ 档位持久化/清空（C5）+ 服务端版本（C6）。
 * 总览计数/Top5 聚合纯函数本身由 :core:model 单测锁定，这里锁 UI 状态编排与仓库触达；
 * C4 的取关路径已随 G2 总览卡下线（关注 toggle 归作者管理页），对应用例移除。
 */
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // ---------- 测试替身（最小行为，只满足本页编排断言） ----------

    private class FakeAuthorRepository(
        authors: List<AuthorSummary>,
    ) : AuthorRepository {
        private val rows = authors.toMutableList()
        val followCalls = mutableListOf<Pair<String, Boolean>>()

        /** 可编程：非空时 setFollowed 抛出（P2-3 失败路径：静默吞错回归锁定） */
        var unfollowError: Throwable? = null

        /** 可编程：非空时 authors 抛出（refreshAuthorOverview 读失败路径锁定） */
        var authorsError: Throwable? = null

        override suspend fun authors(): List<AuthorSummary> {
            authorsError?.let { throw it }
            return rows.toList()
        }

        override suspend fun setFollowed(authorId: String, followed: Boolean) {
            unfollowError?.let { throw it }
            followCalls += authorId to followed
            rows.replaceAll { if (it.id == authorId) it.copy(followed = followed) else it }
        }
    }

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

    private fun author(id: String, followed: Boolean, fileCount: Int? = null) =
        AuthorSummary(id = id, displayName = "作者$id", type = AuthorType.REGULAR, fileCount = fileCount, followed = followed, viewCount = null)

    private fun viewModel(
        auth: AuthRepository = FakeAuthRepository(initialServerUrl = "http://10.0.2.2:8420", initialLoggedIn = true),
        authors: List<AuthorSummary> = listOf(author("1", true, fileCount = 3), author("2", false, fileCount = 10), author("3", true, fileCount = 5)),
        prefs: RecommendPrefsRepository = FakePrefsRepository(),
        version: String? = "v0.9.0",
        cachePrefs: DiskCachePrefsRepository = FakeDiskCachePrefsRepository(),
        cacheManager: CoilCacheManager = FakeCoilCacheManager(),
        authorRepo: FakeAuthorRepository? = null,
        stats: StatsRepository = FakeStatsRepository(
            StatsOverviewValues(totalFiles = 6135, imageCount = 5721, videoCount = 414, totalSizeBytes = 0L, todayViews = 0, totalViews = 0L),
        ),
    ): SettingsViewModel = SettingsViewModel(
        authRepository = auth,
        authorRepository = authorRepo ?: FakeAuthorRepository(authors),
        statsRepository = stats,
        prefsRepository = prefs,
        systemInfoRepository = FakeSystemInfoRepository(version),
        diskCachePrefsRepository = cachePrefs,
        coilCacheManager = cacheManager,
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

    @Test
    fun `init 拉取作者总览 双计数与文件数Top5`() = runTest {
        val settingsViewModel = viewModel()
        advanceUntilIdle()
        val overview = settingsViewModel.uiState.value.authorOverview
        assertNotNull(overview)
        // 全量 3 位 · 已关注 2（Web DataPage 作者总览卡同口径）；Top 按文件数降序
        assertEquals(3, overview!!.totalAuthors)
        assertEquals(2, overview.followedCount)
        assertEquals(listOf("2", "3", "1"), overview.topByFileCount.map { it.id })
        assertFalse(settingsViewModel.uiState.value.authorsLoading)
        assertEquals("http://10.0.2.2:8420", settingsViewModel.uiState.value.serverUrl)
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
        assertEquals(3, settingsViewModel.uiState.value.authorOverview?.totalAuthors)
        assertEquals("v0.9.0", settingsViewModel.uiState.value.serverVersion)
    }

    // ---------- P2-3 写失败反馈 + 读失败反馈（原实现静默吞错的回归锁定） ----------

    @Test
    fun `作者总览读失败给反馈且不清空既有总览`() = runTest {
        val authorRepo = FakeAuthorRepository(
            listOf(author("1", true, fileCount = 3), author("2", false, fileCount = 10), author("3", true, fileCount = 5)),
        )
        val settingsViewModel = viewModel(authorRepo = authorRepo)
        advanceUntilIdle()
        assertEquals(3, settingsViewModel.uiState.value.authorOverview?.totalAuthors)

        // 二次刷新失败：既有总览保持原状（网络抖动不伪装成「没有作者」），给反馈
        authorRepo.authorsError = RuntimeException("network down")
        settingsViewModel.refreshAuthorOverview()
        advanceUntilIdle()
        assertEquals(LOAD_AUTHORS_FAILED_TEXT, settingsViewModel.uiState.value.writeError)
        assertEquals(3, settingsViewModel.uiState.value.authorOverview?.totalAuthors)
        assertFalse(settingsViewModel.uiState.value.authorsLoading)

        // 恢复后重刷：横幅可消除，总览正常落地
        authorRepo.authorsError = null
        settingsViewModel.dismissWriteError()
        settingsViewModel.refreshAuthorOverview()
        advanceUntilIdle()
        assertNull(settingsViewModel.uiState.value.writeError)
        assertEquals(3, settingsViewModel.uiState.value.authorOverview?.totalAuthors)
    }

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
}
