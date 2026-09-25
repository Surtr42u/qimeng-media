package media.qimeng.app.core.data.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import media.qimeng.app.core.model.StagedUpload

/**
 * 收件箱 File 侧操作锁定（2026-09-25 暂存区重做）：扫描过滤（扩展名白名单/排除
 * uploaded/ 归档区/修改时间倒序/isVideo）、目录浏览（隐藏目录可见/名称升序）、
 * 存在性探测与 uploaded/ 归档。java.io.File 纯 JDK API，JVM 临时目录直测。
 */
class InboxFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val store = InboxFileStore()

    // ---- 收件箱扫描 ----

    @Test
    fun `扫描只收白名单媒体文件并按修改时间倒序`() {
        val inbox = tmp.newFolder("inbox")
        // old 先写、new 后写：同秒内 lastModified 可能相同，逐个拨修改时间保证可排序
        val old = write(inbox, "旧.jpg", content = "a")
        val middle = write(inbox, "中.png", content = "bb")
        val newest = write(inbox, "新.mp4", content = "ccc")
        old.setLastModified(1_000_000L)
        middle.setLastModified(2_000_000L)
        newest.setLastModified(3_000_000L)

        val scanned = store.scanInbox(inbox.absolutePath)

        assertEquals(listOf("新.mp4", "中.png", "旧.jpg"), scanned.map { it.displayName })
        assertTrue(scanned.all { it.isPathSource })
        assertEquals(newest.absolutePath, scanned.first().source)
    }

    @Test
    fun `扫描排除白名单外文件与点前缀隐藏文件`() {
        val inbox = tmp.newFolder("inbox")
        write(inbox, "keep.jpg")
        write(inbox, "note.txt")
        write(inbox, "virus.exe")
        write(inbox, ".nomedia")

        val scanned = store.scanInbox(inbox.absolutePath)

        assertEquals(listOf("keep.jpg"), scanned.map { it.displayName })
    }

    @Test
    fun `扫描排除uploaded归档子目录内文件且只列一级`() {
        val inbox = tmp.newFolder("inbox")
        write(inbox, "fresh.jpg")
        val uploaded = File(inbox, StagingRepository.UPLOADED_DIR_NAME).apply { mkdirs() }
        write(uploaded, "archived.jpg")

        val scanned = store.scanInbox(inbox.absolutePath)

        // uploaded/ 内的文件不出现（只列一级文件，双保险口径）
        assertEquals(listOf("fresh.jpg"), scanned.map { it.displayName })
    }

    @Test
    fun `扫描条目isVideo按扩展名判定且带文件元数据`() {
        val inbox = tmp.newFolder("inbox")
        val video = write(inbox, "clip.MP4", content = "video")
        val image = write(inbox, "pic.jpg", content = "image")

        val scanned = store.scanInbox(inbox.absolutePath).associateBy { it.displayName }

        assertTrue(scanned.getValue("clip.MP4").isVideo)
        assertFalse(scanned.getValue("pic.jpg").isVideo)
        assertEquals(video.length(), scanned.getValue("clip.MP4").sizeBytes)
        assertEquals(image.length(), scanned.getValue("pic.jpg").sizeBytes)
    }

    @Test
    fun `扫描根不存在或非目录返回空`() {
        assertTrue(store.scanInbox(tmp.root.resolve("不存在").absolutePath).isEmpty())
        val file = tmp.newFile("plain.txt")
        assertTrue(store.scanInbox(file.absolutePath).isEmpty())
    }

    // ---- 目录浏览 ----

    @Test
    fun `目录浏览含点前缀隐藏目录并按名称升序`() {
        val root = tmp.newFolder("root")
        tmp.newFolder("root/下载")
        tmp.newFolder("root/.hidden")
        tmp.newFolder("root/Album")
        tmp.newFile("root/loose.jpg") // 文件不进目录列表

        val entries = store.listDirectories(root.absolutePath)

        assertEquals(listOf(".hidden", "Album", "下载"), entries.map { it.name })
        assertTrue(entries.all { it.path.startsWith(root.absolutePath) })
    }

    @Test
    fun `目录浏览非法路径返回空`() {
        assertTrue(store.listDirectories(tmp.root.resolve("无此目录").absolutePath).isEmpty())
        assertTrue(store.listDirectories("").isEmpty())
    }

    // ---- 存在性探测 ----

    @Test
    fun `路径类条目存在性看文件真实存在`() {
        val file = tmp.newFile("a.jpg")
        val present = StagedUpload(
            source = file.absolutePath,
            isPathSource = true,
            displayName = "a.jpg",
            sizeBytes = 1,
            isVideo = false,
        )
        val absent = present.copy(source = file.absolutePath + ".gone")

        assertTrue(store.sourceExists(present))
        assertFalse(store.sourceExists(absent))
    }

    @Test
    fun `content类条目恒视为存在`() {
        val item = StagedUpload(
            source = "content://media/external/images/1",
            isPathSource = false,
            displayName = "a.jpg",
            sizeBytes = 1,
            isVideo = false,
        )
        assertTrue(store.sourceExists(item))
    }

    // ---- uploaded/ 归档 ----

    @Test
    fun `归档把源文件移入同目录uploaded子目录`() {
        val inbox = tmp.newFolder("inbox")
        val source = write(inbox, "done.jpg", content = "payload")

        assertTrue(store.archiveToUploaded(source.absolutePath))

        val archived = File(File(inbox, StagingRepository.UPLOADED_DIR_NAME), "done.jpg")
        assertTrue(archived.isFile)
        assertEquals("payload", archived.readText())
        assertFalse(source.exists())
    }

    @Test
    fun `归档目标子目录不存在时自动创建`() {
        val inbox = tmp.newFolder("inbox")
        val source = write(inbox, "auto.jpg")

        assertTrue(store.archiveToUploaded(source.absolutePath))
        assertTrue(File(File(inbox, StagingRepository.UPLOADED_DIR_NAME), "auto.jpg").isFile)
    }

    @Test
    fun `归档源文件不存在返回false不抛错`() {
        assertFalse(store.archiveToUploaded(tmp.root.resolve("不存在.jpg").absolutePath))
    }

    /** 写入测试文件（内容默认一行，返回文件引用） */
    private fun write(dir: File, name: String, content: String = "x"): File =
        File(dir, name).apply { writeText(content) }
}
