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
import media.qimeng.app.core.data.repository.AuthRepository
import media.qimeng.app.core.data.repository.AuthorRepository
import media.qimeng.app.core.data.repository.CoilCacheManager
import media.qimeng.app.core.data.repository.DiskCachePrefsRepository
import media.qimeng.app.core.data.repository.RecommendPrefsRepository
import media.qimeng.app.core.data.repository.SystemInfoRepository
import media.qimeng.app.core.model.AuthorSummary
import media.qimeng.app.core.model.AuthorType
import media.qimeng.app.core.model.DiskCacheQuota
import media.qimeng.app.core.model.RecommendPrefsValues
import media.qimeng.app.core.model.RecommendPreset
import media.qimeng.app.core.model.toPrefsValues
import media.qimeng.app.core.testing.FakeAuthRepository
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 我的页 ViewModel 单测（M4-6）：登出会话闭环（M4-1 原语义）+ 关注列表过滤/取关（C4）+
 * 预设应用（C4）+ 档位持久化/清空（C5）+ 服务端版本（C6）。
 * 过滤纯函数本身由 :core:model 单测锁定，这里锁 UI 状态编排与仓库触达。
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

        override suspend fun authors(): List<AuthorSummary> = rows.toList()

        override suspend fun setFollowed(authorId: String, followed: Boolean) {
            followCalls += authorId to followed
            rows.replaceAll { if (it.id == authorId) it.copy(followed = followed) else it }
        }
    }

    private class FakePrefsRepository : RecommendPrefsRepository {
        var current: RecommendPrefsValues? = RecommendPreset.BALANCED.toPrefsValues()
        val putCalls = mutableListOf<RecommendPrefsValues>()

        override suspend fun prefs(): RecommendPrefsValues = current ?: RecommendPreset.BALANCED.toPrefsValues()

        override suspend fun putPrefs(values: RecommendPrefsValues) {
            putCalls += values
            current = values
        }
    }

    private class FakeSystemInfoRepository(private val version: String?) : SystemInfoRepository {
        override suspend fun serverVersion(): String? = version
    }

    private class FakeDiskCachePrefsRepository : DiskCachePrefsRepository {
        private val quotaFlow = MutableStateFlow(DiskCacheQuota.DEFAULT)

        override val quota: kotlinx.coroutines.flow.Flow<DiskCacheQuota> = quotaFlow

        override suspend fun setQuota(quota: DiskCacheQuota) {
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

    private fun author(id: String, followed: Boolean) =
        AuthorSummary(id = id, displayName = "作者$id", type = AuthorType.REGULAR, fileCount = null, followed = followed, viewCount = null)

    private fun viewModel(
        auth: AuthRepository = FakeAuthRepository(initialServerUrl = "http://10.0.2.2:8420", initialLoggedIn = true),
        authors: List<AuthorSummary> = listOf(author("1", true), author("2", false), author("3", true)),
        prefs: RecommendPrefsRepository = FakePrefsRepository(),
        version: String? = "v0.9.0",
        cachePrefs: DiskCachePrefsRepository = FakeDiskCachePrefsRepository(),
        cacheManager: CoilCacheManager = FakeCoilCacheManager(),
    ): SettingsViewModel = SettingsViewModel(
        authRepository = auth,
        authorRepository = FakeAuthorRepository(authors),
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
    fun `init 拉取关注列表只含已关注作者`() = runTest {
        val settingsViewModel = viewModel()
        advanceUntilIdle()
        assertEquals(listOf("作者1", "作者3"), settingsViewModel.uiState.value.followedAuthors.map { it.displayName })
        assertEquals("http://10.0.2.2:8420", settingsViewModel.uiState.value.serverUrl)
    }

    @Test
    fun `取关发 followed=false 且行消失`() = runTest {
        val settingsViewModel = viewModel()
        advanceUntilIdle()
        settingsViewModel.unfollow("1")
        advanceUntilIdle()
        assertEquals(listOf("作者3"), settingsViewModel.uiState.value.followedAuthors.map { it.displayName })
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
}
