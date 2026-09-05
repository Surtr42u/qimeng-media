package media.qimeng.app.session

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.testing.FakeAuthRepository
import media.qimeng.app.core.testing.MainDispatcherRule

/** 壳层会话状态机单测：登录态分支 + 401 事件即时退登录页 + 重登复位（M4-1 验收口径的状态层）。 */
class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun driveIdle() = mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

    @Test
    fun `持久化态未登录时呈 LoggedOut`() {
        val viewModel = MainViewModel(FakeAuthRepository())
        driveIdle()
        assertEquals(SessionState.LoggedOut, viewModel.sessionState.value)
    }

    @Test
    fun `持久化态已登录时呈 LoggedIn（杀进程重启直进壳）`() {
        val viewModel = MainViewModel(FakeAuthRepository(initialLoggedIn = true))
        driveIdle()
        assertEquals(SessionState.LoggedIn, viewModel.sessionState.value)
    }

    @Test
    fun `401 事件即时退登录页（不等 token 写盘落地）`() {
        val repository = FakeAuthRepository(initialLoggedIn = true)
        val viewModel = MainViewModel(repository)
        driveIdle()
        assertEquals(SessionState.LoggedIn, viewModel.sessionState.value)
        repository.emitUnauthorized()
        driveIdle()
        assertEquals(SessionState.LoggedOut, viewModel.sessionState.value)
    }

    @Test
    fun `重新登录成功复位过期标记（下一次 401 仍可触发跳转）`() {
        val repository = FakeAuthRepository(initialLoggedIn = true)
        val viewModel = MainViewModel(repository)
        driveIdle()
        repository.emitUnauthorized()
        driveIdle()
        assertEquals(SessionState.LoggedOut, viewModel.sessionState.value)
        // 完整重登序列：AuthInterceptor 清 token（isLoggedIn 翻 false）→ 用户重新登录成功（翻 true）
        repository.setLoggedIn(false)
        driveIdle()
        repository.setLoggedIn(true)
        driveIdle()
        assertEquals(SessionState.LoggedIn, viewModel.sessionState.value)
        // 再次 401 仍能即时退登录页
        repository.emitUnauthorized()
        driveIdle()
        assertEquals(SessionState.LoggedOut, viewModel.sessionState.value)
    }
}
