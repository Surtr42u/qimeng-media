package media.qimeng.app.core.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import media.qimeng.app.core.ui.R
import media.qimeng.app.core.ui.icon.gridIconFor
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 标题 + 统计行 + 列数控件行（库列表页页头唯一实现——任务A §5.2，相册/收藏/历史共用，
 * B5 收藏/历史页接线复用，禁止在 feature 各自手抄第 2 份）。
 * 列数控件=图标按钮，图标随列数换 ic_grid_2~5（旧版标题行右侧 allColumnText 同款；
 * 筛选按钮随 B3 面板再加入）。
 *
 * @param title 页面标题（页私有文案，由调用方从各自 strings.xml 注入）
 * @param statLine 统计行文本（如「N 文件」；暂无数据传空串，占位仍保留右端对齐结构）
 * @param columns 当前列数（驱动图标档位，越界由 [gridIconFor] clamp）
 * @param onToggleColumns 列数步进回调（步进/持久化语义在调用方 ViewModel）
 * @param modifier 行级外部布局参数（页面纵向上仍由调用方整体排布）
 */
@Composable
fun QimengTitleRow(
    title: String,
    statLine: String,
    columns: Int,
    onToggleColumns: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = QimengDimens.ScreenPaddingHorizontal,
                vertical = QimengDimens.SpaceM,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = statLine,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = onToggleColumns) {
            Icon(
                imageVector = gridIconFor(columns),
                contentDescription = stringResource(R.string.ui_columns_icon_desc),
            )
        }
    }
}
