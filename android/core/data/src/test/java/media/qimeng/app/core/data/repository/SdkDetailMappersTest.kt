package media.qimeng.app.core.data.repository

import java.time.OffsetDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import media.qimeng.app.core.model.MediaKind
import media.qimeng.sdk.models.AssetDetail
import media.qimeng.sdk.models.Author
import media.qimeng.sdk.models.LikeState
import media.qimeng.sdk.models.MediaType
import media.qimeng.sdk.models.Tag

/**
 * 详情映射单测（M4-3 3a）：锁定 title=cosWork??fileName、签名相对路径 absolutize、
 * BigDecimal→Double、Author.Type.cos→isCos、LikeState 空值兜底。
 * （SdkDetailMappers internal——同模块测试可见；「映射正确」由本测试锁定，
 * DetailViewModelTest 的 fake 直接给领域对象，不重复覆盖 mapper。）
 */
class SdkDetailMappersTest {

    private val baseUrl = "http://192.0.2.10:8420"

    /** 幂等键入参（任务L L5）：本组只验映射透传，值本身任意合法 UUID */
    private val CLIENT_EVENT_ID = "00000000-0000-0000-0000-00000000cba1"

    private fun sdkDetail(
        id: String = "11111111-1111-1111-1111-111111111111",
        fileName: String = "IMG_001.jpg",
        cosWork: String? = null,
        mediaType: MediaType = MediaType.image,
        thumbUrl: String? = "/media/thumb/abc?size=lg&sig=x",
        origUrl: String? = "/media/orig/abc?exp=1&sig=y",
        lastPositionSeconds: Double? = null,
        likedToday: Boolean? = null,
        likeCount: Int? = null,
        tags: List<Tag> = emptyList(),
        authors: List<Author> = emptyList(),
        modifiedAt: OffsetDateTime? = null,
    ): AssetDetail = AssetDetail(
        id = UUID.fromString(id),
        fileName = fileName,
        mediaType = mediaType,
        sizeBytes = 2048L,
        modifiedAt = modifiedAt,
        source = "同人",
        isFavorite = true,
        likeCount = likeCount,
        likedToday = likedToday,
        thumbUrl = thumbUrl,
        durationMs = null,
        cosWork = cosWork,
        lastPositionSeconds = lastPositionSeconds,
        viewCount = 7,
        playCount = 3,
        width = 1920,
        height = 1080,
        tags = tags,
        authors = authors,
        origUrl = origUrl,
    )

    @Test
    fun `标题取cosWork优先 - 相对路径absolutize成绝对直链`() {
        val domain = SdkDetailMappers.toAssetDetail(sdkDetail(cosWork = "作品A"), baseUrl)
        assertEquals("作品A", domain.title)
        assertEquals("http://192.0.2.10:8420/media/thumb/abc?size=lg&sig=x", domain.thumbUrl)
        assertEquals("http://192.0.2.10:8420/media/orig/abc?exp=1&sig=y", domain.origUrl)
        // 签名参数原样保留（签名 URL 禁改写，ADR-0002）
        assertTrue(domain.thumbUrl!!.endsWith("sig=x"))
    }

    @Test
    fun `目录字段 - 库内相对路径原样透传 缺省null等于库根`() {
        // 任务G G1b：文件整理弹窗预填当前目录——SDK directory 透传，不拼 base 不改写
        val withDir = SdkDetailMappers.toAssetDetail(
            sdkDetail().copy(directory = "2026/09"),
            baseUrl,
        )
        assertEquals("2026/09", withDir.directory)
        val rootLevel = SdkDetailMappers.toAssetDetail(sdkDetail(), baseUrl)
        assertNull(rootLevel.directory) // 库根（协议缺省 null；UI 侧 orEmpty 成空串）
    }

    @Test
    fun `relPath字段 - 库内相对路径透传 缺省null兜底空串`() {
        // 任务X X3：详细信息 Sheet「路径」行数据源——SDK relPath 原样透传，不拼 base 不改写
        val withPath = SdkDetailMappers.toAssetDetail(
            sdkDetail().copy(relPath = "cos/2026/img_001.jpg"),
            baseUrl,
        )
        assertEquals("cos/2026/img_001.jpg", withPath.relPath)
        // 协议缺省 null → 空串兜底（UI 按「null/0 不渲染」口径隐藏路径行）
        val noPath = SdkDetailMappers.toAssetDetail(sdkDetail(), baseUrl)
        assertEquals("", noPath.relPath)
    }

    @Test
    fun `cosWork为null回退fileName - 已是绝对的URL原样保留`() {
        val absolute = "https://cdn.example.com/thumb/x"
        val domain = SdkDetailMappers.toAssetDetail(
            sdkDetail(fileName = "IMG_001.jpg", thumbUrl = absolute, origUrl = null),
            baseUrl,
        )
        assertEquals("IMG_001.jpg", domain.title)
        assertEquals(absolute, domain.thumbUrl)
        assertNull(domain.origUrl)
    }

    @Test
    fun `媒体类型-互动字段-断点位置映射`() {
        val domain = SdkDetailMappers.toAssetDetail(
            sdkDetail(
                mediaType = MediaType.video,
                likedToday = true,
                likeCount = 9,
                lastPositionSeconds = 125.75,
            ),
            baseUrl,
        )
        assertEquals(MediaKind.VIDEO, domain.mediaType)
        assertTrue(domain.likedToday)
        assertEquals(9, domain.likeCount)
        assertEquals(125.75, domain.lastPositionSeconds!!, 0.0)
        assertTrue(domain.isFavorite)
        assertEquals(7, domain.viewCount)
        assertEquals(3, domain.playCount)
        assertEquals(1920, domain.width)
        assertEquals(1080, domain.height)
    }

    @Test
    fun `作者cos类型与followed-标签映射`() {
        val domain = SdkDetailMappers.toAssetDetail(
            sdkDetail(
                tags = listOf(Tag(id = "t1", name = "甲")),
                authors = listOf(
                    Author(id = "cos_1", displayName = "名前", type = Author.Type.cos, followed = true),
                    Author(id = "a2", displayName = "常规", type = Author.Type.regular, followed = null),
                ),
            ),
            baseUrl,
        )
        assertEquals(1, domain.tags.size)
        assertEquals("t1", domain.tags[0].id)
        assertEquals("甲", domain.tags[0].name)
        val cos = domain.authors[0]
        assertTrue(cos.isCos)
        assertTrue(cos.followed)
        val regular = domain.authors[1]
        assertFalse(regular.isCos)
        assertFalse(regular.followed) // followed 缺省 false（SDK null 兜底）
    }

    @Test
    fun `LikeState空值兜底`() {
        val result = SdkDetailMappers.toLikeToggleResult(LikeState(likedToday = null, likeCount = null))
        assertFalse(result.likedToday)
        assertEquals(0, result.likeCount)
        val filled = SdkDetailMappers.toLikeToggleResult(LikeState(likedToday = true, likeCount = 5))
        assertTrue(filled.likedToday)
        assertEquals(5, filled.likeCount)
    }

    @Test
    fun `修改时间转epoch毫秒`() {
        val domain = SdkDetailMappers.toAssetDetail(
            sdkDetail(modifiedAt = OffsetDateTime.parse("2026-01-05T12:34:56+08:00")),
            baseUrl,
        )
        // 与 SdkMappers.toMediaAsset 同转换链：OffsetDateTime → Instant → epochMilli
        assertEquals(
            OffsetDateTime.parse("2026-01-05T12:34:56+08:00").toInstant().toEpochMilli(),
            domain.modifiedAtMs,
        )
    }

    // ---------------- M4-3 3d：进度 / 打点 / 时间轴标签映射 ----------------

    @Test
    fun `时间轴标签映射与可空字段兜底`() {
        val filled = SdkDetailMappers.toTimelineTag(
            media.qimeng.sdk.models.TimelineTag(id = "tt1", timeMillis = 65000L, name = "高光"),
        )
        assertEquals("tt1", filled.id)
        assertEquals(65000L, filled.timeMillis)
        assertEquals("高光", filled.name)
        // null 兜底：id/name 归空串、timeMillis 归 0（协议 required，null 仅防御）
        val empty = SdkDetailMappers.toTimelineTag(
            media.qimeng.sdk.models.TimelineTag(id = null, timeMillis = null, name = null),
        )
        assertEquals("", empty.id)
        assertEquals(0L, empty.timeMillis)
        assertEquals("", empty.name)
        // N4 I7b：color 服务端色透传；空串归 null（=无服务端颜色，客户端前缀推断保底）
        val colored = SdkDetailMappers.toTimelineTag(
            media.qimeng.sdk.models.TimelineTag(id = "tt2", timeMillis = 1L, name = "自定义", color = "#d6336c"),
        )
        assertEquals("#d6336c", colored.color)
        val blank = SdkDetailMappers.toTimelineTag(
            media.qimeng.sdk.models.TimelineTag(id = "tt3", timeMillis = 1L, name = "空色", color = ""),
        )
        assertNull(blank.color)
        assertEquals(
            2,
            SdkDetailMappers.toTimelineTags(
                listOf(
                    media.qimeng.sdk.models.TimelineTag(id = "tt1", timeMillis = 1L, name = "a"),
                    media.qimeng.sdk.models.TimelineTag(id = "tt2", timeMillis = 2L, name = "b"),
                ),
            ).size,
        )
    }

    @Test
    fun `进度上报请求体Double直传`() {
        val update = SdkDetailMappers.toProgressUpdate(125.75)
        assertEquals(125.75, update.positionSeconds, 1e-9)
        // 整数秒同值直传
        assertEquals(90.0, SdkDetailMappers.toProgressUpdate(90.0).positionSeconds, 1e-9)
    }

    @Test
    fun `行为打点报告映射kind与dwell秒`() {
        val open = SdkDetailMappers.toViewEventReport(
            "11111111-1111-1111-1111-111111111111",
            media.qimeng.app.core.model.ViewEventKind.OPEN,
            startedAtMs = 1_000L,
            sessionId = "s-1",
            clientEventId = CLIENT_EVENT_ID,
            dwellSeconds = null,
        )
        assertEquals(media.qimeng.sdk.models.ViewEventReport.Kind.`open`, open.kind)
        assertNull(open.seconds)
        assertEquals("s-1", open.sessionId)
        assertEquals(1_000L, open.startedAt.toInstant().toEpochMilli())
        // 幂等键透传（任务L L5）：报告体携带调用方生成的 clientEventId
        assertEquals(java.util.UUID.fromString(CLIENT_EVENT_ID), open.clientEventId)

        val dwell = SdkDetailMappers.toViewEventReport(
            "11111111-1111-1111-1111-111111111111",
            media.qimeng.app.core.model.ViewEventKind.DWELL,
            startedAtMs = 2_000L,
            sessionId = "s-1",
            clientEventId = CLIENT_EVENT_ID,
            dwellSeconds = 42L,
        )
        assertEquals(media.qimeng.sdk.models.ViewEventReport.Kind.dwell, dwell.kind)
        assertEquals(42.0, dwell.seconds!!, 1e-9)

        val play = SdkDetailMappers.toViewEventReport(
            "11111111-1111-1111-1111-111111111111",
            media.qimeng.app.core.model.ViewEventKind.PLAY,
            startedAtMs = 3_000L,
            sessionId = "s-1",
            clientEventId = CLIENT_EVENT_ID,
            dwellSeconds = null,
        )
        assertEquals(media.qimeng.sdk.models.ViewEventReport.Kind.play, play.kind)
    }
}
