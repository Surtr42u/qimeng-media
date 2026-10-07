package media.qimeng.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 上传纯规则锁定（M4-5）：超限判定 / 目录名合法性 / 路径拼装。 */
class UploadModelsTest {

    // ---- UploadLimits.overLimit ----

    @Test
    fun `超过上限判真`() {
        val limits = UploadLimits(maxBytesMb = 64, autoAccept = true)
        assertTrue(limits.overLimit(64L * 1024 * 1024 + 1))
    }

    @Test
    fun `恰好等于上限不拦`() {
        val limits = UploadLimits(maxBytesMb = 64, autoAccept = true)
        assertFalse(limits.overLimit(64L * 1024 * 1024))
    }

    @Test
    fun `未知大小不拦交给服务端兜底`() {
        val limits = UploadLimits(maxBytesMb = 64, autoAccept = true)
        assertFalse(limits.overLimit(-1))
    }

    // ---- UploadItem.effectiveUploadName（挂靠批：编辑后落库名回退口径） ----

    @Test
    fun `未编辑回退展示名`() {
        val item = UploadItem(uri = "u", displayName = "IMG_1.jpg", sizeBytes = 1)
        assertNull(item.uploadFileName)
        assertEquals("IMG_1.jpg", item.effectiveUploadName)
    }

    @Test
    fun `编辑名trim后非空即生效`() {
        val item = UploadItem(
            uri = "u",
            displayName = "IMG_1.jpg",
            sizeBytes = 1,
            uploadFileName = "  作品名.jpg ",
        )
        assertEquals("作品名.jpg", item.effectiveUploadName)
    }

    @Test
    fun `编辑名空白回退展示名`() {
        val item = UploadItem(
            uri = "u",
            displayName = "IMG_1.jpg",
            sizeBytes = 1,
            uploadFileName = "   ",
        )
        assertEquals("IMG_1.jpg", item.effectiveUploadName)
    }

    @Test
    fun `effectiveUploadName清洗路径与非法字符`() {
        val item = UploadItem(
            uri = "u",
            displayName = "/storage/emulated/0/DCIM/Camera/IMG:2026*1.jpg",
            sizeBytes = 1,
        )
        assertEquals("IMG20261.jpg", item.effectiveUploadName)
    }

    @Test
    fun `effectiveUploadName保护Windows保留设备名`() {
        val item = UploadItem(
            uri = "u",
            displayName = "CON.jpg",
            sizeBytes = 1,
        )
        assertEquals("file_CON.jpg", item.effectiveUploadName)
    }

    @Test
    fun `effectiveUploadName空白与全非法名安全兜底`() {
        val item = UploadItem(
            uri = "u",
            displayName = ":*?<>|",
            sizeBytes = 1,
        )
        assertEquals("upload", item.effectiveUploadName)
    }

    // ---- UploadRules.sanitizeFileName ----

    @Test
    fun `文件名清洗正常保留`() {
        assertEquals("photo_1.jpg", UploadRules.sanitizeFileName("photo_1.jpg"))
        assertEquals("中文_2026.png", UploadRules.sanitizeFileName("中文_2026.png"))
    }

    @Test
    fun `文件名清洗剥离路径与反斜杠`() {
        assertEquals("IMG_1.jpg", UploadRules.sanitizeFileName("/sdcard/DCIM/IMG_1.jpg"))
        assertEquals("video.mp4", UploadRules.sanitizeFileName("C:\\Users\\Camera\\video.mp4"))
    }

    @Test
    fun `文件名清洗剥离控制字符与Windows非法字符`() {
        assertEquals("testfile.jpg", UploadRules.sanitizeFileName("test:?<file>|\"\u0001.jpg"))
    }

    @Test
    fun `文件名清洗首尾空格与点修剪`() {
        assertEquals("test.jpg", UploadRules.sanitizeFileName("  test.jpg.  "))
    }

    @Test
    fun `文件名清洗无扩展名时自动应用兜底扩展名`() {
        assertEquals("video.mp4", UploadRules.sanitizeFileName("video", fallbackExtension = "mp4"))
        assertEquals("photo.jpg", UploadRules.sanitizeFileName("photo", fallbackExtension = ".jpg"))
    }

    @Test
    fun `文件名清洗点文件保留为合法名`() {
        assertEquals("upload.jpg", UploadRules.sanitizeFileName(".jpg"))
        assertEquals("upload.nomedia", UploadRules.sanitizeFileName(".nomedia"))
    }

    @Test
    fun `文件名清洗Windows保留名加前缀保护`() {
        assertEquals("file_CON.jpg", UploadRules.sanitizeFileName("CON.jpg"))
        assertEquals("file_prn.png", UploadRules.sanitizeFileName("prn.png"))
        assertEquals("file_AUX.mp4", UploadRules.sanitizeFileName("AUX.mp4"))
        assertEquals("file_NUL", UploadRules.sanitizeFileName("NUL"))
        assertEquals("file_COM1.jpg", UploadRules.sanitizeFileName("COM1.jpg"))
        assertEquals("file_lpt9.extra.jpg", UploadRules.sanitizeFileName("lpt9.extra.jpg"))
        // 非保留名前缀正常放行
        assertEquals("conference.jpg", UploadRules.sanitizeFileName("conference.jpg"))
    }

    @Test
    fun `文件名清洗全非法字符与空白走兜底基名与后缀`() {
        assertEquals("upload.jpg", UploadRules.sanitizeFileName("", fallbackExtension = "jpg"))
        assertEquals("upload.jpg", UploadRules.sanitizeFileName("   ", fallbackExtension = "jpg"))
        assertEquals("upload.png", UploadRules.sanitizeFileName(":*?<>|", fallbackExtension = "png"))
        assertEquals("custom_base.jpg", UploadRules.sanitizeFileName("   ", fallbackExtension = "jpg", fallbackBaseName = "custom_base"))
    }

    // ---- UploadRules.isValidDirName ----

    @Test
    fun `目录名合法形态`() {
        assertTrue(UploadRules.isValidDirName("cos2026"))
        assertTrue(UploadRules.isValidDirName(" 带空格目录 "))
    }

    @Test
    fun `目录名拒绝点段与分隔符`() {
        assertFalse(UploadRules.isValidDirName(""))
        assertFalse(UploadRules.isValidDirName("   "))
        assertFalse(UploadRules.isValidDirName("."))
        assertFalse(UploadRules.isValidDirName(".."))
        assertFalse(UploadRules.isValidDirName("a/b"))
        assertFalse(UploadRules.isValidDirName("a\\b"))
    }

    // ---- UploadRules.joinDirPath ----

    @Test
    fun `库根下直接拼名`() {
        assertEquals("sub", UploadRules.joinDirPath("", "sub"))
    }

    @Test
    fun `嵌套目录斜杠拼接且无重复斜杠`() {
        assertEquals("a/b/c", UploadRules.joinDirPath("a/b", "c"))
    }

    @Test
    fun `选中目录带尾斜杠时容错`() {
        assertEquals("a/c", UploadRules.joinDirPath("a/", "c"))
    }

    @Test
    fun `非法名返回null`() {
        assertNull(UploadRules.joinDirPath("a", ".."))
        assertNull(UploadRules.joinDirPath("a", "x/y"))
    }

    // ---- UploadRules.joinUploadDirPath（U10-6c：选文件夹上传的 per-item dir 拼装）----

    @Test
    fun `基目录为空时相对目录即整段`() {
        assertEquals("作者A/子", UploadRules.joinUploadDirPath("", "作者A/子"))
    }

    @Test
    fun `相对目录为空时保持基目录`() {
        assertEquals("photos", UploadRules.joinUploadDirPath("photos", ""))
    }

    @Test
    fun `基目录与相对目录斜杠拼接`() {
        assertEquals("photos/作者A/子", UploadRules.joinUploadDirPath("photos", "作者A/子"))
    }

    @Test
    fun `反斜杠归一为斜杠且首尾斜杠容错`() {
        assertEquals("photos/作者A/子", UploadRules.joinUploadDirPath("/photos/", "作者A\\子"))
    }

    @Test
    fun `两者皆空返回空串`() {
        assertEquals("", UploadRules.joinUploadDirPath("", ""))
    }

    // ---- UploadRules.shouldExpandOnSelect（V7：点目录行 = 选中并进入）----

    @Test
    fun `有子级且未展开时点行同时展开`() {
        assertTrue(UploadRules.shouldExpandOnSelect(hasChildren = true, alreadyExpanded = false))
    }

    @Test
    fun `已展开的点行不再触发展开`() {
        assertFalse(UploadRules.shouldExpandOnSelect(hasChildren = true, alreadyExpanded = true))
    }

    @Test
    fun `叶子目录点行不展开`() {
        assertFalse(UploadRules.shouldExpandOnSelect(hasChildren = false, alreadyExpanded = false))
    }

    // ---- UploadRules.isAbsoluteFilePath（源标识判定：路径类 vs content uri 类）----

    @Test
    fun `源标识路径类判定`() {
        assertTrue(UploadRules.isAbsoluteFilePath("/storage/emulated/0/.dl/a.jpg"))
        assertTrue(UploadRules.isAbsoluteFilePath("/tmp/x"))
        assertFalse(UploadRules.isAbsoluteFilePath("content://media/external/images/1"))
        assertFalse(UploadRules.isAbsoluteFilePath(""))
    }
}
