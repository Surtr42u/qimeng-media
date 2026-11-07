package media.qimeng.app.feature.search

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 搜索结果缩略图下短文件名纯函数（2026-11-07 用户需求）：
 * 去掉扩展名保持简短，但必须保留作品编号与组图页码；不改变真实文件名身份。
 */
class SearchFileCaptionTest {

    @Test
    fun `组图页去掉扩展名后保留作品编号与页码`() {
        assertEquals("守望先锋 朱诺 11 (2)", searchFileCaption("守望先锋 朱诺 11 (2).png"))
        assertEquals("朱诺 10", searchFileCaption("朱诺 10.mp4"))
    }

    @Test
    fun `编号边界不因截断混淆`() {
        assertEquals("朱诺 1", searchFileCaption("朱诺 1.png"))
        assertEquals("朱诺 10", searchFileCaption("朱诺 10.png"))
        assertEquals("朱诺 110", searchFileCaption("朱诺 110.jpg"))
        assertEquals("朱诺 11 (1)", searchFileCaption("朱诺 11 (1).webp"))
    }

    @Test
    fun `无扩展名或点开头文件名原样保留`() {
        assertEquals("无扩展名文件", searchFileCaption("无扩展名文件"))
        assertEquals("", searchFileCaption("   "))
        assertEquals(".hidden", searchFileCaption(".hidden"))
    }
}
