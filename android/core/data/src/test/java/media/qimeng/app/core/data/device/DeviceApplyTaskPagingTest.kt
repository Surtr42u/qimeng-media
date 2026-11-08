package media.qimeng.app.core.data.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 设备结果包列表分页（2026-11-08 修复「只看第一页」）：
 * 用假页源验证多页遍历、页序保持与硬上限行为，不依赖 SDK/网络。
 */
class DeviceApplyTaskPagingTest {

    private fun summary(id: String) = DeviceApplySummary(
        id = id,
        targetDeviceId = "phone-main",
        status = DeviceTaskStatus.PENDING,
        protocolVersion = DeviceApplyPolicy.PROTOCOL_VERSION,
        rulesFingerprint = "a".repeat(64),
    )

    /** 记录请求游标的假页源：pages 按调用顺序返回，超出后重复最后一页（模拟游标异常）。 */
    private class FakePageSource(
        private val pages: List<DeviceApplyTaskPage>,
    ) : DeviceApplyTaskPageSource {
        val requestedCursors = mutableListOf<String?>()
        var calls = 0

        override fun fetch(cursor: String?): DeviceApplyTaskPage {
            requestedCursors += cursor
            calls++
            return pages.getOrElse(calls - 1) { pages.last() }
        }
    }

    @Test
    fun `多页遍历跟随nextCursor直到null并保持服务端倒序`() {
        val source = FakePageSource(
            listOf(
                DeviceApplyTaskPage(listOf(summary("a"), summary("b")), nextCursor = "c2"),
                DeviceApplyTaskPage(listOf(summary("c")), nextCursor = "c3"),
                DeviceApplyTaskPage(listOf(summary("d")), nextCursor = null),
            ),
        )

        val items = DeviceApplyTaskPaging.collectAll(source)

        assertEquals(listOf("a", "b", "c", "d"), items.map { it.id })
        assertEquals(listOf(null, "c2", "c3"), source.requestedCursors)
    }

    @Test
    fun `最后一页nextCursor为null时即使正好用满页数也正常结束`() {
        val source = FakePageSource(
            listOf(
                DeviceApplyTaskPage(listOf(summary("a")), nextCursor = "c2"),
                DeviceApplyTaskPage(listOf(summary("b")), nextCursor = null),
            ),
        )

        val items = DeviceApplyTaskPaging.collectAll(source, maxPages = 2)

        assertEquals(listOf("a", "b"), items.map { it.id })
        assertEquals(2, source.calls)
    }

    @Test
    fun `超过页数上限抛出明确异常并停止继续翻页`() {
        val source = FakePageSource(
            listOf(
                DeviceApplyTaskPage(listOf(summary("a")), nextCursor = "c2"),
                DeviceApplyTaskPage(listOf(summary("b")), nextCursor = "c3"),
                DeviceApplyTaskPage(listOf(summary("c")), nextCursor = "c4"),
                DeviceApplyTaskPage(listOf(summary("d")), nextCursor = "c5"),
            ),
        )

        val failure = runCatching { DeviceApplyTaskPaging.collectAll(source, maxPages = 3) }
            .exceptionOrNull()

        assertTrue(failure is DeviceApplyPageLimitException)
        val limit = failure as DeviceApplyPageLimitException
        assertEquals(3, limit.maxPages)
        assertEquals(3, limit.collected)
        // 硬上限：只请求 3 页，绝不继续跟随第 3 页返回的游标。
        assertEquals(3, source.calls)
        assertTrue(limit.message!!.contains("分页上限"))
    }

    @Test
    fun `空页但游标非空仍继续遍历`() {
        val source = FakePageSource(
            listOf(
                DeviceApplyTaskPage(emptyList(), nextCursor = "c2"),
                DeviceApplyTaskPage(listOf(summary("a")), nextCursor = null),
            ),
        )

        val items = DeviceApplyTaskPaging.collectAll(source)

        assertEquals(listOf("a"), items.map { it.id })
        assertEquals(listOf(null, "c2"), source.requestedCursors)
    }

    @Test
    fun `页数上限必须为正数`() {
        val source = FakePageSource(listOf(DeviceApplyTaskPage(emptyList(), nextCursor = null)))
        try {
            DeviceApplyTaskPaging.collectAll(source, maxPages = 0)
            fail("maxPages=0 应当被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertEquals(0, source.calls)
        }
    }
}
