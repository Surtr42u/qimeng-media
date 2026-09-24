package media.qimeng.app.session

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import media.qimeng.app.core.data.backup.AutoBackupRunner
import media.qimeng.app.core.data.embedded.EmbeddedServerController
import media.qimeng.app.core.testing.FakeAuthRepository
import media.qimeng.app.core.testing.MainDispatcherRule

/** 壳层会话状态机单测：登录态分支 + 401 事件即时退登录页 + 重登复位（M4-1 验收口径的状态层）。
 *  2026-09-16 接线：构造器新增 AutoBackupRunner（登录态就绪触发自动备份判定），本测试以
 *  空壳桩满足构造，会话状态机断言不依赖备份行为。 */
class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun driveIdle() = mainDispatcherRule.testDispatcher.scheduler.advanceUntilIdle()

    @Test
    fun `持久化态未登录时呈 LoggedOut`() {
        val viewModel = MainViewModel(FakeAuthRepository(), NoopEmbeddedServerController, NoopAutoBackupRunner)
        driveIdle()
        assertEquals(SessionState.LoggedOut, viewModel.sessionState.value)
    }

    @Test
    fun `持久化态已登录时呈 LoggedIn（杀进程重启直进壳）`() {
        val viewModel = MainViewModel(FakeAuthRepository(initialLoggedIn = true), NoopEmbeddedServerController, NoopAutoBackupRunner)
        driveIdle()
        assertEquals(SessionState.LoggedIn, viewModel.sessionState.value)
    }

    @Test
    fun `401 事件即时退登录页（不等 token 写盘落地）`() {
        val repository = FakeAuthRepository(initialLoggedIn = true)
        val viewModel = MainViewModel(repository, NoopEmbeddedServerController, NoopAutoBackupRunner)
        driveIdle()
        assertEquals(SessionState.LoggedIn, viewModel.sessionState.value)
        repository.emitUnauthorized()
        driveIdle()
        assertEquals(SessionState.LoggedOut, viewModel.sessionState.value)
    }

    @Test
    fun `重新登录成功复位过期标记（下一次 401 仍可触发跳转）`() {
        val repository = FakeAuthRepository(initialLoggedIn = true)
        val viewModel = MainViewModel(repository, NoopEmbeddedServerController, NoopAutoBackupRunner)
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

    @Test
    fun `前台回归透传最近地址给健康检查（2026-09-25 冻结自检接线）`() {
        val repository = FakeAuthRepository(initialServerUrl = "http://192.0.2.10:18430")
        val controller = RecordingEmbeddedServerController()
        val viewModel = MainViewModel(repository, controller, NoopAutoBackupRunner)
        driveIdle()
        viewModel.onAppForeground()
        assertEquals(listOf("http://192.0.2.10:18430"), controller.healthCheckUrls)
    }

    @Test
    fun `地址流尚无首个值时前台自检不触发`() {
        val controller = RecordingEmbeddedServerController()
        val viewModel = MainViewModel(FakeAuthRepository(), controller, NoopAutoBackupRunner)
        driveIdle()
        viewModel.onAppForeground()
        assertEquals(emptyList<String>(), controller.healthCheckUrls)
    }
}

/** U11 批次D：壳层自检消费桩——地址流驱动启停的触发不进本测试的关注面 */
private object NoopEmbeddedServerController : EmbeddedServerController {
    override fun ensureStartedIfLocalMode(serverUrl: String): Boolean = false
    override fun ensureHealthyIfLocalMode(serverUrl: String): Boolean = false
    override fun stop() = Unit
}

/** 前台回归自检（2026-09-25 冻结事故）的记录桩：断言触发条件与透传地址 */
private class RecordingEmbeddedServerController : EmbeddedServerController {
    val healthCheckUrls = mutableListOf<String>()
    override fun ensureStartedIfLocalMode(serverUrl: String): Boolean = false
    override fun ensureHealthyIfLocalMode(serverUrl: String): Boolean {
        healthCheckUrls += serverUrl
        return true
    }
    override fun stop() = Unit
}

/**
 * AutoBackupRunner 空壳桩（2026-09-16 接线）：MainViewModel 构造器新增该依赖后测试需要一个
 * 实例。AutoBackupRunner 是 final class，构造器又要求 @ApplicationContext Context——JVM 单测
 * 的 android.jar 桩类任何构造器都直接抛 Stub!（本模块未开 returnDefaultValues，且无
 * Robolectric/Mock 框架可用），故以 sun.misc.Unsafe.allocateInstance 绕开构造链造空壳实例
 * （字段全 null）。Unsafe 走纯反射拿（Kotlin 的 jdk-release 编译视图不解析 sun.misc 编译期
 * 符号）。安全性已核：MainViewModel 侧 runCatching 包裹 runIfDue，桩在读 prefs.state 首行即
 * NPE 被吞；Context/contentResolver 永不被触碰，本测试只断言会话状态机。
 */
private val NoopAutoBackupRunner: AutoBackupRunner = run {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
    unsafeField.isAccessible = true
    val unsafe = unsafeField.get(null)
    unsafeClass
        .getMethod("allocateInstance", Class::class.java)
        .invoke(unsafe, AutoBackupRunner::class.java) as AutoBackupRunner
}
