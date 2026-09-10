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
}
