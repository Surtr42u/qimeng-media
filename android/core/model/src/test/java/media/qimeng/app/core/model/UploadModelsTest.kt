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

    // ---- UploadItem 基名编辑口径（2026-10-07 文件名编辑加回：基名可编辑、扩展名锁定） ----

    @Test
    fun `扩展名与基名按展示名拆分`() {
        val item = UploadItem(uri = "u", displayName = "作品 01.jpg", sizeBytes = 1)
        assertEquals(".jpg", item.extension)
        assertEquals("作品 01", item.defaultBaseName)
        assertEquals("作品 01", item.currentBaseName)
    }

    @Test
    fun `点前缀隐藏文件整体为基名无扩展名`() {
        val item = UploadItem(uri = "u", displayName = ".hidden", sizeBytes = 1)
        assertEquals("", item.extension)
        assertEquals(".hidden", item.defaultBaseName)
    }

    @Test
    fun `多点文件名取最后一段为扩展名`() {
        val item = UploadItem(uri = "u", displayName = "a.b.mp4", sizeBytes = 1)
        assertEquals(".mp4", item.extension)
        assertEquals("a.b", item.defaultBaseName)
    }

    @Test
    fun `基名编辑拼装锁定扩展名为落库名`() {
        val item = UploadItem(
            uri = "u",
            displayName = "IMG_1.jpg",
            sizeBytes = 1,
            uploadBaseName = "  新作品名 ",
        )
        // currentBaseName 回显输入原文（不 trim，编辑态所见即所输）；落库名才 trim
        assertEquals("  新作品名 ", item.currentBaseName)
        assertEquals("新作品名.jpg", item.effectiveUploadName)
    }

    @Test
    fun `基名里输入的点视为基名一部分扩展名不可换`() {
        val item = UploadItem(
            uri = "u",
            displayName = "IMG_1.jpg",
            sizeBytes = 1,
            uploadBaseName = "video.mp4",
        )
        // 扩展名锁定口径的核心防御：用户无法借基名编辑把 .jpg 换成 .mp4
        assertEquals("video.mp4.jpg", item.effectiveUploadName)
    }

    @Test
    fun `基名空白回退展示名`() {
        val item = UploadItem(
            uri = "u",
            displayName = "IMG_1.jpg",
            sizeBytes = 1,
            uploadBaseName = "   ",
        )
        assertEquals("IMG_1.jpg", item.effectiveUploadName)
    }

    @Test
    fun `基名清空为空串时currentBaseName如实回显空串不回弹`() {
        val item = UploadItem(
            uri = "u",
            displayName = "IMG_1.jpg",
            sizeBytes = 1,
            uploadBaseName = "",
        )
        // 用户清空输入框时，如实保持空串以供界面清空编辑，入队时回退 displayName
        assertEquals("", item.currentBaseName)
        assertEquals("IMG_1.jpg", item.effectiveUploadName)
    }

    @Test
    fun `手机待上传真实目录18个视频文件名清洗与拆装测试`() {
        val phoneFiles = listOf(
            "Vam 三角洲行动 佐娅能天使 被伏.mp4" to ("Vam 三角洲行动 佐娅能天使 被伏" to ".mp4"),
            "4k-beach-sex-with-jubilee-liepraag_2160p.mp4" to ("4k-beach-sex-with-jubilee-liepraag_2160p" to ".mp4"),
            "VGErotica.mp4" to ("VGErotica" to ".mp4"),
            "Aphy3d.mp4" to ("Aphy3d" to ".mp4"),
            "13 Mercy Bathroom (2K) 54FPS_3840x2160_prob-3.mp4" to ("13 Mercy Bathroom (2K) 54FPS_3840x2160_prob-3" to ".mp4"),
            "Ashley Church (1080) [NO WM].mp4" to ("Ashley Church (1080) [NO WM]" to ".mp4"),
            "Ashley Sofa (1080) [NO WM].mp4" to ("Ashley Sofa (1080) [NO WM]" to ".mp4"),
            "HydraFXX Tifa ALT+ CUT (2024).mp4" to ("HydraFXX Tifa ALT+ CUT (2024)" to ".mp4"),
            "[HydraFXX] 2B Woods (4K) NO WM.mp4" to ("[HydraFXX] 2B Woods (4K) NO WM" to ".mp4"),
            "·克莱尔警服诱惑（生.mp4" to ("·克莱尔警服诱惑（生" to ".mp4"),
            "Shadowheart 4K HydraFXX.mp4" to ("Shadowheart 4K HydraFXX" to ".mp4"),
            "malenia-defeated-m71z30_2160p.mp4" to ("malenia-defeated-m71z30_2160p" to ".mp4"),
            "11.mp4" to ("11" to ".mp4"),
            "最终幻想  露娜弗蕾亚 5.mp4" to ("最终幻想  露娜弗蕾亚 5" to ".mp4"),
            "Idemi .mp4" to ("Idemi " to ".mp4"),
            "Arhoangel.mp4" to ("Arhoangel" to ".mp4"),
            "Horny Herring Studios.mp4" to ("Horny Herring Studios" to ".mp4"),
            "D.Va Anal Riding Huge Dildo In Front of Campus [Xordel].mp4" to ("D.Va Anal Riding Huge Dildo In Front of Campus [Xordel]" to ".mp4"),
        )
        for ((name, pair) in phoneFiles) {
            val (expectedBase, expectedExt) = pair
            val item = UploadItem(uri = "content://media/$name", displayName = name, sizeBytes = 1000L)
            assertEquals("defaultBaseName 校验: $name", expectedBase, item.defaultBaseName)
            assertEquals("extension 校验: $name", expectedExt, item.extension)
            val effective = item.effectiveUploadName
            assertTrue("落库文件名不应为空: $name", effective.isNotEmpty())
            assertTrue("落库扩展名应为 mp4: $name", effective.endsWith(".mp4"))
            assertFalse("不应命中保留名: $effective", UploadRules.isWindowsReservedName(effective))
        }
    }

    @Test
    fun `无扩展名文件基名编辑只取基名`() {
        val item = UploadItem(
            uri = "u",
            displayName = "无名",
            sizeBytes = 1,
            uploadBaseName = "新名",
        )
        assertEquals("", item.extension)
        assertEquals("新名", item.effectiveUploadName)
    }

    @Test
    fun `基名编辑优先于旧全名编辑字段`() {
        val item = UploadItem(
            uri = "u",
            displayName = "IMG_1.jpg",
            sizeBytes = 1,
            uploadFileName = "legacy.jpg",
            uploadBaseName = "新名",
        )
        assertEquals("新名.jpg", item.effectiveUploadName)
    }

    // ---- UploadNaming（扩展名锁定口径单一实现，用例对齐 Web upload-naming.test.ts） ----

    @Test
    fun `naming基名拆分常规文件名`() {
        assertEquals(".jpg", UploadNaming.extensionOf("作品 01.jpg"))
        assertEquals("作品 01", UploadNaming.baseNameOf("作品 01.jpg"))
    }

    @Test
    fun `naming无扩展名整体为基名`() {
        assertEquals("", UploadNaming.extensionOf("无名"))
        assertEquals("无名", UploadNaming.baseNameOf("无名"))
    }

    @Test
    fun `naming点前缀隐藏文件整体为基名`() {
        assertEquals("", UploadNaming.extensionOf(".hidden"))
        assertEquals(".hidden", UploadNaming.baseNameOf(".hidden"))
    }

    @Test
    fun `naming多点文件名取最后一段`() {
        assertEquals(".mp4", UploadNaming.extensionOf("a.b.mp4"))
        assertEquals("a.b", UploadNaming.baseNameOf("a.b.mp4"))
    }

    @Test
    fun `naming拼装基名与扩展名`() {
        assertEquals("名 13.png", UploadNaming.composeUploadName("名 13", ".png"))
        assertEquals("名 13", UploadNaming.composeUploadName("名 13", ""))
    }

    @Test
    fun `naming基名trim后为空返回空串`() {
        assertEquals("", UploadNaming.composeUploadName("   ", ".png"))
        assertEquals("", UploadNaming.composeUploadName("", ".png"))
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
