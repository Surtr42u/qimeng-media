package media.qimeng.app.core.data.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 上传响应体解析锁定：4xx 文案透传（M4-5 冻结口径）与最终文件名提取。 */
class UploadApiBodiesTest {

    @Test
    fun `4xx错误体透传code与message`() {
        val body = """{"code":"INVALID_TYPE","message":"不支持的文件类型"}"""
        assertEquals("INVALID_TYPE 不支持的文件类型", UploadApiBodies.serverErrorMessage(body, 400))
    }

    @Test
    fun `4xx错误体缺字段回退兜底文案`() {
        assertEquals("服务端拒绝（HTTP 403）", UploadApiBodies.serverErrorMessage("{}", 403))
        assertEquals("服务端拒绝（HTTP 403）", UploadApiBodies.serverErrorMessage("  ", 403))
    }

    @Test
    fun `4xx响应体非JSON回退兜底文案`() {
        assertEquals("服务端拒绝（HTTP 413）", UploadApiBodies.serverErrorMessage("<html>bad</html>", 413))
    }

    @Test
    fun `2xx解析id与最终文件名`() {
        val body = """{"id":"3f2a7c1e-0000-0000-0000-000000000001","fileName":"IMG (2).jpg","mediaType":"image"}"""
        val parsed = UploadApiBodies.parseUploadResult(body)
        assertEquals("3f2a7c1e-0000-0000-0000-000000000001", parsed?.id)
        assertEquals("IMG (2).jpg", parsed?.fileName)
    }

    @Test
    fun `2xx缺id字段id为null由worker收敛挂靠失败`() {
        val parsed = UploadApiBodies.parseUploadResult("""{"fileName":"IMG (2).jpg"}""")
        assertEquals("IMG (2).jpg", parsed?.fileName)
        assertNull(parsed?.id)
    }

    @Test
    fun `2xx缺fileName字段fileName为null由调用方回退`() {
        val parsed = UploadApiBodies.parseUploadResult("""{"id":"uuid"}""")
        assertEquals("uuid", parsed?.id)
        assertNull(parsed?.fileName)
    }

    @Test
    fun `2xx响应体非JSON返回null`() {
        assertNull(UploadApiBodies.parseUploadResult("<html>bad</html>"))
        assertNull(UploadApiBodies.parseUploadResult(""))
    }
}
