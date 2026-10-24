package media.qimeng.app.core.data.upload

import java.io.File
import java.nio.file.Files
import media.qimeng.app.core.data.repository.InboxFileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveUploadCleanupTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cleanup = ArchiveUploadCleanup(InboxFileStore())

    private fun spec(file: File) = UploadWorkSpec.UploadRequestSpec(
        localId = "synthetic-upload", uri = file.absolutePath, libraryId = "synthetic-library",
        dir = "", displayName = file.name, sizeBytes = file.length(), uploadFileName = file.name,
        alreadyArchived = true, targetServerUrl = "http://192.0.2.10:8420", sourceMtime = file.lastModified(),
    )

    private fun source(root: File) = File(root, "Library/nested/photo.jpg").apply {
        parentFile!!.mkdirs()
        writeText("synthetic media")
    }

    @Test fun `远端确认成功后只释放归档副本并允许幂等清理`() {
        val root = tmp.newFolder()
        val file = source(root)
        val request = spec(file)
        val sibling = File(file.parentFile, "other.jpg").apply { writeText("keep") }
        assertEquals("", cleanup.finish(request, root.path, confirmed = true))
        assertFalse(file.exists())
        assertTrue(sibling.exists())
        assertEquals("", cleanup.finish(request, root.path, confirmed = true))
    }

    @Test fun `本机上传和未确认入库保留归档`() {
        val root = tmp.newFolder()
        val file = source(root)
        for (url in listOf("http://127.0.0.1:18430", "http://localhost:18430", "http://[::1]:8420", "http://127.0.0.2:9000")) {
            assertEquals("", cleanup.finish(spec(file).copy(targetServerUrl = url), root.path, true))
            assertTrue(file.exists())
        }
        assertEquals(ArchiveUploadCleanup.FAILED_NOTE, cleanup.finish(spec(file), root.path, false))
        assertTrue(file.exists())
        assertEquals("", cleanup.finish(spec(file).copy(alreadyArchived = false), root.path, true))
        assertTrue(file.exists())
    }

    @Test fun `文件变化和旧载荷缺时间都保源且不删除目录`() {
        val root = tmp.newFolder()
        val file = source(root)
        val request = spec(file)
        val changes = listOf(
            request.copy(sizeBytes = request.sizeBytes + 1),
            request.copy(sourceMtime = request.sourceMtime!! + 1),
            request.copy(sourceMtime = null),
            request.copy(uri = file.parentFile!!.path),
        )
        for (change in changes) {
            assertEquals(ArchiveUploadCleanup.FAILED_NOTE, cleanup.finish(change, root.path, true))
            assertTrue(file.exists())
        }
    }

    @Test fun `归档根变化或根外文件与根下散文件拒绝清理`() {
        val root = tmp.newFolder()
        val file = source(root)
        val loose = File(root, "loose.jpg").apply { writeText("keep") }
        assertEquals(ArchiveUploadCleanup.FAILED_NOTE, cleanup.finish(spec(file), tmp.newFolder().path, true))
        assertEquals(ArchiveUploadCleanup.FAILED_NOTE, cleanup.finish(spec(loose), root.path, true))
        assertEquals(ArchiveUploadCleanup.FAILED_NOTE, cleanup.finish(spec(file), null, true))
        assertTrue(file.exists())
        assertTrue(loose.exists())
    }

    @Test fun `拒绝库目录与文件软链和路径穿越但允许根本身的系统别名`() {
        val root = tmp.newFolder()
        val file = source(root)
        val link = File(root, "Library/alias.jpg")
        Files.createSymbolicLink(link.toPath(), file.toPath())
        assertEquals(ArchiveUploadCleanup.FAILED_NOTE, cleanup.finish(spec(link), root.path, true))
        val dirLink = File(root, "Alias")
        Files.createSymbolicLink(dirLink.toPath(), file.parentFile!!.toPath())
        assertEquals(ArchiveUploadCleanup.FAILED_NOTE,
            cleanup.finish(spec(File(dirLink, file.name)), root.path, true))
        val traversal = File(root, "Library/nested/../nested/${file.name}")
        assertEquals(ArchiveUploadCleanup.FAILED_NOTE, cleanup.finish(spec(traversal), root.path, true))
        assertTrue(file.exists())
        val rootAlias = File(tmp.root, "root-alias")
        Files.createSymbolicLink(rootAlias.toPath(), root.toPath())
        val aliasedFile = File(rootAlias, "Library/nested/${file.name}")
        assertEquals("", cleanup.finish(spec(aliasedFile), rootAlias.path, true))
        assertFalse(file.exists())
    }
}
