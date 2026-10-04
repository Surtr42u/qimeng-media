package media.qimeng.app.core.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 覆盖页/列表页统一顶栏：返回 + 标题 + 可选动作区（同构页面禁止各写一套 Scaffold 顶栏）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QimengTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        navigationIcon = {
            if (onBack != null) {
                // 返回图标走 M3 IconButton（无自持矢量需求，箭头语义系统自带）
                androidx.compose.material3.IconButton(onClick = onBack) {
                    androidx.compose.material3.Icon(
                        imageVector = media.qimeng.app.core.ui.icon.BackIcon,
                        contentDescription = "返回",
                    )
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
        modifier = modifier,
    )
}

/**
 * 统一空态（文本可区分场景：相册空白 / 收藏 / 历史 / 搜索各用各的文案，规格书语义）。
 */
@Composable
fun QimengEmptyState(
    text: String,
    modifier: Modifier = Modifier,
) {
    // C8 用户视角重审修复（S9 空态文案 26.8%）：撑满剩余空间使文案垂直居中，对齐旧仓库
    // 空态口径——fragment_favorite.xml L160-168 / fragment_browse_history.xml L167-176 均为
    // height=0dp + weight=1 + gravity=center（剩余区域居中）；旧实现 wrap-content 落顶部，
    // 与旧版可见位置差（顶部 vs 居中）。8 处调用方（all/author/favorite/history/home/search）
    // 各自独占满容父容器（7 处在 QimengPullToRefresh content 内；search ResultPhase 在
    // Box(fillMaxSize) 内独占、无兄弟），fillMaxSize 无挤压风险。
    Box(modifier = modifier.fillMaxSize().padding(vertical = QimengDimens.EmptyStateVerticalPadding)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

/**
 * 统一下拉刷新容器（Material3 自带 PullToRefreshBox；旧版 SwipeRefreshLayout 的 Compose 等价物）。
 * 各页刷新语义（首页 seed 重排 / 列表重拉）由调用方 onRefresh 决定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QimengPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
    ) {
        content()
    }
}
