package media.qimeng.app.core.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份文件前置校验锁定（U10-6b）：超限/坏 JSON/格式不符/摘要计数。判定顺序与文案
 * 逐字对齐 web/src/lib/backup.ts parseLegacyBackupFile（含 64MB 双写常量）。
 * 2026-10-03 随校验器自 feature:manage 整体搬入本包（撤 :sdk 直依赖批；逻辑零变化，
 * 载荷 format 断言改经 internal sdkPayload——测试同模块可见）。
 */
class BackupValidatorTest {

    /** 最小合法信封：各数组元素只带其强类型模型的必填字段（ Legacy* 生成物口径） */
    private val validEnvelopeJson = """
        {
          "format": "qimeng_backup",
          "schemaVersion": 1,
          "appIdentifier": "com.qimeng.media",
          "data": {
            "mediaFiles": [
              {"recordKey": "r1", "fileName": "a.jpg", "mediaType": "image", "sizeBytes": 1, "modifiedAtMillis": 1},
              {"recordKey": "r2", "fileName": "b.mp4", "mediaType": "video", "sizeBytes": 2, "modifiedAtMillis": 2}
            ],
            "authors": [
              {"authorId": "A1", "displayName": "作者一"},
              {"authorId": "A2", "displayName": "作者二"},
              {"authorId": "A3", "displayName": "作者三"}
            ],
            "tags": [{"name": "标签一"}],
            "mediaStats": [
              {"recordKey": "r1", "fileName": "a.jpg"},
              {"recordKey": "r2", "fileName": "b.mp4"},
              {"recordKey": "r3", "fileName": "c.jpg"},
              {"recordKey": "r4", "fileName": "d.jpg"},
              {"recordKey": "r5", "fileName": "e.jpg"}
            ],
            "dailyBrowse": [
              {"recordKey": "r1", "fileName": "a.jpg", "mediaType": "image", "dayStartMillis": 0},
              {"recordKey": "r2", "fileName": "b.mp4", "mediaType": "video", "dayStartMillis": 0},
              {"recordKey": "r3", "fileName": "c.jpg", "mediaType": "image", "dayStartMillis": 0},
              {"recordKey": "r4", "fileName": "d.jpg", "mediaType": "image", "dayStartMillis": 0},
              {"recordKey": "r5", "fileName": "e.jpg", "mediaType": "image", "dayStartMillis": 0},
              {"recordKey": "r6", "fileName": "f.jpg", "mediaType": "image", "dayStartMillis": 0},
              {"recordKey": "r7", "fileName": "g.jpg", "mediaType": "image", "dayStartMillis": 0}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `超限前置拦截不出解析`() {
        val bytes = ByteArray(BackupValidator.BACKUP_MAX_BYTES + 1)
        val result = BackupValidator.validate("qimeng_backup.json", bytes)
        // Web backup.ts L32 逐字（toFixed(0) → 整数 64）
        assertEquals("备份文件超过 64MB 上限，请确认导出的是完整备份", (result as BackupValidator.Result.Invalid).message)
    }

    @Test
    fun `恰好上限的空体可通过大小闸`() {
        // 边界口径：> MAX 才拦（Web if (file.size > BACKUP_MAX_BYTES) 同为开区间）
        val bytes = ByteArray(BackupValidator.BACKUP_MAX_BYTES)
        val result = BackupValidator.validate("qimeng_backup.json", bytes)
        // 全零字节不是合法 JSON → 走坏 JSON 文案（证明大小闸已放行）
        assertEquals(BackupValidator.MESSAGE_BAD_JSON, (result as BackupValidator.Result.Invalid).message)
    }

    @Test
    fun `坏JSON报非法文件文案`() {
        val result = BackupValidator.validate("qimeng_backup.json", "这不是json".toByteArray())
        assertEquals(BackupValidator.MESSAGE_BAD_JSON, (result as BackupValidator.Result.Invalid).message)
    }

    @Test
    fun `format不符报格式文案`() {
        val result = BackupValidator.validate(
            "qimeng_backup.json",
            """{"format": "not_backup", "data": {}}""".toByteArray(),
        )
        assertEquals(
            BackupValidator.MESSAGE_FORMAT_MISMATCH,
            (result as BackupValidator.Result.Invalid).message,
        )
    }

    @Test
    fun `缺data段同报格式文案`() {
        // Web 判定：format !== 'qimeng_backup' || !envelope.data → 同一条格式文案
        val result = BackupValidator.validate("qimeng_backup.json", """{"format": "qimeng_backup"}""".toByteArray())
        assertEquals(
            BackupValidator.MESSAGE_FORMAT_MISMATCH,
            (result as BackupValidator.Result.Invalid).message,
        )
    }

    @Test
    fun `非对象JSON同报格式文案`() {
        // JSON.parse 合法的标量/数组过不了信封闸
        listOf("""123""", """["x"]""").forEach { json ->
            val result = BackupValidator.validate("qimeng_backup.json", json.toByteArray())
            assertEquals(
                BackupValidator.MESSAGE_FORMAT_MISMATCH,
                (result as BackupValidator.Result.Invalid).message,
            )
        }
    }

    @Test
    fun `合法信封返回载荷与摘要计数`() {
        val result = BackupValidator.validate("qimeng_backup.json", validEnvelopeJson.toByteArray())
        result is BackupValidator.Result.Ok || throw AssertionError("合法信封应通过校验：$result")
        val ok = result as BackupValidator.Result.Ok
        // 载荷原样透传（供 POST /import/qimeng-backup；SDK 模型字段经 internal 句柄取）
        assertEquals("qimeng_backup", ok.payload.sdkPayload.format)
        // 摘要计数 = 备份内原始条数；统计条 = mediaStats + dailyBrowse（Web 同口径）
        val summary = ok.summary
        assertEquals("qimeng_backup.json", summary.fileName)
        assertEquals(2, summary.mediaFiles)
        assertEquals(3, summary.authors)
        assertEquals(1, summary.tags)
        assertEquals(12, summary.statsRows)
    }

    @Test
    fun `确认摘要文案与Web逐字`() {
        val summary = BackupValidator.BackupSummary(
            fileName = "qimeng_backup.json",
            mediaFiles = 2,
            authors = 3,
            tags = 1,
            statsRows = 12,
        )
        // Web backupSummaryText 逐字（toLocaleString 小数值无千分位分隔）
        assertEquals(
            "检测到备份数据：2 个媒体文件 / 3 位作者 / 1 个标签 / 12 条统计。" +
                "导入按唯一键合并、不删除现有数据，是否导入恢复？",
            BackupValidator.summaryText(summary),
        )
        // 千分位：%,d 与 toLocaleString 同语义
        val large = summary.copy(mediaFiles = 1234)
        assertTrue(BackupValidator.summaryText(large).contains("1,234 个媒体文件"))
    }

    @Test
    fun `数组缺项摘要计零`() {
        val json = """
            {"format": "qimeng_backup", "schemaVersion": 1, "appIdentifier": "com.qimeng.media", "data": {}}
        """.trimIndent()
        val result = BackupValidator.validate("qimeng_backup.json", json.toByteArray())
        val ok = result as BackupValidator.Result.Ok
        assertEquals(0, ok.summary.mediaFiles)
        assertEquals(0, ok.summary.statsRows)
    }
}
