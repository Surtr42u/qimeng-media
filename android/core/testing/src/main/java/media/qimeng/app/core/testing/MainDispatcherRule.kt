package media.qimeng.app.core.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * 把协程 `Dispatchers.Main` 替换为测试调度器的 JUnit 规则（ViewModel 依赖 Main 的 viewModelScope）。
 *
 * 测试体不进挂起上下文时，用 `rule.testDispatcher.scheduler.advanceUntilIdle()` 驱动虚拟时间；
 * 若配合 `runTest`，请传 `runTest(rule.testDispatcher)` 使两侧共享同一调度器。
 */
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
