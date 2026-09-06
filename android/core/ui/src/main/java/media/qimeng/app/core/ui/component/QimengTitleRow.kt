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
import media.qimeng.app.core.ui.icon.FilterListIcon
import media.qimeng.app.core.ui.icon.gridIconFor
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 标题 + 统计行 + 列数控件行（库列表页页头唯一实现——任务A §5.2，相册/收藏/历史共用，
 * B5 收藏/历史页接线复用，禁止在 feature 各自手抄第 2 份）。
 * 列数控件=图标按钮，图标随列数换 ic_grid_2~5（旧版标题行右侧 allColumnText 同款）；
 * 筛选按钮（M4-2A-B3）= 可选项，仅相册页传入（按实录判读：筛选入口只在相册页标题行——
 * 旧版 uiautomator 实录仅 all_partition.txt 有 allFilterButton 节点，favorite/history 实录
 * 无筛选图标，/history 协议亦不支持筛选参数；此判读待 B5 收藏/历史接线时复核落档，
 * 收藏/历史页不传 onFilterClick 即不显示）。
 *
 * @param title 页面标题（页私有文案，由调用方从各自 strings.xml 注入）
 * @param statLine 统计行文本（如「N 文件」；暂无数据传空串，占位仍保留右端对齐结构）
 * @param columns 当前列数（驱动图标档位，越界由 [gridIconFor] clamp）
 * @param onToggleColumns 列数步进回调（步进/持久化语义在调用方 ViewModel）
 * @param onFilterClick 筛选按钮回调（null=不显示筛选图标，M4-2A-B3 可选入口）
 * @param modifier 行级外部布局参数（页面纵向上仍由调用方整体排布）
 */
@Composable
fun QimengTitleRow(
    title: String,
    statLine: String,
    columns: Int,
    onToggleColumns: () -> Unit,
    modifier: Modifier = Modifier,
    onFilterClick: (() -> Unit)? = null,
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
        if (onFilterClick != null) {
            IconButton(onClick = onFilterClick) {
                Icon(
                    imageVector = FilterListIcon,
                    contentDescription = stringResource(R.string.ui_filter_icon_desc),
                )
            }
        }
        IconButton(onClick = onToggleColumns) {
            Icon(
                imageVector = gridIconFor(columns),
                contentDescription = stringResource(R.string.ui_columns_icon_desc),
            )
        }
    }
}
