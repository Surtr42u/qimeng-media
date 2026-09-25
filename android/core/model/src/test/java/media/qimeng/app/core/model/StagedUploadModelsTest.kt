package media.qimeng.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 暂存区持久模型纯规则锁定（2026-09-25 暂存区重做）：
 * 基名/扩展名拆装（扩展名锁定口径）/ 落库名回退 / 收件箱白名单与视频扩展名 / 源标识判定。
 */
class StagedUploadModelsTest {

    // ---- UploadNaming（基名/扩展名拆装，扩展名锁定口径的单一实现） ----

    @Test
    fun `基名与扩展名拆装普通形态`() {
        assertEquals("IMG_1", UploadNaming.baseNameOf("IMG_1.jpg"))
        assertEquals(".jpg", UploadNaming.extensionOf("IMG_1.jpg"))
        assertEquals("守望先锋dva", UploadNaming.baseNameOf("守望先锋dva.png"))
        assertEquals(".png", UploadNaming.extensionOf("守望先锋dva.png"))
    }

    @Test
    fun `多点文件名取最后一段为扩展名`() {
        assertEquals("a.b", UploadNaming.baseNameOf("a.b.jpg"))
        assertEquals(".jpg", UploadNaming.extensionOf("a.b.jpg"))
    }

    @Test
    fun `无扩展名与点前缀隐藏文件整体为基名`() {
        assertEquals("README", UploadNaming.baseNameOf("README"))
        assertEquals("", UploadNaming.extensionOf("README"))
        // ".hidden"：点前缀隐藏文件没有可锁定的扩展名，整体当基名（防拆出空基名）
        assertEquals(".hidden", UploadNaming.baseNameOf(".hidden"))
        assertEquals("", UploadNaming.extensionOf(".hidden"))
    }

    @Test
    fun `落库名拼接含扩展名锁定与空基名回退`() {
        assertEquals("守望先锋 DVA 13.jpg", UploadNaming.composeUploadName("守望先锋 DVA 13", ".jpg"))
        // 基名 trim（输入框首尾空格卫生）；扩展名原样拼接
        assertEquals("名13.jpg", UploadNaming.composeUploadName(" 名13 ", ".jpg"))
        // 空基名 = 未编辑信号（调用方回退展示名），拼接器返回空串
        assertEquals("", UploadNaming.composeUploadName("   ", ".jpg"))
        assertEquals("", UploadNaming.composeUploadName("", ".jpg"))
        // 无扩展名条目只取基名
        assertEquals("README", UploadNaming.composeUploadName("README", ""))
    }

    // ---- StagedUpload.effectiveUploadName（回退口径：未编辑/清空回退展示名） ----

    @Test
    fun `未编辑基名回退展示名`() {
        val item = staged(displayName = "IMG_1.jpg")
        assertEquals("IMG_1.jpg", item.effectiveUploadName)
        assertEquals(".jpg", item.extension)
        assertEquals("IMG_1", item.defaultBaseName)
    }

    @Test
    fun `编辑基名拼接锁定扩展名`() {
        val item = staged(displayName = "IMG_1.jpg", uploadBaseName = "守望先锋 DVA 13")
        assertEquals("守望先锋 DVA 13.jpg", item.effectiveUploadName)
    }

    @Test
    fun `基名空白回退展示名`() {
        val item = staged(displayName = "IMG_1.jpg", uploadBaseName = "   ")
        assertEquals("IMG_1.jpg", item.effectiveUploadName)
    }

    @Test
    fun `基名首尾空格trim后生效`() {
        val item = staged(displayName = "IMG_1.jpg", uploadBaseName = " 名13 ")
        assertEquals("名13.jpg", item.effectiveUploadName)
    }

    private fun staged(displayName: String, uploadBaseName: String? = null) = StagedUpload(
        source = "/storage/emulated/0/.dl/$displayName",
        isPathSource = true,
        displayName = displayName,
        sizeBytes = 1,
        isVideo = false,
        uploadBaseName = uploadBaseName,
    )

    // ---- UploadRules 收件箱扫描口径 ----

    @Test
    fun `媒体扩展名白名单命中与大小写不敏感`() {
        assertTrue(UploadRules.isAllowedMediaExtension("a.JPG"))
        assertTrue(UploadRules.isAllowedMediaExtension("a.jpeg"))
        assertTrue(UploadRules.isAllowedMediaExtension("b.Png"))
        assertTrue(UploadRules.isAllowedMediaExtension("c.MKV"))
        // 与服务端 filing 超集决策一致：m4v 与 mp4 同容器
        assertTrue(UploadRules.isAllowedMediaExtension("d.m4v"))
    }

    @Test
    fun `白名单外扩展名与无扩展名拒绝`() {
        assertFalse(UploadRules.isAllowedMediaExtension("a.txt"))
        assertFalse(UploadRules.isAllowedMediaExtension("a.exe"))
        assertFalse(UploadRules.isAllowedMediaExtension("a.nomedia"))
        assertFalse(UploadRules.isAllowedMediaExtension("README"))
        assertFalse(UploadRules.isAllowedMediaExtension(".hidden"))
    }

    @Test
    fun `视频扩展名判定用于isVideo`() {
        assertTrue(UploadRules.isVideoExtension("a.MP4"))
        assertTrue(UploadRules.isVideoExtension("b.mov"))
        assertTrue(UploadRules.isVideoExtension("c.webm"))
        assertFalse(UploadRules.isVideoExtension("a.jpg"))
        assertFalse(UploadRules.isVideoExtension("b.png"))
        assertFalse(UploadRules.isVideoExtension("README"))
    }

    @Test
    fun `源标识路径类判定`() {
        assertTrue(UploadRules.isAbsoluteFilePath("/storage/emulated/0/.dl/a.jpg"))
        assertTrue(UploadRules.isAbsoluteFilePath("/tmp/x"))
        assertFalse(UploadRules.isAbsoluteFilePath("content://media/external/images/1"))
        assertFalse(UploadRules.isAbsoluteFilePath(""))
    }
}
