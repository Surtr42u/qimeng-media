package media.qimeng.app.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailPreloadQueueTest {
    private val a = DetailPreloadTarget("a", "a.orig", false)
    private val b = DetailPreloadTarget("b", "b.orig", false)

    private class Requests {
        val started = mutableListOf<DetailPreloadTarget>()
        val cancelled = mutableListOf<DetailPreloadTarget>()
        val callbacks = mutableListOf<() -> Unit>()
        val queue = DetailPreloadQueue { target, finished ->
            started += target
            callbacks += finished
            val cancel: () -> Unit = { cancelled += target }
            cancel
        }
    }

    @Test
    fun growingTargetListKeepsInFlightAndRunsSerially() {
        val r = Requests()
        r.queue.update(listOf(a), true)
        r.queue.update(listOf(a, b), true)
        assertEquals(listOf(a), r.started)
        assertEquals(emptyList<DetailPreloadTarget>(), r.cancelled)
        r.callbacks[0]()
        assertEquals(listOf(a, b), r.started)
        r.callbacks[1]()
        r.queue.update(listOf(a, b), true)
        assertEquals(listOf(a, b), r.started)
    }

    @Test
    fun currentImageAndPausedScreenBlockPreloadAndLateCompletion() {
        val r = Requests()
        r.queue.update(listOf(a, b), false)
        assertEquals(0, r.started.size)
        r.queue.update(listOf(a, b), true)
        r.queue.pause()
        r.callbacks[0]()
        assertEquals(listOf(a), r.started)
        assertEquals(listOf(a), r.cancelled)
        r.queue.update(listOf(a, b), true)
        r.callbacks[0]() // 旧请求的第二次迟到回调仍不能完成新任务。
        assertEquals(listOf(a, a), r.started)
        r.callbacks[1]()
        assertEquals(listOf(a, a, b), r.started)
    }

    @Test
    fun changedUrlAndRemovedTargetCancelOnlyTheirOwnRequest() {
        val r = Requests()
        val replaced = a.copy(url = "a.new")
        r.queue.update(listOf(a, b), true)
        r.queue.update(listOf(replaced, b), true)
        assertEquals(listOf(a), r.cancelled)
        r.callbacks[0]()
        assertEquals(listOf(a, replaced), r.started)
        r.queue.update(listOf(b), true)
        assertEquals(listOf(a, replaced), r.cancelled)
        assertEquals(listOf(a, replaced, b), r.started)
        r.queue.clear()
        r.callbacks.last()()
        assertEquals(listOf(a, replaced, b), r.started)
    }

    @Test
    fun synchronousMemoryHitsDoNotCancelNextTaskOrLoop() {
        val started = mutableListOf<DetailPreloadTarget>()
        val released = mutableListOf<DetailPreloadTarget>()
        val queue = DetailPreloadQueue { target, finished ->
            started += target
            finished()
            val cancel: () -> Unit = { released += target }
            cancel
        }
        queue.update(listOf(a, b), true)
        queue.update(listOf(a, b), true)
        assertEquals(listOf(a, b), started)
        assertEquals(setOf(a, b), released.toSet())
    }
}
