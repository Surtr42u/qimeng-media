package media.qimeng.app.feature.detail.image

import android.content.Context

/**
 * dp → px 扩展（搬运最小面：[ZoomImageView] 横滑阈值判定用）。
 * 来源：QimengMedia 旧仓 com.qimeng.media.ui.widget.DimenExt.kt（仅取 Int.dpFloat 一档，
 * 其余 dp 档在本仓 Compose 侧走 QimengDimens，View 桥接层用不到）。
 */
fun Int.dpFloat(context: Context): Float =
    this * context.resources.displayMetrics.density
