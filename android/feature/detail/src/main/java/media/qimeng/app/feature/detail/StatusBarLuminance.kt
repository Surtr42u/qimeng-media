package media.qimeng.app.feature.detail

/**
 * 图片态沉浸（chrome 隐藏）状态栏图标反色的亮度采样与阈值判定（任务S 批S10 件1，
 * 2026-09-19 用户拍板「应该是白色上面状态栏就是反色这种……就是手机相册那种」）：
 * chrome 隐藏后系统状态栏浮在图片内容上（而非 App 主题底色），图标明暗须随图片顶部
 * 实际亮度自适应——顶部亮（白图）→ 深色图标、顶部暗（黑图）→ 浅色图标，手机相册语义。
 *
 * 两段均为纯函数（无 IO、无 Android 类型依赖，JVM 单测锁定 StatusBarLuminanceTest）：
 * - [topRegionAverageLuminance]：展平像素 → 顶部 1/3 区域平均亮度。像素由 48px 采样小图
 *   （Coil 请求 `allowHardware(false)` 解码的软件位图——硬件位图禁 getPixels）经
 *   Bitmap.getPixels 展平后传入；只算顶部 1/3——状态栏浮在图片顶部，中/下部亮度与
 *   本判定无关。
 * - [statusBarIconsDarkForLuminance]：亮度 → 图标明暗阈值判定。阈值口径见
 *   [STATUS_BAR_LUMINANCE_DARK_ICONS]：>0.35 → 深色图标（含 0.35~0.5 中灰灰区取深，
 *   拍板口径「灰区取深」的等价化简——宁深勿浅，防白图标压浅图不可辨）；≤0.35 → 浅色
 *   图标（黑/深图黑底语义）。
 *
 * 亮度域选型记档：用 BT.601 gamma 域加权 luma（近似感知亮度），不用 sRGB 线性化亮度
 * （android.graphics.Color.luminance 同款）——线性域把中灰压到约 0.21，与拍板给的
 * 「0.35~0.5 灰区」感知数值口径不符；gamma 域 luma 数值即人眼感知近似，阈值可按拍板
 * 原话直读。失败/未加载兜底（null → 浅色图标）在 DetailScreen 消费点裁决，不在本函数。
 */

/** chrome 隐藏态图标反色的采样小图边长（px，Coil `.size(48)` 解码目标档）：判明暗只需
 *  数百像素；仅作解码降采样目标，网络层仍取原件（磁盘缓存按 U10-5 口径禁用） */
internal const val STATUS_BAR_SAMPLE_SIZE_PX = 48

/** 顶部采样区占比（1/3）：状态栏浮在图片顶部，取顶部 1/3 高度的平均亮度作判定面 */
internal const val STATUS_BAR_SAMPLE_TOP_REGION_RATIO = 1.0 / 3.0

/**
 * 深色图标判定阈值（gamma 域 luma，>阈值=深图标）：拍板口径「luminance>0.5 → 深图标；
 * 0.35~0.5 灰区取深」两子句等价化简为单阈值 0.35——0.35~0.5 中灰灰区归深，杜绝阈值附近
 * 图标明暗闪烁。
 */
internal const val STATUS_BAR_LUMINANCE_DARK_ICONS = 0.35

/**
 * 顶部 1/3 区域平均亮度（0.0 黑 ~ 1.0 白；展平像素按行优先 rows×cols，值=ARGB int）。
 * 行数=height×[STATUS_BAR_SAMPLE_TOP_REGION_RATIO] 向下取整且至少 1 行（极扁图防御）；
 * 逐像素 BT.601 加权后取均值。
 */
internal fun topRegionAverageLuminance(pixels: IntArray, cols: Int, rows: Int): Double {
    if (cols <= 0 || rows <= 0 || pixels.size < cols * rows) return 0.0
    val sampleRows = maxOf(1, (rows * STATUS_BAR_SAMPLE_TOP_REGION_RATIO).toInt())
    var total = 0.0
    for (y in 0 until sampleRows) {
        val rowStart = y * cols
        for (x in 0 until cols) {
            total += pixelLuminance(pixels[rowStart + x])
        }
    }
    val count = sampleRows * cols
    return total / count
}

/** 亮度 → 图标明暗阈值判定（true=深色图标；口径见 [STATUS_BAR_LUMINANCE_DARK_ICONS]） */
internal fun statusBarIconsDarkForLuminance(luminance: Double): Boolean =
    luminance > STATUS_BAR_LUMINANCE_DARK_ICONS

/** 单像素 BT.601 gamma 域加权 luma：0.299R + 0.587G + 0.114B（分量归一，不线性化） */
private fun pixelLuminance(pixel: Int): Double {
    val r = (pixel shr 16 and 0xFF) / 255.0
    val g = (pixel shr 8 and 0xFF) / 255.0
    val b = (pixel and 0xFF) / 255.0
    return 0.299 * r + 0.587 * g + 0.114 * b
}
