package media.qimeng.app.feature.login

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.data.repository.LoginError
import media.qimeng.app.core.data.repository.LoginResult
import media.qimeng.app.core.network.DefaultEndpoint
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.testing.FakeAuthRepository
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 登录页 ViewModel 状态机单测（约束 9：ViewModel 必须单测）。
 * 第三百六十三笔：登录页改「服务器 / 本机」两选项——本文件随状态机重写。
 * 仓库行为本身由 :core:data 的全链路测试锁定，这里只锁 UI 状态编排。
 */
class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun driveIdle() = mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

    // ---------- 进页预选与地址解析 ----------

    @Test
    fun `默认端为NAS时预选服务器且地址取NAS记忆`() {
        val repository = FakeAuthRepository(
            initialServerUrl = ServerAddress.LOCAL_MODE_PRESET, // 上次登录是本机
            initialRememberedNasUrl = "http://192.168.1.8:8420",
            initialDefaultEndpoint = DefaultEndpoint.NAS,
        )
        val viewModel = LoginViewModel(repository)
        driveIdle()
        assertEquals(DefaultEndpoint.NAS, viewModel.uiState.value.selected)
        assertEquals("http://192.168.1.8:8420", viewModel.uiState.value.serverUrl)
    }

    @Test
    fun `默认端未设置时按当前地址端型预选NAS`() {
        val repository = FakeAuthRepository(initialServerUrl = "http://10.0.2.2:8420")
        val viewModel = LoginViewModel(repository)
        driveIdle()
        assertEquals(DefaultEndpoint.NAS, viewModel.uiState.value.selected)
        assertEquals("http://10.0.2.2:8420", viewModel.uiState.value.serverUrl)
    }

    @Test
    fun `默认端未设置且当前是本机地址时预选本机`() {
        val repository = FakeAuthRepository(initialServerUrl = ServerAddress.LOCAL_MODE_PRESET)
        val viewModel = LoginViewModel(repository)
        driveIdle()
        assertEquals(DefaultEndpoint.LOCAL, viewModel.uiState.value.selected)
    }

    @Test
    fun `换址预置的新地址优先于NAS记忆展示`() {
        // 设置页地址卡改成新地址并保存 → 预置进主键；登录页「服务器」应带出预置值
        // 而非旧记忆（「app 内改地址同步记录」的展示口径）
        val repository = FakeAuthRepository(
            initialServerUrl = "http://192.168.1.50:8420",
            initialRememberedNasUrl = "http://192.168.1.8:8420",
            initialDefaultEndpoint = DefaultEndpoint.NAS,
        )
        val viewModel = LoginViewModel(repository)
        driveIdle()
        assertEquals("http://192.168.1.50:8420", viewModel.uiState.value.serverUrl)
    }

    @Test
    fun `本机选项地址取记忆无记忆回退预设`() {
        val rememberedLocal = ServerAddress.LOCAL_MODE_PRESET.dropLast(1) + "1"
        val withMemory = LoginViewModel(
            FakeAuthRepository(initialRememberedLocalUrl = rememberedLocal),
        )
        driveIdle()
        assertEquals(rememberedLocal, withMemory.uiState.value.localUrl)

        val withoutMemory = LoginViewModel(FakeAuthRepository())
        driveIdle()
        assertEquals(ServerAddress.LOCAL_MODE_PRESET, withoutMemory.uiState.value.localUrl)
    }

    @Test
    fun `全新安装未设置时不预选`() {
        val viewModel = LoginViewModel(FakeAuthRepository())
        driveIdle()
        assertNull(viewModel.uiState.value.selected)
        assertEquals("", viewModel.uiState.value.serverUrl)
    }

    // ---------- 选项点选与提交透传 ----------

    @Test
    fun `点选本机后提交透传本机地址`() {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository)
        driveIdle()
        viewModel.onEndpointSelected(DefaultEndpoint.LOCAL)
        viewModel.submit()
        driveIdle()
        assertEquals(1, repository.loginCalls.size)
        assertEquals(ServerAddress.LOCAL_MODE_PRESET, repository.loginCalls.single().rawAddress)
    }

    @Test
    fun `点选服务器后提交透传服务器地址`() {
        val repository = FakeAuthRepository(
            initialRememberedNasUrl = "http://192.168.1.8:8420",
            initialDefaultEndpoint = DefaultEndpoint.NAS,
        )
        val viewModel = LoginViewModel(repository)
        driveIdle()
        viewModel.submit()
        driveIdle()
        assertEquals(1, repository.loginCalls.size)
        assertEquals("http://192.168.1.8:8420", repository.loginCalls.single().rawAddress)
    }

    @Test
    fun `未选端就提交报地址错误且不发仓库调用`() {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository)
        driveIdle() // 全空环境：不预选
        viewModel.submit()
        driveIdle()
        assertEquals(LoginError.InvalidAddress, viewModel.uiState.value.error)
        assertTrue(repository.loginCalls.isEmpty())
    }

    @Test
    fun `点选选项可清除旧错误`() {
        val repository = FakeAuthRepository()
        val viewModel = LoginViewModel(repository)
        driveIdle()
        viewModel.submit()
        driveIdle()
        assertEquals(LoginError.InvalidAddress, viewModel.uiState.value.error)
        viewModel.onEndpointSelected(DefaultEndpoint.LOCAL)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `服务器地址可手改后按改后值提交`() {
        val repository = FakeAuthRepository(
            initialRememberedNasUrl = "http://192.168.1.8:8420",
            initialDefaultEndpoint = DefaultEndpoint.NAS,
        )
        val viewModel = LoginViewModel(repository)
        driveIdle()
        viewModel.onServerUrlChange("http://192.168.1.50:8420")
        viewModel.submit()
        driveIdle()
        assertEquals("http://192.168.1.50:8420", repository.loginCalls.single().rawAddress)
    }

    // ---------- 既有提交状态机（M4-1 冻结口径，随选项化适配） ----------

    @Test
    fun `提交成功后清除提交中状态`() {
        val repository = FakeAuthRepository(
            initialRememberedNasUrl = "http://192.168.1.8:8420",
            initialDefaultEndpoint = DefaultEndpoint.NAS,
        )
        val viewModel = LoginViewModel(repository)
        driveIdle()
        viewModel.onPasswordChange("secret")
        viewModel.submit()
        driveIdle()
        assertEquals(LoginResult.Success, repository.nextLoginResult)
        assertTrue(viewModel.uiState.value.error == null)
        assertEquals("secret", repository.loginCalls.single().password)
    }

    @Test
    fun `提交失败映射错误分类`() {
        val repository = FakeAuthRepository(
            initialRememberedNasUrl = "http://192.168.1.8:8420",
            initialDefaultEndpoint = DefaultEndpoint.NAS,
        )
        repository.nextLoginResult = LoginResult.Failure(LoginError.ServerUnreachable)
        val viewModel = LoginViewModel(repository)
        driveIdle()
        viewModel.submit()
        driveIdle()
        assertEquals(LoginError.ServerUnreachable, viewModel.uiState.value.error)
    }

    @Test
    fun `提交中防重复提交`() {
        val repository = FakeAuthRepository(
            initialRememberedNasUrl = "http://192.168.1.8:8420",
            initialDefaultEndpoint = DefaultEndpoint.NAS,
        )
        val gate = CompletableDeferred<Unit>()
        repository.loginGate = gate
        val viewModel = LoginViewModel(repository)
        driveIdle()
        viewModel.submit()
        viewModel.submit()
        gate.complete(Unit)
        driveIdle()
        assertEquals(1, repository.loginCalls.size)
    }
}
