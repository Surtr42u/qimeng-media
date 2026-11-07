package media.qimeng.app.core.data.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceApplyResultPlannerTest {

    private val master = SegmentApplyResult(DeviceSegmentKind.MASTER_TXT, applied = true)
    private val backup = SegmentApplyResult(DeviceSegmentKind.V2_BACKUP, applied = true)
    private val masterFailed = SegmentApplyResult(
        DeviceSegmentKind.MASTER_TXT,
        applied = false,
        message = "作者 TXT 与上传写入条目冲突",
    )

    private fun matched(kind: DeviceSegmentKind = DeviceSegmentKind.MASTER_TXT) =
        SegmentReadbackComparison(kind, ReadbackComparison(matched = true))

    private fun mismatch(kind: DeviceSegmentKind = DeviceSegmentKind.V2_BACKUP) =
        SegmentReadbackComparison(
            kind,
            ReadbackComparison(
                matched = false,
                missingKeys = listOf("viewEvents"),
                mismatchedKeys = listOf("authors"),
            ),
        )

    @Test
    fun `授权缺失或没有任何段应用成功时为 failed`() {
        assertEquals(
            DeviceResultStatus.FAILED,
            DeviceApplyResultPlanner.resolve(
                authorized = false,
                segmentResults = listOf(master),
                readbackMatched = true,
                localWriteOk = true,
            ),
        )
        assertEquals(
            DeviceResultStatus.FAILED,
            DeviceApplyResultPlanner.resolve(true, emptyList(), true, true),
        )
        assertEquals(
            DeviceResultStatus.FAILED,
            DeviceApplyResultPlanner.resolve(true, listOf(masterFailed), true, true),
        )
    }

    @Test
    fun `全部段成功且回读与两代备份都成功才 applied`() {
        assertEquals(
            DeviceResultStatus.APPLIED,
            DeviceApplyResultPlanner.resolve(true, listOf(master, backup), true, true),
        )
    }

    @Test
    fun `部分段失败或回读不符或本机回写失败为 partial`() {
        assertEquals(
            DeviceResultStatus.PARTIAL,
            DeviceApplyResultPlanner.resolve(true, listOf(master, masterFailed.copy(kind = DeviceSegmentKind.V2_BACKUP)), true, true),
        )
        assertEquals(
            DeviceResultStatus.PARTIAL,
            DeviceApplyResultPlanner.resolve(true, listOf(master, backup), false, true),
        )
        assertEquals(
            DeviceResultStatus.PARTIAL,
            DeviceApplyResultPlanner.resolve(true, listOf(master, backup), true, false),
        )
    }

    @Test
    fun `回传消息包含失败段与回读键且可被 2000 字符截断`() {
        val message = DeviceApplyResultPlanner.message(
            status = DeviceResultStatus.PARTIAL,
            segmentResults = listOf(master, masterFailed.copy(kind = DeviceSegmentKind.V2_BACKUP)),
            readback = listOf(matched(), mismatch()),
            localWriteOk = false,
        )
        assertTrue(message.contains("v2Backup"))
        assertTrue(message.contains("作者 TXT"))
        assertTrue(message.contains("viewEvents"))
        assertTrue(message.contains("authors"))
        assertTrue(message.contains("本机两代备份"))
        assertTrue(message.codePointCount(0, message.length) <= DeviceApplyMessages.MAX_MESSAGE_CHARS)
    }

    @Test
    fun `超长消息按 code point 截断不劈开代理对`() {
        val long = "结".repeat(DeviceApplyMessages.MAX_MESSAGE_CHARS + 50)
        val bounded = DeviceApplyMessages.truncate(long)
        assertEquals(DeviceApplyMessages.MAX_MESSAGE_CHARS, bounded.codePointCount(0, bounded.length))
        assertTrue(bounded.endsWith("…"))

        val emoji = "😀".repeat(DeviceApplyMessages.MAX_MESSAGE_CHARS + 1)
        val boundedEmoji = DeviceApplyMessages.truncate(emoji)
        assertEquals(DeviceApplyMessages.MAX_MESSAGE_CHARS, boundedEmoji.codePointCount(0, boundedEmoji.length))
        assertFalse(boundedEmoji.contains("\uFFFD"))
    }
}
