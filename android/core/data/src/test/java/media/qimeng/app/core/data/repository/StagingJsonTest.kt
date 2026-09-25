package media.qimeng.app.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import media.qimeng.app.core.model.StagedUpload
import media.qimeng.app.core.model.StagingBatchConfig

/**
 * 暂存区持久化编码锁定（2026-09-25 暂存区重做）：moshi JSON 往返（杀进程重启读回一致）
 * 与损坏数据容错（宁空不崩）。
 */
class StagingJsonTest {

    private val item = StagedUpload(
        source = "/storage/emulated/0/.dl/守望先锋dva.jpg",
        isPathSource = true,
        displayName = "守望先锋dva.jpg",
        sizeBytes = 12345L,
        isVideo = false,
        uploadBaseName = "守望先锋 DVA 13",
        attachAuthorId = "author-1",
        attachAuthorName = "作者A",
        attachSources = listOf("kemono", "fanbox"),
        libraryIdOverride = "lib-2",
        addedAtMs = 1_730_000_000_000L,
    )

    @Test
    fun `暂存条目JSON往返字段无损`() {
        val json = StagingJson.itemsToJson(listOf(item))
        assertEquals(listOf(item), StagingJson.itemsFromJson(json))
    }

    @Test
    fun `暂存条目缺省可空字段往返`() {
        val minimal = item.copy(
            uploadBaseName = null,
            attachAuthorId = null,
            attachAuthorName = null,
            attachSources = null,
            libraryIdOverride = null,
        )
        assertEquals(listOf(minimal), StagingJson.itemsFromJson(StagingJson.itemsToJson(listOf(minimal))))
    }

    @Test
    fun `批次配置JSON往返`() {
        val config = StagingBatchConfig(
            libraryId = "lib-1",
            authorId = "author-1",
            authorName = "作者A",
            sources = listOf("kemono"),
        )
        assertEquals(config, StagingJson.batchFromJson(StagingJson.batchToJson(config)))
    }

    @Test
    fun `批次配置空值往返`() {
        assertEquals(StagingBatchConfig(), StagingJson.batchFromJson(StagingJson.batchToJson(StagingBatchConfig())))
    }

    // ---- 损坏/缺键容错：持久层宁可空不可崩 ----

    @Test
    fun `缺键与空串回退空列表与默认批次`() {
        assertEquals(emptyList<StagedUpload>(), StagingJson.itemsFromJson(null))
        assertEquals(emptyList<StagedUpload>(), StagingJson.itemsFromJson(""))
        assertEquals(emptyList<StagedUpload>(), StagingJson.itemsFromJson("  "))
        assertEquals(StagingBatchConfig(), StagingJson.batchFromJson(null))
        assertEquals(StagingBatchConfig(), StagingJson.batchFromJson(""))
    }

    @Test
    fun `损坏JSON回退空列表与默认批次不抛错`() {
        assertTrue(StagingJson.itemsFromJson("{not-json").isEmpty())
        assertTrue(StagingJson.itemsFromJson("42").isEmpty())
        assertEquals(StagingBatchConfig(), StagingJson.batchFromJson("[not-object]"))
        assertEquals(StagingBatchConfig(), StagingJson.batchFromJson("nonsense"))
    }
}
