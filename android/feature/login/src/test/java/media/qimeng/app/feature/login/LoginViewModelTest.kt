package media.qimeng.app.feature.login

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.data.repository.LoginError
import media.qimeng.app.core.data.repository.LoginResult
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.testing.FakeAuthRepository
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 登录页 ViewModel 状态机单测（约束 9：ViewModel 必须单测）。
 * 仓库行为本身由 :core:data 的全链路测试锁定，这里只锁 UI 状态编排。
 */
class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun driveIdle() = mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

    @Test
    fun `init 回填上次登录成功的服务器地址`() {
        val repository = FakeAuthRepository(initialServerUrl = "http://10.0.2.2:8420")
        val viewModel = LoginViewModel(repository)
        driveIdle()
        assertEquals("http://10.0.2.2:8420", viewModel.uiState.value.serverUrl)
    }

    @Test
    fun `从未配置地址时保持空输入`() {
        val viewModel = LoginViewModel(FakeAuthRepository())
        driveIdle()
        assertEquals("", viewModel.uiState.value.serverUrl)
    }

    // ---------- 本机模式快捷填入（任务T T3，ADR-0015 预设） ----------

    @Test
    fun `本机模式快捷填入预设地址且仍是未提交态可再修改`() {
        val viewModel = LoginViewModel(FakeAuthRepository())
        viewModel.fillLocalMode()
        driveIdle()
        assertEquals(ServerAddress.LOCAL_MODE_PRESET, viewModel.uiState.value.serverUrl)
        assertNull(viewModel.uiState.value.error)
        // 快捷填入=输入框赋值不是保存：用户仍可手改（未提交态语义）
        viewModel.onServerUrlChange("http://192.168.1.10:8420")
        assertEquals("http://192.168.1.10:8420", viewModel.uiState.value.serverUrl)
    }

    @Test
    fun `快捷填入优先带出记忆的本机模式地址`() {
        // 批S3：本机模式地址自身也记忆（M6 口）——快捷填入带出记忆值（端口值自常量派生，测试零新增字面量）
        val rememberedLocal = ServerAddress.LOCAL_MODE_PRESET.dropLast(1) + "1"
        val repository = FakeAuthRepository().apply { setRememberedLocalUrl(rememberedLocal) }
        val viewModel = LoginViewModel(repository)
        driveIdle() // init 预取记忆完成
        viewModel.fillLocalMode()
        assertEquals(rememberedLocal, viewModel.uiState.value.serverUrl)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `快捷填入后提交按既有登录流程原样透传预设地址`() {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository)
        viewModel.fillLocalMode()
        viewModel.onPasswordChange("secret")
        viewModel.submit()
        driveIdle()
        assertEquals(1, repository.loginCalls.size)
        assertEquals(ServerAddress.LOCAL_MODE_PRESET, repository.loginCalls.single().rawAddress)
    }

    @Test
    fun `提交透传用户输入并在成功后清除提交中状态`() {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository)
        viewModel.onServerUrlChange("http://192.168.1.10:8420")
        viewModel.onPasswordChange("secret")
        viewModel.submit()
        driveIdle()
        assertEquals(1, repository.loginCalls.size)
        assertEquals("http://192.168.1.10:8420", repository.loginCalls.single().rawAddress)
        assertEquals("secret", repository.loginCalls.single().password)
        assertEquals(false, viewModel.uiState.value.isSubmitting)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `密码错误显示 WrongPassword 分类`() {
        val repository = FakeAuthRepository().apply { nextLoginResult = LoginResult.Failure(LoginError.WrongPassword) }
        val viewModel = LoginViewModel(repository)
        viewModel.onServerUrlChange("http://10.0.2.2:8420")
        viewModel.onPasswordChange("wrong")
        viewModel.submit()
        driveIdle()
        assertEquals(LoginError.WrongPassword, viewModel.uiState.value.error)
        assertEquals(false, viewModel.uiState.value.isSubmitting)
    }

    @Test
    fun `地址不通显示 ServerUnreachable 分类`() {
        val repository = FakeAuthRepository().apply { nextLoginResult = LoginResult.Failure(LoginError.ServerUnreachable) }
        val viewModel = LoginViewModel(repository)
        viewModel.submit()
        driveIdle()
        assertEquals(LoginError.ServerUnreachable, viewModel.uiState.value.error)
    }

    @Test
    fun `提交中重复 submit 被忽略`() {
        val repository = FakeAuthRepository()
        val gate = CompletableDeferred<Unit>()
        repository.loginGate = gate
        val viewModel = LoginViewModel(repository)
        viewModel.onPasswordChange("secret")
        viewModel.submit()
        viewModel.submit()
        viewModel.submit()
        driveIdle() // 第一个 login 协程跑到仓库闸门处挂起（loginCalls 已记录 1 次）
        // 提交中：后续 submit 不触达仓库
        assertEquals(1, repository.loginCalls.size)
        assertEquals(true, viewModel.uiState.value.isSubmitting)
        gate.complete(Unit)
        driveIdle()
        assertEquals(1, repository.loginCalls.size)
        assertTrue(repository.loginCalls.single().password == "secret")
    }
}
