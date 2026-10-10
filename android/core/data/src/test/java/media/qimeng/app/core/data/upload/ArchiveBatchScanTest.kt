package media.qimeng.app.core.data.upload

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import media.qimeng.app.core.model.LibraryChoice

/**
 * 归档一键重传扫描锁定（2026-10-01 归档一键上传）：库匹配（精确/sanitize/歧义/未命中）、
 * 递归收集（relDir 相对路径/扩展名白名单/点前缀跳过）、排序与字节汇总。
 * java.io.File 纯 JDK API，JVM 临时目录直测（InboxFileStoreTest 同款方式）。
 */
class ArchiveBatchScanTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun scan(root: File, vararg libraries: LibraryChoice) =
        scanArchiveForUpload(root, libraries.toList())

    private fun library(id: String, name: String) = LibraryChoice(id = id, name = name)

    private fun write(dir: File, name: String, content: String = "x"): File {
        val file = File(dir, name)
        file.writeText(content)
        return file
    }

    // ---- 空结果 ----

    @Test
    fun `归档根不存在返回空结果`() {
        val result = scan(tmp.root.resolve("不存在"), library("lib-a", "测试库A"))
        assertTrue(result.items.isEmpty())
        assertTrue(result.unmatchedFolders.isEmpty())
        assertEquals(0L, result.totalBytes)
        assertEquals(0, result.skippedCount)
    }

    @Test
    fun `归档根为空目录返回空结果`() {
        val result = scan(tmp.newFolder("空归档"))
        assertTrue(result.items.isEmpty())
        assertTrue(result.unmatchedFolders.isEmpty())
    }

    // ---- 库匹配 ----

    @Test
    fun `文件夹名精确等于库名时命中该库`() {
        val root = tmp.newFolder("归档")
        val libDir = tmp.newFolder("归档/测试库A")
        write(libDir, "a.jpg", content = "12345")

        val result = scan(root, library("lib-a", "测试库A"))

        val item = result.items.single()
        assertEquals("lib-a", item.libraryId)
        assertEquals("测试库A", item.libraryName)
        assertEquals("", item.relDir)
        assertEquals("12345".length.toLong(), result.totalBytes)
        assertTrue(result.unmatchedFolders.isEmpty())
    }

    @Test
    fun `库名sanitize后与文件夹同名时命中——非法字符对应下划线`() {
        val root = tmp.newFolder("归档")
        val libDir = tmp.newFolder("归档/a_b")
        write(libDir, "v.mp4")

        val result = scan(root, library("lib-b", "a:b"))

        assertEquals("lib-b", result.items.single().libraryId)
        assertTrue(result.unmatchedFolders.isEmpty())
    }

    @Test
    fun `两个库名sanitize撞名时该文件夹判歧义不上传`() {
        val root = tmp.newFolder("归档")
        // 归档写入侧按 sanitize(库名) 建目录：库 "a/b" 与 "a\b" 都落成 "a_b" 文件夹
        val libDir = tmp.newFolder("归档/a_b")
        write(libDir, "v.mp4")

        val result = scan(root, library("lib-1", "a/b"), library("lib-2", "a\\b"))

        assertTrue(result.items.isEmpty())
        val unmatched = result.unmatchedFolders.single()
        assertEquals("a_b", unmatched.first)
        assertEquals(ARCHIVE_BATCH_REASON_AMBIGUOUS, unmatched.second)
    }

    @Test
    fun `库名多空格与文件夹单空格时通过规范化折叠命中`() {
        val root = tmp.newFolder("归档")
        val libDir = tmp.newFolder("归档/1 3D")
        write(libDir, "v.mp4")

        // 库名带有两个空格 "1  3D"，文件夹名为单空格 "1 3D"
        val result = scan(root, library("lib-3d", "1  3D"))

        val item = result.items.single()
        assertEquals("lib-3d", item.libraryId)
        assertEquals("1  3D", item.libraryName)
        assertTrue(result.unmatchedFolders.isEmpty())
    }

    @Test
    fun `未命中文件夹记未找到同名库`() {
        val root = tmp.newFolder("归档")
        tmp.newFolder("归档/未知库")

        val result = scan(root, library("lib-a", "测试库A"))

        val unmatched = result.unmatchedFolders.single()
        assertEquals("未知库", unmatched.first)
        assertEquals(ARCHIVE_BATCH_REASON_NO_LIBRARY, unmatched.second)
        assertTrue(result.items.isEmpty())
    }

    @Test
    fun `一级点前缀目录整目录跳过且不算未匹配`() {
        val root = tmp.newFolder("归档")
        tmp.newFolder("归档/.隐藏")

        val result = scan(root, library("lib-a", "测试库A"))

        assertTrue(result.items.isEmpty())
        assertTrue(result.unmatchedFolders.isEmpty())
        assertEquals(0, result.skippedCount)
    }

    // ---- 文件收集 ----

    @Test
    fun `非媒体扩展名文件计入跳过`() {
        val root = tmp.newFolder("归档")
        val libDir = tmp.newFolder("归档/测试库A")
        write(libDir, "note.txt")
        write(libDir, "poster.jpg")

        val result = scan(root, library("lib-a", "测试库A"))

        assertEquals(listOf("poster.jpg"), result.items.map { it.file.name })
        assertEquals(1, result.skippedCount)
    }

    @Test
    fun `扩展名大小写不敏感命中白名单`() {
        val root = tmp.newFolder("归档")
        val libDir = tmp.newFolder("归档/测试库A")
        write(libDir, "X.JPG")

        assertEquals("X.JPG", scan(root, library("lib-a", "测试库A")).items.single().file.name)
    }

    @Test
    fun `点前缀文件与目录跳过计入skipped且不递归隐藏目录`() {
        val root = tmp.newFolder("归档")
        val libDir = tmp.newFolder("归档/测试库A")
        tmp.newFolder("归档/测试库A/.tmp")
        write(File(libDir, ".tmp"), "inner.jpg") // 隐藏目录内不递归，不计条目
        write(libDir, ".hidden.jpg")
        write(libDir, "v.mp4")

        val result = scan(root, library("lib-a", "测试库A"))

        assertEquals(listOf("v.mp4"), result.items.map { it.file.name })
        assertEquals(2, result.skippedCount)
    }

    @Test
    fun `递归收集子目录且relDir为文件夹内相对路径`() {
        val root = tmp.newFolder("归档")
        val libDir = tmp.newFolder("归档/测试库A")
        tmp.newFolder("归档/测试库A/sub/deep")
        write(libDir, "top.jpg")
        write(File(libDir, "sub/deep"), "v.mp4")

        val result = scan(root, library("lib-a", "测试库A"))

        val byName = result.items.associateBy { it.file.name }
        assertEquals("", byName.getValue("top.jpg").relDir)
        assertEquals("sub/deep", byName.getValue("v.mp4").relDir)
    }

    // ---- 排序与汇总 ----

    @Test
    fun `结果按文件夹名与相对路径稳定排序`() {
        // 文件夹名取码点顺序无歧义的（库a < 库b；中文字符码点序与直觉序可能相反）
        val root = tmp.newFolder("归档")
        val libA = tmp.newFolder("归档/库a")
        val libASub = tmp.newFolder("归档/库a/sub")
        val libB = tmp.newFolder("归档/库b")
        write(libASub, "2.jpg")
        write(libA, "1.jpg")
        write(libB, "9.jpg")

        val result = scan(root, library("lib-a", "库a"), library("lib-b", "库b"))

        assertEquals(
            listOf("库a/1.jpg", "库a/sub/2.jpg", "库b/9.jpg"),
            result.items.map { item ->
                val rel = if (item.relDir.isEmpty()) item.file.name else "${item.relDir}/${item.file.name}"
                "${item.libraryName}/$rel"
            },
        )
    }

    @Test
    fun `totalBytes为全部条目字节求和`() {
        val root = tmp.newFolder("归档")
        val libDir = tmp.newFolder("归档/测试库A")
        write(libDir, "a.jpg", content = "123") // 3 字节
        write(libDir, "b.jpg", content = "12345") // 5 字节

        assertEquals(8L, scan(root, library("lib-a", "测试库A")).totalBytes)
    }

    @Test
    fun `同名文件夹同库多文件归并到同一目标库`() {
        val root = tmp.newFolder("归档")
        val libDirA = tmp.newFolder("归档/测试库A/2026")
        val libDirB = tmp.newFolder("归档/测试库A/2027")
        write(libDirA, "a.jpg")
        write(libDirB, "b.jpg")

        val result = scan(root, library("lib-a", "测试库A"))

        assertEquals(2, result.items.size)
        assertTrue(result.items.all { it.libraryId == "lib-a" })
        assertEquals(listOf("2026", "2027"), result.items.map { it.relDir })
    }
}
