package media.qimeng.app.core.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 榜单卡容器（任务G G2）：对齐 Web .rank-card 卡盒语言（prototype.css「卡片通用」规则——
 * background var(--bg) 页面底色 + 1px var(--border) 描边 + 12px 圆角 + 16px 内边距）。
 * 设计语义：以描边而非实底区分卡片（底色=页面底色 background），与 surfaceVariant
 * 实底卡（设置页入口行等）是两种并存的卡语言，不互相替换。
 * 单源卡盒，消费方清单：feature/settings 作者总览卡、feature/author 作者管理列表；
 * 新的榜单式卡片一律复用本组件，禁止各页自绘描边卡。
 *
 * @param modifier 尺寸由调用方给（fillMaxWidth 撑满宽 / fillMaxSize 撑满剩余区）
 * @param content 卡内内容（内边距 16dp 已由本组件统一施加，调用方勿重复）
 */
@Composable
fun QimengRankCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(QimengDimens.RankCardCornerRadius),
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(QimengDimens.RankCardBorderWidth, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(QimengDimens.RankCardInnerPadding),
            content = content,
        )
    }
}
