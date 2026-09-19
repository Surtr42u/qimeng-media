package media.qimeng.app.feature.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.data.embedded.EmbeddedServerController
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.testing.FakeAuthRepository
import media.qimeng.app.core.testing.MainDispatcherRule

/**
 * 服务器设置页 ViewModel 单测（U10-4）：地址展示与回填、保存换址=logoutWithStagedUrl
 * （先写地址再清 token 的既有语义，调用断言在仓库替身上；全链路行为由 :core:data
 * 自带测试锁定）、本机模式预填值取常量与编辑后换预填值、非法地址不发仓库调用。
 *
 * 调度器按 MainDispatcherRule 文档配方与 Main 共享（runTest(rule.testDispatcher)），
 * advanceUntilIdle 同时推进 ViewModel 的 viewModelScope 与本 TestScope 的事件收集器。
 */
class ServerSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** 初始地址用 core 既有常量（测试零新增端口字面量） */
    private val initialUrl = ServerAddress.EMULATOR_LOOPBACK

    private var fakeController: FakeEmbeddedServerController = FakeEmbeddedServerController()
        get() = field

    private fun viewModel(
        auth: FakeAuthRepository = FakeAuthRepository(initialServerUrl = initialUrl, initialLoggedIn = true),
        scanCharge: FakeScanChargeController = FakeScanChargeController(),
    ): ServerSettingsViewModel = ServerSettingsViewModel(
        authRepository = auth,
        embeddedServerController = FakeEmbeddedServerController().also { fakeController = it },
        scanChargeController = scanCharge,
    )

    @Test
    fun `进页展示当前地址并回填输入框`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(initialUrl, vm.uiState.value.currentUrl)
        assertEquals(initialUrl, vm.uiState.value.urlInput)
        // 预填值单源取常量（U10-4 拍板：本机地址编辑框初值=LOCAL_MODE_PRESET）
        assertEquals(ServerAddress.LOCAL_MODE_PRESET, vm.uiState.value.localUrlInput)
        assertFalse(vm.uiState.value.isSaving)
    }

    @Test
    fun `保存换址登出并预置规范化新地址且发登出事件`() = runTest(mainDispatcherRule.testDispatcher) {
        val auth = FakeAuthRepository(initialServerUrl = initialUrl, initialLoggedIn = true)
        val vm = viewModel(auth)
        val received = mutableListOf<ServerSettingsEvent>()
        launch { vm.events.take(1).toList(received) }
        advanceUntilIdle() // 事件收集器就位（init 回填同步完成）
        vm.onUrlChange("192.168.1.50") // 裸地址（无端口）：断言提交值经规范化补 http:// 前缀
        vm.saveAndRelogin()
        advanceUntilIdle()
        // 登出触达仓库层；地址预置为规范化后的新值（登录页「记忆上次」回填数据源）
        assertEquals(1, auth.logoutCount)
        assertFalse(auth.isLoggedIn.first())
        assertEquals("http://192.168.1.50", auth.serverUrl.first())
        assertEquals(listOf(ServerSettingsEvent.LoggedOut), received)
        // 防重位保持到页面销毁（登出成功即整页离树，无需复位）
        assertTrue(vm.uiState.value.isSaving)
    }

    @Test
    fun `保存非法地址置错误态且不发仓库调用`() = runTest(mainDispatcherRule.testDispatcher) {
        val auth = FakeAuthRepository(initialServerUrl = initialUrl, initialLoggedIn = true)
        val vm = viewModel(auth)
        advanceUntilIdle()
        vm.onUrlChange("http://") // 无 host：ServerAddress.normalize 判非法
        vm.saveAndRelogin()
        advanceUntilIdle()
        assertEquals(0, auth.logoutCount)
        assertTrue(vm.uiState.value.urlInvalid)
        // 地址与登录态均未被动（校验挡在仓库调用之前）
        assertEquals(initialUrl, auth.serverUrl.first())
        assertTrue(auth.isLoggedIn.first())
    }

    @Test
    fun `本机模式一键切换登出并预置常量地址`() = runTest(mainDispatcherRule.testDispatcher) {
        val auth = FakeAuthRepository(initialServerUrl = initialUrl, initialLoggedIn = true)
        val vm = viewModel(auth)
        val received = mutableListOf<ServerSettingsEvent>()
        launch { vm.events.take(1).toList(received) }
        advanceUntilIdle()
        vm.switchToLocalMode()
        advanceUntilIdle()
        // 预置值=预设常量（原 SettingsViewModel.fillLocalModeForNextLogin 语义逐字迁入）
        assertEquals(1, auth.logoutCount)
        assertFalse(auth.isLoggedIn.first())
        assertEquals(ServerAddress.LOCAL_MODE_PRESET, auth.serverUrl.first())
        assertEquals(listOf(ServerSettingsEvent.LoggedOut), received)
        // U11 批次D（reviewer P3-10 补断言）：预设地址切换必须触发内嵌服务端拉起
        assertEquals(ServerAddress.LOCAL_MODE_PRESET, fakeController.lastStartedUrl)
    }

    @Test
    fun `本机模式编辑预填值后切换预置改后地址`() = runTest(mainDispatcherRule.testDispatcher) {
        val auth = FakeAuthRepository(initialServerUrl = initialUrl, initialLoggedIn = true)
        val vm = viewModel(auth)
        advanceUntilIdle()
        // 端口值自常量派生（末位改 1），测试零新增端口字面量
        val edited = ServerAddress.LOCAL_MODE_PRESET.dropLast(1) + "1"
        vm.onLocalUrlChange(edited)
        vm.switchToLocalMode()
        advanceUntilIdle()
        // 编辑语义=改「下次登录预填值」：切换时预置编辑后地址；常量本身不可变（const val，
        // 结构性保证无「第二地址源」写回），此处断言预置值确为编辑后规范化结果
        assertEquals(1, auth.logoutCount)
        assertEquals(edited, auth.serverUrl.first())
        assertFalse(vm.uiState.value.localUrlInvalid)
    }

    @Test
    fun `非法预填值切换置错误态且不发仓库调用`() = runTest(mainDispatcherRule.testDispatcher) {
        val auth = FakeAuthRepository(initialServerUrl = initialUrl, initialLoggedIn = true)
        val vm = viewModel(auth)
        advanceUntilIdle()
        vm.onLocalUrlChange("http://") // 无 host：ServerAddress.normalize 判非法
        vm.switchToLocalMode()
        advanceUntilIdle()
        assertEquals(0, auth.logoutCount)
        assertTrue(vm.uiState.value.localUrlInvalid)
        assertTrue(auth.isLoggedIn.first())
    }

    @Test
    fun `种子位一次性——仓库流再变只刷展示位不覆盖用户输入`() = runTest(mainDispatcherRule.testDispatcher) {
        val auth = FakeAuthRepository(initialServerUrl = initialUrl, initialLoggedIn = true)
        val vm = viewModel(auth)
        advanceUntilIdle()
        vm.onUrlChange("http://10.1.2.3:9999") // 用户已开始输入
        // 仓库侧地址再变（如别处登出预置）：只刷 currentUrl 展示位，输入框不被回写覆盖
        auth.logoutWithStagedUrl("http://192.168.1.99")
        advanceUntilIdle()
        assertEquals("http://10.1.2.3:9999", vm.uiState.value.urlInput)
        assertEquals("http://192.168.1.99", vm.uiState.value.currentUrl)
    }

    // ---- 批C 任务Q C-3：仅充电时扫描（仅本机模式可见） ----

    @Test
    fun `本机预设地址时isLocalMode为真且NAS地址为假`() = runTest(mainDispatcherRule.testDispatcher) {
        val localAuth = FakeAuthRepository(
            initialServerUrl = ServerAddress.LOCAL_MODE_PRESET,
            initialLoggedIn = true,
        )
        val localVm = viewModel(localAuth)
        advanceUntilIdle()
        assertTrue(localVm.uiState.value.isLocalMode)

        // 同一 VM 换 NAS 地址后判定翻转（地址流驱动，设置行随登录地址显隐）
        localAuth.logoutWithStagedUrl("http://192.168.1.99")
        localAuth.setLoggedIn(true)
        advanceUntilIdle()
        assertFalse(localVm.uiState.value.isLocalMode)
    }

    @Test
    fun `仅充电开关切换写入控制器`() = runTest(mainDispatcherRule.testDispatcher) {
        val scanCharge = FakeScanChargeController()
        val vm = viewModel(scanCharge = scanCharge)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.chargeOnlyScanEnabled)
        vm.onChargeOnlyScanChange(false)
        advanceUntilIdle()
        assertFalse(scanCharge.chargeOnly.value)
        assertFalse(vm.uiState.value.chargeOnlyScanEnabled)
    }
}

/** U11 批次D：内嵌服务端控制桩——ViewModel 只消费 ensureStartedIfLocalMode 的返回语义 */
private class FakeEmbeddedServerController : EmbeddedServerController {
    var lastStartedUrl: String? = null
    override fun ensureStartedIfLocalMode(serverUrl: String): Boolean {
        lastStartedUrl = serverUrl
        return serverUrl == ServerAddress.LOCAL_MODE_PRESET
    }
    override fun stop() = Unit
}

/** C-3：扫描充电控制桩——设置项流内存态，持久化行为由 DataStore 实现层保证 */
private class FakeScanChargeController : media.qimeng.app.core.data.scan.ScanChargeController {
    val chargeOnly = kotlinx.coroutines.flow.MutableStateFlow(true)
    override val chargeOnlyScanEnabled: Flow<Boolean> = chargeOnly
    override suspend fun setChargeOnlyScanEnabled(enabled: Boolean) {
        chargeOnly.value = enabled
    }
    override suspend fun requestRescan(libraryId: String) =
        media.qimeng.app.core.data.scan.RescanDecision.STARTED
    override suspend fun onPowerConnected() = Unit
    override suspend fun resumeDeferredIfCharging() = Unit
}
