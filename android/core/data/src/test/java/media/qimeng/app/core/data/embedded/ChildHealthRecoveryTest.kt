package media.qimeng.app.core.data.embedded

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChildHealthRecoveryTest {
    @Test fun `missing or dead child is started by foreground check`() = runTest {
        for (child in listOf(null, Any())) {
            var starts = 0
            ChildHealthRecovery(current = { child }, alive = { false },
                ensureStarted = { starts++ }, probe = { error("no child to probe") },
                pauseBeforeRecheck = {}, restart = { error("no child to kill") }).runOnce()
            assertEquals(1, starts)
        }
    }

    @Test fun `temporary probe failure does not kill recovered child`() = runTest {
        val child = Any(); var probes = 0; var restarts = 0
        ChildHealthRecovery(current = { child }, alive = { true }, ensureStarted = {},
            probe = { ++probes == 2 }, pauseBeforeRecheck = {}, restart = { restarts++ }).runOnce()
        assertEquals(2, probes)
        assertEquals(0, restarts)
    }

    @Test fun `two failures restart only captured generation`() = runTest {
        val child = Any(); val restarted = mutableListOf<Any>()
        ChildHealthRecovery(current = { child }, alive = { true }, ensureStarted = {},
            probe = { false }, pauseBeforeRecheck = {}, restart = { restarted += it }).runOnce()
        assertEquals(listOf(child), restarted)
        var current = child
        ChildHealthRecovery(current = { current }, alive = { true }, ensureStarted = {},
            probe = { false }, pauseBeforeRecheck = { current = Any() },
            restart = { error("must not kill replaced child") }).runOnce()
    }

    @Test fun `overlapping checks collapse and cancellation releases gate`() = runTest {
        val child = Any(); val blocked = CompletableDeferred<Unit>(); var probes = 0
        val recovery = ChildHealthRecovery(current = { child }, alive = { true }, ensureStarted = {},
            probe = { probes++; blocked.await(); true }, pauseBeforeRecheck = {}, restart = {})
        val first = launch { recovery.runOnce() }
        runCurrent()
        recovery.runOnce()
        assertEquals(1, probes)
        first.cancel(); first.join()
        blocked.complete(Unit)
        recovery.runOnce()
        assertEquals(2, probes)
    }
}
