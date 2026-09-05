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
    fun `2xx解析最终文件名`() {
        val body = """{"id":"uuid","fileName":"IMG (2).jpg","mediaType":"image"}"""
        assertEquals("IMG (2).jpg", UploadApiBodies.parseFinalFileName(body))
    }

    @Test
    fun `2xx缺fileName字段返回null由调用方回退`() {
        assertNull(UploadApiBodies.parseFinalFileName("""{"id":"uuid"}"""))
    }
}
