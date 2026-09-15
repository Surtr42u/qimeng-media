package media.qimeng.app.core.data.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选文件夹上传纯逻辑锁定（U10-6c）：扩展名白名单过滤（口径=服务端
 * filing/upload.go allowedExtensions）+ 单次上限截断计数（口径③）。
 */
class FolderScanPolicyTest {

    // ---- FolderScanPolicy.isUploadableName ----

    @Test
    fun `白名单扩展名可上传`() {
        // 全量对照 server/internal/filing/upload.go:30-43 allowedExtensions
        val names = listOf(
            "a.jpg", "a.jpeg", "a.PNG", "a.gif", "a.webp", "a.avif",
            "a.mp4", "a.m4v", "a.mkv", "a.webm", "a.MOV", "a.avi",
        )
        names.forEach { name -> assertTrue(name, FolderScanPolicy.isUploadableName(name)) }
    }

    @Test
    fun `白名单外与无扩展名不可上传`() {
        assertFalse(FolderScanPolicy.isUploadableName("a.txt"))
        assertFalse(FolderScanPolicy.isUploadableName("a.pdf"))
        assertFalse(FolderScanPolicy.isUploadableName("a.jpg.exe"))
        assertFalse(FolderScanPolicy.isUploadableName("无扩展名"))
        assertFalse(FolderScanPolicy.isUploadableName("结尾点."))
    }

    @Test
    fun `扩展名大小写不敏感`() {
        assertTrue(FolderScanPolicy.isUploadableName("photo.JPG"))
        assertTrue(FolderScanPolicy.isUploadableName("VIDEO.Mp4"))
    }

    // ---- UploadableFileSink：截断与计数（口径③）----

    private fun item(name: String) = media.qimeng.app.core.model.UploadItem(
        uri = "content://x/$name",
        displayName = name,
        sizeBytes = 1L,
        relativeDir = "作者A",
    )

    @Test
    fun `未达上限全量收录且不截断`() {
        val sink = UploadableFileSink()
        repeat(3) { sink.addUploadable(item("f$it.jpg")) }
        sink.addSkipped()
        val result = sink.toResult()
        assertEquals(3, result.files.size)
        assertEquals(1, result.skippedCount)
        assertEquals(3, result.totalUploadable)
        assertFalse(result.truncated)
        // f0ee67f 引入的断言自相矛盾（前一行 size==3 与 single() 要求唯一元素互斥），
        // 裁定=测试 bug 非生产回归：single() 必抛 IllegalArgumentException，其余 4 用例
        // 已锁定截断/计数语义且全绿。本行意图=验证 relativeDir 透传，改按逐元素断言。
        result.files.forEach { assertEquals("作者A", it.relativeDir) }
    }

    @Test
    fun `超上限截断保留前N个且总数照计`() {
        val sink = UploadableFileSink(maxFiles = 3)
        repeat(5) { sink.addUploadable(item("f$it.jpg")) }
        val result = sink.toResult()
        assertEquals(listOf("f0.jpg", "f1.jpg", "f2.jpg"), result.files.map { it.displayName })
        assertEquals(5, result.totalUploadable)
        assertTrue(result.truncated)
    }

    @Test
    fun `恰好等于上限不截断`() {
        val sink = UploadableFileSink(maxFiles = 3)
        repeat(3) { sink.addUploadable(item("f$it.jpg")) }
        val result = sink.toResult()
        assertEquals(3, result.files.size)
        assertFalse(result.truncated)
    }

    @Test
    fun `默认上限为一千`() {
        assertEquals(1000, FolderScanPolicy.MAX_FOLDER_FILES)
        val sink = UploadableFileSink()
        repeat(FolderScanPolicy.MAX_FOLDER_FILES + 1) { sink.addUploadable(item("f$it.jpg")) }
        val result = sink.toResult()
        assertEquals(FolderScanPolicy.MAX_FOLDER_FILES, result.files.size)
        assertTrue(result.truncated)
    }
}
