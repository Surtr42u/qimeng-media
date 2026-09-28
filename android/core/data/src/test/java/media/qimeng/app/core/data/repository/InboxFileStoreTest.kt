package media.qimeng.app.core.data.repository

import java.io.File
import java.io.FileInputStream
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

    // ---- 目录文件列举（2026-09-28 上传页「浏览文件」弹层） ----

    @Test
    fun `文件列举只收白名单媒体文件并按名称升序`() {
        val dir = tmp.newFolder("browse")
        write(dir, "note.txt")
        write(dir, "app.exe")
        write(dir, "clip.MP4", content = "video") // 扩展名大小写不敏感
        write(dir, "b.jpg")
        write(dir, "a.png")

        val files = store.listMediaFiles(dir.absolutePath)

        assertEquals(listOf("a.png", "b.jpg", "clip.MP4"), files.map { it.name })
    }

    @Test
    fun `文件列举带绝对路径与字节数`() {
        val dir = tmp.newFolder("browse-meta")
        val file = write(dir, "pic.jpg", content = "12345")

        val entry = store.listMediaFiles(dir.absolutePath).single()

        assertEquals(file.absolutePath, entry.path)
        assertEquals(file.length(), entry.sizeBytes)
    }

    @Test
    fun `文件列举包含点前缀隐藏目录内的文件`() {
        // 隐藏目录内的合法媒体文件必须可见——这正是「浏览文件」入口存在的原因
        val hidden = File(tmp.newFolder("browse-hidden"), ".stash").apply { mkdirs() }
        write(hidden, "pic.jpg")
        write(hidden, "junk.exe")

        val files = store.listMediaFiles(hidden.absolutePath)

        assertEquals(listOf("pic.jpg"), files.map { it.name })
    }

    @Test
    fun `文件列举含点前缀隐藏媒体文件且不收子目录与深层文件`() {
        val dir = tmp.newFolder("browse-scope")
        write(dir, ".cover.jpg") // 隐藏媒体文件（扩展名合法）不按文件名排除
        val sub = File(dir, "sub").apply { mkdirs() }
        write(sub, "deep.jpg")

        val files = store.listMediaFiles(dir.absolutePath)

        assertEquals(listOf(".cover.jpg"), files.map { it.name })
    }

    @Test
    fun `文件列举根不存在或非目录返回空`() {
        assertTrue(store.listMediaFiles(tmp.root.resolve("不存在").absolutePath).isEmpty())
        val file = tmp.newFile("plain2.txt")
        assertTrue(store.listMediaFiles(file.absolutePath).isEmpty())
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

    // ---- 归档文件夹（archiveToLibraryRoot，2026-09-28 上传归档文件夹功能） ----

    @Test
    fun `归档到库根目录正常移动且原位置消失`() {
        val inbox = tmp.newFolder("inbox2")
        val archiveRoot = tmp.root.resolve("archive") // 归档根不存在：须连根一并创建
        val source = write(inbox, "done.mp4", content = "payload")

        assertTrue(store.archiveToLibraryRoot(archiveRoot.absolutePath, "测试库A", source))

        val archived = File(File(archiveRoot, "测试库A"), "done.mp4")
        assertTrue(archived.isFile)
        assertEquals("payload", archived.readText())
        assertFalse(source.exists())
    }

    @Test
    fun `库名含非法字符被替换为下划线`() {
        val inbox = tmp.newFolder("inbox3")
        val source = write(inbox, "pic.jpg")

        assertTrue(store.archiveToLibraryRoot(tmp.root.absolutePath, "a/b\\c:d*e?f\"g<h>i|j", source))

        // 全部 Windows 保留字符 + 路径分隔符统一替换为 _，不产生嵌套目录
        assertTrue(File(tmp.root, "a_b_c_d_e_f_g_h_i_j").isDirectory)
        assertTrue(File(File(tmp.root, "a_b_c_d_e_f_g_h_i_j"), "pic.jpg").isFile)
    }

    @Test
    fun `目标同名且内容不同时加序号不覆盖既有文件`() {
        val libDir = tmp.newFolder("archive-lib")
        // 既有归档文件（用户已手动整理过）：同名但内容不同
        write(libDir, "done.jpg", content = "old-archived")
        val source = write(tmp.newFolder("inbox4"), "done.jpg", content = "new-upload")

        assertTrue(store.archiveToLibraryRoot(tmp.root.absolutePath, "archive-lib", source))

        // 既有文件原样保留，新文件落到 (1) 序号位
        assertEquals("old-archived", File(libDir, "done.jpg").readText())
        assertEquals("new-upload", File(libDir, "done (1).jpg").readText())
        assertFalse(source.exists())
    }

    @Test
    fun `目标同名且内容相同时删旧放新结果与源一致`() {
        val libDir = tmp.newFolder("archive-lib2")
        write(libDir, "dup.jpg", content = "same")
        val source = write(tmp.newFolder("inbox5"), "dup.jpg", content = "same")

        assertTrue(store.archiveToLibraryRoot(tmp.root.absolutePath, "archive-lib2", source))

        // 同内容重复上传：目标内容一致（源文件本体落位），原位置清空
        assertEquals("same", File(libDir, "dup.jpg").readText())
        assertFalse(source.exists())
    }

    @Test
    fun `库名空白返回false且源文件不动`() {
        val source = write(tmp.newFolder("inbox6"), "keep.jpg")

        assertFalse(store.archiveToLibraryRoot(tmp.root.absolutePath, "   ", source))
        assertFalse(store.archiveToLibraryRoot(tmp.root.absolutePath, "", source))
        // 归档失败不吞源文件（调用方回退 uploaded/ 归档的兜底前提）
        assertTrue(source.isFile)
    }

    @Test
    fun `源文件不存在返回false`() {
        assertFalse(
            store.archiveToLibraryRoot(
                tmp.root.absolutePath,
                "任意库",
                tmp.root.resolve("不存在.jpg"),
            ),
        )
    }

    @Test
    fun `copy兜底落位后不残留part文件`() {
        val libDir = tmp.newFolder("archive-copy")
        val source = write(tmp.newFolder("inbox-copy"), "big.mp4", content = "payload")

        // 强制走 copy 兜底：renameTo 需要对源文件的删除访问权，Windows JVM 上源被打开的
        // 流持有时（句柄不带 FILE_SHARE_DELETE）rename/delete 均失败——锁住源即锁死直改
        // 路径。POSIX 上 rename 不受打开句柄影响会直改成功，此时本测试退化为对
        // 「无 .part 残留」不变式的验证（copy 路径在 Windows 开发/CI 机被覆盖）。
        FileInputStream(source).use { locked ->
            locked.read() // 建立真实句柄，防惰性打开差异

            val result = store.archiveToLibraryRoot(tmp.root.absolutePath, "archive-copy", source)

            // 无论直改还是兜底：目标必须就位且内容与源一致
            val archived = File(libDir, "big.mp4")
            assertTrue(archived.isFile)
            assertEquals("payload", archived.readText())
            // .part 半截文件不得残留（copy 兜底 finally 清理口径）
            assertTrue(
                libDir.listFiles().orEmpty().none { it.name.endsWith(".part") },
            )
            // 删源失败按失败上报的有意语义：false 时源仍在（不吞文件）、true 时源已移走
            if (result) assertFalse(source.exists()) else assertTrue(source.exists())
        }
    }

    /** 写入测试文件（内容默认一行，返回文件引用） */
    private fun write(dir: File, name: String, content: String = "x"): File =
        File(dir, name).apply { writeText(content) }
}
