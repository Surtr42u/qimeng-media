package media.qimeng.app.core.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 占位页共享组件（M4-0 空壳专用）：所有 feature 占位页统一由它渲染，避免十处复制粘贴。
 * 占位文案直接内嵌——生命周期只到对应功能批次落地为止，不值得抽资源。
 */
@Composable
fun QimengPlaceholderPage(
    title: String,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(all = Dimens.ScreenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "占位页（M4-0 骨架）——内容随后续批次填充",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 布局尺寸 token：占位阶段只有一屏内边距一个值，后续批次按需扩充。 */
object Dimens {
    /** 页面四周统一留白 */
    val ScreenPadding = 16.dp
}
