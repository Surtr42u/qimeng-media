package media.qimeng.app.feature.settings

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.testing.FakeAuthRepository
import media.qimeng.app.core.testing.MainDispatcherRule

/** 设置页 ViewModel 单测：登出触达仓库层且翻转登录态（M4-1 会话闭环的最小锁定）。 */
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `logout 触达仓库层并清除登录态`() {
        val repository = FakeAuthRepository(initialServerUrl = "http://10.0.2.2:8420", initialLoggedIn = true)
        val viewModel = SettingsViewModel(repository)
        viewModel.logout()
        mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, repository.logoutCount)
        // 登录态已翻 false；地址保留（下次登录自动回填的「记忆上次」语义）
        assertFalse(runBlocking { repository.isLoggedIn.first() })
        assertEquals("http://10.0.2.2:8420", runBlocking { repository.serverUrl.first() })
    }
}
