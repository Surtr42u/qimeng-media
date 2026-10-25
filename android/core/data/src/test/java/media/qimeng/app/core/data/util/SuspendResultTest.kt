package media.qimeng.app.core.data.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SuspendResultTest {
    @Test fun `挂起后取消不发布失败也不继续重试`() = runTest {
        val entered = CompletableDeferred<Unit>()
        var failures = 0
        var attempts = 0
        val job = launch {
            repeat(3) {
                attempts++
                runSuspendCatching { entered.complete(Unit); awaitCancellation() }.onFailure { failures++ }
            }
        }
        entered.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, attempts)
        assertEquals(0, failures)
    }

    @Test fun `普通失败仍作为业务结果返回`() = runTest {
        val failure = IllegalStateException("synthetic")
        assertSame(failure, runSuspendCatching { throw failure }.exceptionOrNull())
        assertEquals(42, runSuspendCatching { 42 }.getOrThrow())
    }
}
