package media.qimeng.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 缩略图覆盖进度纯函数锁定（批S4 2026-09-19 勘误）：分子 thumbsOnDisk 是缓存目录
 * 落盘**文件数**（多档并存按文件计 + 已删资产遗留孤儿，真库 2026-09-19 只读实测
 * 7386 文件 vs 6341 资产），可 > 分母——fraction 必须钳制 0~1，否则进度条越界。
 */
class ThumbnailCacheProgressTest {

    @Test
    fun `常规比例直接返回`() {
        val progress = ThumbnailCacheProgress(totalAssets = 100, thumbsOnDisk = 50)
        assertEquals(0.5f, progress.fraction)
    }

    @Test
    fun `分子大于分母时钳制为1`() {
        // 真库口径：文件数（6477 含多档与孤儿）> 资产数（6341）
        val progress = ThumbnailCacheProgress(totalAssets = 6341, thumbsOnDisk = 6477)
        assertEquals(1f, progress.fraction)
    }

    @Test
    fun `分母为0视为已满`() {
        val progress = ThumbnailCacheProgress(totalAssets = 0, thumbsOnDisk = 0)
        assertEquals(1f, progress.fraction)
    }

    @Test
    fun `分子为0返回0`() {
        val progress = ThumbnailCacheProgress(totalAssets = 100, thumbsOnDisk = 0)
        assertEquals(0f, progress.fraction)
    }
}
