package media.qimeng.app.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import media.qimeng.app.core.model.StagingBatchConfig

/**
 * 批次配置持久化编码锁定（2026-09-29 直传化收窄后仅余批次配置）：moshi JSON 往返
 * （杀进程重启读回一致）与损坏数据容错（宁默认不崩）。
 */
class StagingJsonTest {

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

    // ---- 损坏/缺键容错：持久层宁可默认不可崩 ----

    @Test
    fun `缺键与空串回退默认批次`() {
        assertEquals(StagingBatchConfig(), StagingJson.batchFromJson(null))
        assertEquals(StagingBatchConfig(), StagingJson.batchFromJson(""))
        assertEquals(StagingBatchConfig(), StagingJson.batchFromJson("  "))
    }

    @Test
    fun `损坏JSON回退默认批次不抛错`() {
        assertEquals(StagingBatchConfig(), StagingJson.batchFromJson("[not-object]"))
        assertEquals(StagingBatchConfig(), StagingJson.batchFromJson("nonsense"))
    }
}
