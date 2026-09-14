package media.qimeng.app.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import media.qimeng.app.core.ui.R
import media.qimeng.app.core.ui.icon.BackIcon
import media.qimeng.app.core.ui.icon.HomeFilterIcon
import media.qimeng.app.core.ui.icon.gridIconFor
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 返回钮 + 标题 + 统计行 + 可选动作/列数控件行（库列表页页头唯一实现——任务A §5.2，相册/收藏/历史共用，
 * B5 收藏/历史页接线复用，禁止在 feature 各自手抄第 2 份）。
 * 返回钮（M4-2A-B5 收编进本组件）：旧版实录 favorite.txt/history.txt 头部首元素均为返回图标
 * （desc=返回，favorite.txt L6 / history.txt L6），收藏/历史页传 onBack，相册页不传（Tab 内无返回语义）。
 * 列数控件=图标按钮，图标随列数换 ic_grid_2~5（旧版标题行右侧 allColumnText 同款）；为可选项——
 * 旧版实录 favorite/history 两页标题行**无**列数图标（主会话 2026-09-07 裁定 2），两页不传即不显示。
 * 筛选按钮（M4-2A-B3）= 可选项，仅相册页传入（按实录判读：筛选入口只在相册页标题行——
 * 旧版 uiautomator 实录仅 all_partition.txt 有 allFilterButton 节点，favorite/history 实录
 * 无筛选图标，/history 协议亦不支持筛选参数；B5 接线已复核落档）。U10-3 观感对齐：钮体换
 * 旧版软底胶囊容器+ic_home_filter 专用图标（见实现处注释）。
 *
 * @param title 页面标题（页私有文案，由调用方从各自 strings.xml 注入）
 * @param statLine 统计行文本（如「N 文件」；暂无数据传空串，占位仍保留右端对齐结构）
 * @param titleStyle 页标题样式组件级覆盖（Y3 批 2026-09-12 裁决：Typography 角色不动——旧版页标题
 *   自身三档 28/24/22sp，角色级一刀切会殃及收藏/历史等页，逐页对齐走本参数。相册页 28sp Bold
 *   （fragment_all_files.xml L31-37）、收藏/历史 22sp Bold（fragment_favorite.xml L36-45 /
 *   fragment_browse_history.xml L37-40，色均 qmColorTextPrimary→onSurface 槽）；null=默认
 *   titleLarge（M3 22sp Regular）不回归旧版观感的调用方零影响）
 * @param onBack 返回按钮回调（null=不显示返回钮，M4-2A-B5 可选项）
 * @param modifier 行级外部布局参数（页面纵向上仍由调用方整体排布）
 * @param onFilterClick 筛选按钮回调（null=不显示筛选图标，M4-2A-B3 可选入口）
 * @param filterActive 是否存在已激活筛选（U10-2b，2026-09-14 用户真机反馈「筛选钮常亮」）：
 *   false=透明底（40dp 尺寸/图标 tint 不变，防布局跳动），true=旧版软底胶囊。旧版为恒显软底
 *   （fragment_all_files.xml:48-57 静态 bg_capsule_soft，无状态切换代码），本开关为按用户反馈
 *   做的有意偏离增强；仅相册页传入，收藏/历史走默认 false 观感不变
 * @param columns 当前列数（驱动图标档位，越界由 [gridIconFor] clamp；与 onToggleColumns 成对出现）
 * @param onToggleColumns 列数步进回调（null=整组列数控件不显示，M4-2A-B5 可选项；步进/持久化语义在调用方 ViewModel）
 */
@Composable
fun QimengTitleRow(
    title: String,
    statLine: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onFilterClick: (() -> Unit)? = null,
    filterActive: Boolean = false,
    columns: Int? = null,
    onToggleColumns: (() -> Unit)? = null,
    titleStyle: TextStyle? = null,
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
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = BackIcon,
                    contentDescription = stringResource(R.string.ui_back_desc),
                )
            }
        }
        // Y3 批：titleStyle 非空时页标题组件级覆盖（见 KDoc @param titleStyle——旧版页标题三档
        // 28/24/22sp，Typography 角色级不动），null=维持 titleLarge 基线
        Text(text = title, style = titleStyle ?: MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = statLine,
            // Y3 批：字重对齐旧版 Regular（fragment_all_files.xml L39-46 统计行 12sp 无 bold，
            // labelMedium 默认 Medium 500→Normal 400；字号 12sp/色 onSurfaceVariant 已与旧版一致不动）
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onFilterClick != null) {
            // U10-3：对齐旧版 allFilterButton（fragment_all_files.xml L48-57——40dp bg_capsule_soft
            // 胶囊底 + ic_home_filter tint qmColorPrimary）：裸图标 IconButton 换软底胶囊容器 +
            // 专用款 [HomeFilterIcon]（旧 ic_home_filter 三页通用，勿用 Material filter_list）。
            // 不走 IconButton/可点击 Surface：二者内建 48dp 最小触达会把旧版 40dp 胶囊撑大
            // （HomeTopIconButton 同成因）；内层 clickable 承担点击（ripple 被 Surface 形状裁剪）
            Surface(
                shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
                // U10-2b：无激活筛选=透明底（用户反馈「常亮」——旧版恒显软底系静态容器无状态
                // 语义，见 KDoc @param filterActive 有意偏离记档）；true 恢复旧版软底
                color = if (filterActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                modifier = Modifier.size(QimengDimens.IconButtonSize),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClick = onFilterClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = HomeFilterIcon,
                        contentDescription = stringResource(R.string.ui_filter_icon_desc),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        // 列数控件=图标+回调成对出现：回调为 null（或未配列数）整组不显示，不渲染残缺控件
        if (onToggleColumns != null && columns != null) {
            IconButton(onClick = onToggleColumns) {
                Icon(
                    imageVector = gridIconFor(columns),
                    contentDescription = stringResource(R.string.ui_columns_icon_desc),
                )
            }
        }
    }
}
