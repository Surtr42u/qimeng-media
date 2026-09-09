package media.qimeng.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * query 值编码单测（RES R1，feature:author AuthorCollectionRoutesTest 同款处置）：
 * 锁定 [encodeQueryValue] 的百分号编码行为——query 结构字符（&/#）不编码会劈裂 query
 * 或吞掉后续参数；多字节（中文/emoji）按 UTF-8 字节序编码；unreserved 集原样保留。
 * 编码器为壳层自持（Uri.encode 行为不确定性，见函数 KDoc），行为由本测锁定。
 */
class EncodeQueryValueTest {

    @Test
    fun `query编码 - unreserved字符原样保留`() {
        assertEquals("abcXYZ019" + "-_.~", encodeQueryValue("abcXYZ019" + "-_.~"))
    }

    @Test
    fun `query编码 - 中文按UTF-8字节百分号编码`() {
        // 「作」= E4 BD 9C、「者」= E8 80 85（UTF-8），与 encodeRouteSegment 中文断言同款口径
        assertEquals("%E4%BD%9C%E8%80%85", encodeQueryValue("作者"))
    }

    @Test
    fun `query编码 - ampersand与井号必须编码（劈裂query或吞参）`() {
        assertEquals("%26", encodeQueryValue("&"))
        assertEquals("%23", encodeQueryValue("#"))
        // 混合：空格同批编码，~ 属 unreserved 保留
        assertEquals("a%20b%26c%23d~", encodeQueryValue("a b&c#d~"))
    }

    @Test
    fun `query编码 - 空格编码为百分号20`() {
        assertEquals("%20", encodeQueryValue(" "))
        assertEquals("%E4%BD%9C%E8%80%85%20%E8%A0%A2", encodeQueryValue("作者 蠢"))
    }

    @Test
    fun `query编码 - 多字节emoji按UTF-8四字节序列编码`() {
        // U+1F600（😀）= F0 9F 98 80（UTF-8 四字节序列，逐字节 %XX）
        assertEquals("%F0%9F%98%80", encodeQueryValue("😀"))
    }

    @Test
    fun `路由构建 - searchRoute携词参数经编码拼接（q键单源）`() {
        assertEquals(
            "search?q=%E4%BD%9C%E8%80%85",
            Routes.searchRoute("作者"),
        )
        assertEquals("search?q=%26tag", Routes.searchRoute("&tag"))
    }
}
