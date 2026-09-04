package media.qimeng.app.feature.upload

import androidx.compose.runtime.Composable
import media.qimeng.app.core.ui.component.QimengPlaceholderPage

/**
 * 上传主通道占位（M4-0 空壳）：真实现随 M4-5 落地（系统分享接收 + SAF 多选 + WorkManager 队列）。
 * 本页不进底部五 Tab——入口为系统分享与 App 内上传动作（M4-5 接 intent-filter 后生效）。
 */
@Composable
fun UploadScreen() {
    QimengPlaceholderPage(title = "上传")
}
