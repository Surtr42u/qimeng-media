package media.qimeng.app.feature.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import media.qimeng.app.core.model.AssetDetail
import media.qimeng.app.core.ui.component.QimengThumbnail

/**
 * 图片/动图舞台（3a 占位桩，自 DetailSections.kt MediaStage 图片分支拆出，零行为变化）：
 * QimengThumbnail thumbUrl 直链渲染（空值兜底由组件内置）。真身 3b 替换。
 *
 * @param onSiblingNavigate 左右滑切换相邻资产回调——**3a 桩暂不消费**：
 *   3b 实现左右滑与沉浸单击时在此接线（拍板③，目标 id 由 DetailViewModel.moveBy 解析）
 * @param onToggleChrome 沉浸模式顶行开关回调——**3a 桩暂不消费**：3b 单击手势接线
 */
@Composable
internal fun ImageStage(
    asset: AssetDetail,
    modifier: Modifier,
    onSiblingNavigate: (delta: Int) -> Unit,
    onToggleChrome: () -> Unit,
) {
    Box(modifier = modifier) {
        QimengThumbnail(
            model = asset.thumbUrl,
            contentDescription = stringResource(R.string.detail_media_stage),
            modifier = Modifier.fillMaxSize(),
        )
    }
}
