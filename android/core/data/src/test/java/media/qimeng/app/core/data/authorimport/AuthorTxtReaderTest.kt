package media.qimeng.app.core.data.authorimport

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import media.qimeng.sdk.infrastructure.Serializer
import media.qimeng.sdk.models.ApiV1AuthorsImportTxtPostRequest
import org.junit.Assert.*
import org.junit.Test

class AuthorTxtReaderTest {
    @Test fun `预检拒绝扩展名和声明超限且不打开流`() = runTest {
        for ((name, size) in listOf("wrong.bin" to 1L, "large.txt" to (AUTHOR_TXT_REQUEST_MAX_BYTES + 1L))) {
            try {
                readAuthorTxtDocument(name, size) { error("must not open") }
                fail("expected rejection")
            } catch (_: AuthorTxtReadException) { }
        }
    }

    @Test fun `未知大小空文件与UTF8保持既有解码策略`() = runTest {
        for (bytes in listOf(byteArrayOf(), "\uFEFF作者\n作品.jpg".toByteArray(), byteArrayOf(0xFF.toByte()))) {
            val doc = readAuthorTxtDocument("合成.TXT", null) { ByteArrayInputStream(bytes) }
            assertEquals(bytes.toString(Charsets.UTF_8), doc.content)
        }
    }

    @Test fun `实际JSON恰好上限通过而多一字节拒绝`() = runTest {
        val adapter = Serializer.moshi.adapter(ApiV1AuthorsImportTxtPostRequest::class.java)
        val overhead = adapter.toJson(ApiV1AuthorsImportTxtPostRequest(filename = "a.txt", content = "")).toByteArray().size
        val content = "x".repeat(AUTHOR_TXT_REQUEST_MAX_BYTES - overhead)
        assertEquals(content, readAuthorTxtDocument("a.txt", null) { content.byteInputStream() }.content)
        try {
            readAuthorTxtDocument("a.txt", 1L) { (content + "x").byteInputStream() }
            fail("JSON overhead must count")
        } catch (_: AuthorTxtReadException) { }
    }

    @Test fun `转义与非法编码扩张按SDK请求计数`() = runTest {
        for (bytes in listOf("\n".repeat(AUTHOR_TXT_REQUEST_MAX_BYTES / 2).toByteArray(), ByteArray(AUTHOR_TXT_REQUEST_MAX_BYTES / 2) { 0xFF.toByte() })) {
            try {
                readAuthorTxtDocument("a.txt", null) { ByteArrayInputStream(bytes) }
                fail("expanded request must reject")
            } catch (_: AuthorTxtReadException) { }
        }
    }

    @Test fun `假报小体积和增长流至多读取上限加一并关闭`() = runTest {
        var consumed = 0
        var closed = false
        val growing = object : InputStream() {
            override fun read(): Int { consumed++; return 'x'.code }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                bytes.fill('x'.code.toByte(), offset, offset + length)
                consumed += length
                return length
            }
            override fun close() { closed = true }
        }
        try {
            readAuthorTxtDocument("a.txt", 1L) { growing }
            fail("must stop growing stream")
        } catch (_: AuthorTxtReadException) { }
        assertEquals(AUTHOR_TXT_REQUEST_MAX_BYTES + 1, consumed)
        assertTrue(closed)
    }

    @Test fun `打开失败保持可重试且读取取消关闭流继续传播`() = runTest {
        try {
            readAuthorTxtDocument("a.txt", null) { throw IOException("synthetic open failure") }
            fail("must fail")
        } catch (_: IOException) { }
        assertEquals("ok", readAuthorTxtDocument("a.txt", null) { "ok".byteInputStream() }.content)
        var closed = false
        var continued = false
        val job = launch {
            val ctx = currentCoroutineContext()
            val stream = object : InputStream() {
                override fun read(): Int { ctx.cancel(); return 'x'.code }
                override fun close() { closed = true }
            }
            try {
                readAuthorTxtDocument("a.txt", null) { stream }
                continued = true
            } catch (e: CancellationException) { throw e }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(closed)
        assertFalse(continued)
    }
}
