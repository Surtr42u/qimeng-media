package media.qimeng.app.core.data.embedded

import kotlinx.coroutines.sync.Mutex

/** 前台恢复编排：单飞、缺进程补启动、两次探测，检查期间换代则放弃旧快照。 */
internal class ChildHealthRecovery<P : Any>(
    private val current: () -> P?,
    private val alive: (P) -> Boolean,
    private val ensureStarted: suspend () -> Unit,
    private val probe: suspend () -> Boolean,
    private val pauseBeforeRecheck: suspend () -> Unit,
    private val restart: suspend (P) -> Unit,
) {
    private val gate = Mutex()

    suspend fun runOnce() {
        if (!gate.tryLock()) return
        try {
            val child = current()
            if (child == null || !alive(child)) {
                ensureStarted()
                return
            }
            if (probe()) return
            pauseBeforeRecheck()
            if (current() !== child) return
            if (!probe()) restart(child)
        } finally {
            gate.unlock()
        }
    }
}
