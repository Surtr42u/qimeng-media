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

/** 与 SettingsViewModel 私有常量对齐的反馈文案（文案属反馈契约，ViewModel 侧改动须同步此处） */
private const val SAVE_FAILED_TEXT = "保存失败，请重试"
private const val UNFOLLOW_FAILED_TEXT = "取关失败，请重试"
private const val LOAD_FOLLOWED_FAILED_TEXT = "关注列表加载失败，请重试"

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

        /** 可编程：非空时 setFollowed 抛出（P2-3 失败路径：静默吞错回归锁定） */
        var unfollowError: Throwable? = null

        /** 可编程：非空时 authors 抛出（refreshFollowed 读失败路径锁定） */
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

    private fun author(id: String, followed: Boolean) =
        AuthorSummary(id = id, displayName = "作者$id", type = AuthorType.REGULAR, fileCount = null, followed = followed, viewCount = null)

    private fun viewModel(
        auth: AuthRepository = FakeAuthRepository(initialServerUrl = "http://10.0.2.2:8420", initialLoggedIn = true),
        authors: List<AuthorSummary> = listOf(author("1", true), author("2", false), author("3", true)),
        prefs: RecommendPrefsRepository = FakePrefsRepository(),
        version: String? = "v0.9.0",
        cachePrefs: DiskCachePrefsRepository = FakeDiskCachePrefsRepository(),
        cacheManager: CoilCacheManager = FakeCoilCacheManager(),
        authorRepo: FakeAuthorRepository? = null,
    ): SettingsViewModel = SettingsViewModel(
        authRepository = auth,
        authorRepository = authorRepo ?: FakeAuthorRepository(authors),
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

    // ---------- P2-3 写失败反馈 + 读失败反馈（原实现静默吞错的回归锁定） ----------

    @Test
    fun `关注列表读失败给反馈且不清空既有列表`() = runTest {
        val authorRepo = FakeAuthorRepository(
            listOf(author("1", true), author("2", false), author("3", true)),
        )
        val settingsViewModel = viewModel(authorRepo = authorRepo)
        advanceUntilIdle()
        assertEquals(listOf("作者1", "作者3"), settingsViewModel.uiState.value.followedAuthors.map { it.displayName })

        // 二次刷新失败：列表保持原状（修复前 getOrDefault(emptyList()) 把网络抖动伪装成「没有关注」），给反馈
        authorRepo.authorsError = RuntimeException("network down")
        settingsViewModel.refreshFollowed()
        advanceUntilIdle()
        assertEquals(LOAD_FOLLOWED_FAILED_TEXT, settingsViewModel.uiState.value.writeError)
        assertEquals(listOf("作者1", "作者3"), settingsViewModel.uiState.value.followedAuthors.map { it.displayName })
        assertFalse(settingsViewModel.uiState.value.followedLoading)

        // 恢复后重刷：横幅可消除，列表正常落地
        authorRepo.authorsError = null
        settingsViewModel.dismissWriteError()
        settingsViewModel.refreshFollowed()
        advanceUntilIdle()
        assertNull(settingsViewModel.uiState.value.writeError)
        assertEquals(listOf("作者1", "作者3"), settingsViewModel.uiState.value.followedAuthors.map { it.displayName })
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
    fun `取关失败给反馈且列表保持原状`() = runTest {
        val authorRepo = FakeAuthorRepository(
            listOf(author("1", true), author("2", false), author("3", true)),
        ).apply { unfollowError = RuntimeException("network down") }
        val settingsViewModel = viewModel(authorRepo = authorRepo)
        advanceUntilIdle()

        settingsViewModel.unfollow("1")
        advanceUntilIdle()
        assertEquals(UNFOLLOW_FAILED_TEXT, settingsViewModel.uiState.value.writeError)
        // 回滚语义：请求未触达仓库、列表保持原状（行不乐观移除）
        assertEquals(0, authorRepo.followCalls.size)
        assertEquals(listOf("作者1", "作者3"), settingsViewModel.uiState.value.followedAuthors.map { it.displayName })
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
