package media.qimeng.app.core.data.repository

import java.math.BigDecimal
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

    private val baseUrl = "http://192.168.1.10:8420"

    private fun sdkDetail(
        id: String = "11111111-1111-1111-1111-111111111111",
        fileName: String = "IMG_001.jpg",
        cosWork: String? = null,
        mediaType: MediaType = MediaType.image,
        thumbUrl: String? = "/media/thumb/abc?size=lg&sig=x",
        origUrl: String? = "/media/orig/abc?exp=1&sig=y",
        lastPositionSeconds: BigDecimal? = null,
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
        assertEquals("http://192.168.1.10:8420/media/thumb/abc?size=lg&sig=x", domain.thumbUrl)
        assertEquals("http://192.168.1.10:8420/media/orig/abc?exp=1&sig=y", domain.origUrl)
        // 签名参数原样保留（签名 URL 禁改写，ADR-0002）
        assertTrue(domain.thumbUrl!!.endsWith("sig=x"))
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
                lastPositionSeconds = BigDecimal("125.75"),
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
}
