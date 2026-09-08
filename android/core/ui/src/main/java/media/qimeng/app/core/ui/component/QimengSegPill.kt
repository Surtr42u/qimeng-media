package media.qimeng.app.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 分段选择胶囊（任务 G6：FilterChip 的胶囊替身，对齐 Web .seg/.pill=999px）。
 *
 * 语义：无勾选框；选中=主色实底反色字（SemiBold），未选中=软底 surfaceVariant——与
 * [QimengPills] 的胶囊语言完全同谱（选中实底/未选软底）。
 *
 * 实现取舍：自绘 Text+clip+background+clickable，不用 FilterChip——FilterChip 选中态自带
 * 前导勾选图标与描边观感，与「无勾选框软底胶囊」语言冲突（Web .seg 无勾选框）。
 * 渲染参数与 QimengPills 原私有 PillChip 逐项相同（G6 查重后 PillChip 已改为委托本组件），
 * 本组件是全仓单枚胶囊渲染的唯一来源，禁止再开平行实现。
 *
 * 消费方清单：feature/settings 缓存档位、feature/stats 时段档、feature/upload 目标库选择、
 * QimengPills 全部胶囊行/词丸流/悬浮面板（经私有 PillChip 委托）。
 *
 * @param text 胶囊文案
 * @param selected 选中态（实底主色 vs 软底）
 * @param onClick 点按回调（分段切换语义，由调用方驱动状态）
 */
@Composable
fun QimengSegPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier
            .clip(RoundedCornerShape(QimengDimens.PillCornerRadius))
            .background(
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = QimengDimens.ChipHorizontalPadding,
                vertical = QimengDimens.SpaceS,
            ),
    )
}
