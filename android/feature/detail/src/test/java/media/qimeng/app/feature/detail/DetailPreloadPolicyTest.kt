package media.qimeng.app.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 预载窗口策略单测（拍板③，口径冻结对照旧版 preloadAround）：
 * 窗口前 1 后 2 / 不含当前 / 越界不环绕 / 超大图（长边>4096）窗口内限量 1 张且距当前最近优先 /
 * 堆占用 ≥0.6 跳过全部超大图 / 宽高未知按普通图。纯函数零 Android 依赖（JVM 最快档）。
 */
class DetailPreloadPolicyTest {

    private fun noDims(id: String): ImageDims? = null

    @Test
    fun `窗口前1后2不含当前且按距当前最近序排列`() {
        val ids = listOf("a", "b", "c", "d", "e")
        // 当前 c（index=2）：+1=d、-1=b、+2=e，输出按最近序 [d, b, e]
        assertEquals(
            listOf("d", "b", "e"),
            DetailPreloadPolicy.computePreload(2, ids, ::noDims, heapUsedRatio = 0f),
        )
    }

    @Test
    fun `越界窗口自然为空不环绕`() {
        val ids = listOf("a", "b")
        // 首资产：-1/+2 越界只出 +1；尾资产：+1/+2 越界只出 -1——不环绕、无越界 id
        assertEquals(listOf("b"), DetailPreloadPolicy.computePreload(0, ids, ::noDims, 0f))
        assertEquals(listOf("a"), DetailPreloadPolicy.computePreload(1, ids, ::noDims, 0f))
        // 当前序号不在批次内（无批次上下文）→ 空
        assertEquals(emptyList<String>(), DetailPreloadPolicy.computePreload(-1, ids, ::noDims, 0f))
        assertEquals(emptyList<String>(), DetailPreloadPolicy.computePreload(2, ids, ::noDims, 0f))
    }

    @Test
    fun `超大图窗口内最多预载一张且距当前最近优先`() {
        val ids = listOf("a", "b", "c", "d", "e")
        val huge = { id: String -> if (id == "d" || id == "b" || id == "e") ImageDims(5000, 6000) else null }
        // 窗口序 [d, b, e] 全是超大图：d 最近→预载（第 1 张），b/e 撞限量→跳过
        assertEquals(
            listOf("d"),
            DetailPreloadPolicy.computePreload(2, ids, huge, heapUsedRatio = 0.3f),
        )
        // 只有 e 是超大图：d/b 普通照预，e 是第 1 张超大图→预载
        val onlyE = { id: String -> if (id == "e") ImageDims(5000, 6000) else null }
        assertEquals(
            listOf("d", "b", "e"),
            DetailPreloadPolicy.computePreload(2, ids, onlyE, heapUsedRatio = 0.3f),
        )
    }

    @Test
    fun `堆占用达到阈值跳过全部超大图普通图照常`() {
        val ids = listOf("a", "b", "c", "d", "e")
        val dims = { id: String -> if (id == "d" || id == "e") ImageDims(5000, 6000) else null }
        // 窗口序 [d(超大), b(普通), e(超大)]：heap=0.6 达阈值 → d/e 跳过，只留 b
        assertEquals(
            listOf("b"),
            DetailPreloadPolicy.computePreload(2, ids, dims, heapUsedRatio = DetailPreloadPolicy.HEAP_PRESSURE_RATIO),
        )
        // heap=0.59 未达阈值：d 是第 1 张超大图→预载，e 撞限量→跳过
        assertEquals(
            listOf("d", "b"),
            DetailPreloadPolicy.computePreload(2, ids, dims, heapUsedRatio = 0.59f),
        )
    }

    @Test
    fun `宽高未知或非法按普通图处理`() {
        val ids = listOf("a", "b", "c")
        // 窗口 [c, a]：c 尺寸未知（null）、a 非法尺寸（0x0，长边 0 ≤ 阈值）——都不算超大图，
        // 达堆阈值也照常预载（收紧只作用于「已知」超大图）
        val unknown = { id: String -> if (id == "c") null else ImageDims(0, 0) }
        assertEquals(
            listOf("c", "a"),
            DetailPreloadPolicy.computePreload(1, ids, unknown, heapUsedRatio = 0.9f),
        )
    }

    @Test
    fun `批次含重复id时跳过与当前相同的id - 防御分支`() {
        // 防御分支：窗口位 id == 当前资产 id（批次脏数据含重复）跳过——否则会对自己发预载
        val ids = listOf("b", "b", "c")
        // 当前 b（index=0）：+1 位是重复的 "b"（跳过），-1 越界，+2 = "c"（普通照预）
        assertEquals(
            listOf("c"),
            DetailPreloadPolicy.computePreload(0, ids, ::noDims, heapUsedRatio = 0f),
        )
    }
}
