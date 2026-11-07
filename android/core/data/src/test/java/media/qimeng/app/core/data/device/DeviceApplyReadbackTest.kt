package media.qimeng.app.core.data.device

import media.qimeng.app.core.model.LegacyImportSummary
import media.qimeng.app.core.model.TxtImportSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceApplyReadbackTest {

    @Test
    fun `作者TXT导入结果映射 authors 与 assetAuthorRefs`() {
        val values = DeviceApplyReadback.fromMasterTxt(
            TxtImportSummary(authorsImported = 3, filesMatched = 4),
        )
        assertEquals(3, values["authors"])
        assertEquals(3, values["authorsImported"])
        assertEquals(4, values["assetAuthorRefs"])
        assertEquals(4, values["filesMatched"])
    }

    @Test
    fun `v2备份导入结果映射事件作者关联标签与文件计数`() {
        val values = DeviceApplyReadback.fromBackup(
            LegacyImportSummary(
                mediaFilesTotal = 100,
                assetsMatched = 90,
                authorsImported = 8,
                authorRefsImported = 120,
                tagsImported = 7,
                eventsReplayed = 10,
            ),
        )
        assertEquals(10, values["viewEvents"])
        assertEquals(10, values["eventsReplayed"])
        assertEquals(8, values["authors"])
        assertEquals(120, values["assetAuthorRefs"])
        assertEquals(7, values["tags"])
        assertEquals(100, values["mediaFilesTotal"])
        assertEquals(90, values["assetsMatched"])
    }

    @Test
    fun `回读比较接受 JSON 的浮点数字与本地整数`() {
        val comparison = DeviceApplyReadback.compare(
            expected = mapOf("authors" to 3.0, "viewEvents" to 10.0),
            actual = mapOf("authors" to 3, "viewEvents" to 10L),
        )
        assertTrue(comparison.matched)
        assertTrue(comparison.missingKeys.isEmpty())
    }

    @Test
    fun `回读比较分别报告缺失键与值不符`() {
        val comparison = DeviceApplyReadback.compare(
            expected = mapOf("authors" to 3, "assetAuthorRefs" to 4, "tags" to 7),
            actual = mapOf("authors" to 3, "assetAuthorRefs" to 5),
        )
        assertFalse(comparison.matched)
        assertEquals(listOf("tags"), comparison.missingKeys)
        assertEquals(listOf("assetAuthorRefs"), comparison.mismatchedKeys)
    }

    @Test
    fun `未声明 expectedReadback 视为匹配`() {
        assertTrue(DeviceApplyReadback.compare(null, emptyMap()).matched)
        assertTrue(DeviceApplyReadback.compare(emptyMap(), mapOf("authors" to 1)).matched)
    }

    @Test
    fun `嵌套对象与数组递归比较`() {
        val expected = mapOf("detail" to mapOf("keys" to listOf(1.0, 2.0)))
        val equal = mapOf<String, Any>("detail" to mapOf("keys" to listOf(1, 2L)))
        val different = mapOf<String, Any>("detail" to mapOf("keys" to listOf(1, 3)))
        assertTrue(DeviceApplyReadback.compare(expected, equal).matched)
        assertFalse(DeviceApplyReadback.compare(expected, different).matched)
    }

    @Test
    fun `单段回传平铺 双段按 kind 嵌套`() {
        val master = SegmentReadback(DeviceSegmentKind.MASTER_TXT, mapOf("authors" to 3))
        assertEquals(mapOf("authors" to 3), DeviceApplyReadback.merge(listOf(master)))
        val both = DeviceApplyReadback.merge(
            listOf(
                master,
                SegmentReadback(DeviceSegmentKind.V2_BACKUP, mapOf("viewEvents" to 10)),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val masterNested = both["masterTxt"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val backupNested = both["v2Backup"] as Map<String, Any>
        assertEquals(3, masterNested["authors"])
        assertEquals(10, backupNested["viewEvents"])

        val onlyMaster = DeviceApplyReadback.merge(listOf(master), nestWhenMultiple = true)
        assertTrue(onlyMaster.containsKey("masterTxt"))
    }
}
