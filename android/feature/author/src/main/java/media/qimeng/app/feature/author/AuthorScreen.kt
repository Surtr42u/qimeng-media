package media.qimeng.app.feature.author

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.model.AuthorSortOption
import media.qimeng.app.core.model.AuthorSummary
import media.qimeng.app.core.model.Zone
import media.qimeng.app.core.model.displayLabel
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.component.QimengChipRow
import media.qimeng.app.core.ui.component.QimengPill
import media.qimeng.app.core.ui.component.QimengPullToRefresh
import media.qimeng.app.core.ui.component.QimengRankCard
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.icon.SearchIcon
import media.qimeng.app.core.ui.theme.QimengDimens

/** 作者管理胶囊（全部/常规/COS）+ 排序三项（默认/浏览数/文件数） */
private val ZONE_OPTIONS = listOf("全部" to Zone.ALL, "常规" to Zone.REGULAR, "COS" to Zone.COS)

/** 计数行文案前缀（G2：Web AuthorsPage page-head 副行「全部作者 · N 位」） */
private const val COUNT_ROW_PREFIX = "全部作者"

/** 空态文案（Web .a-empty：列表无匹配行时卡内小字） */
private const val EMPTY_AUTHORS = "暂无作者"

/**
 * 作者管理页（M4-2 覆盖页，G2 对齐 Web AuthorsPage 形态重排）：计数行 + 体系胶囊 +
 * 名字搜索 + 排序三项 + 关注 toggle（GET /authors 全量 + PUT /authors/{authorId}/follow）。
 * 列表套榜单卡（QimengRankCard 单源卡盒）；行点击回调 [onAuthorClick] 留接口占位，
 * 作者集合子页（Web /app/collection/author/{name} 等价物）归 G1b 批接线实现。
 *
 * @param onAuthorClick 行点击（authorId + 原始 displayName 不带「 ·COS」后缀——Web 跳转
 *   用原始名 + URL 编码同语义）；本批壳层接空实现
 */
@Composable
fun AuthorScreen(
    onBack: () -> Unit,
    onAuthorClick: (authorId: String, displayName: String) -> Unit = { _, _ -> },
    viewModel: AuthorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        QimengTopBar(title = "作者管理", onBack = onBack)

        // 计数行（G2：Web page-head 副行；N=全量作者数不随体系/关键词过滤，
        // 计数口径在 ViewModel.authorCount——UI 不内嵌业务规则）
        Text(
            text = "$COUNT_ROW_PREFIX · ${viewModel.authorCount(state)} 位",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = QimengDimens.ScreenPaddingHorizontal),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = QimengDimens.ScreenPaddingHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QimengCapsuleTextField(
                value = state.keyword,
                onValueChange = viewModel::onKeywordChange,
                placeholder = "按名字搜索",
                // 对齐 Web .hist-search：胶囊搜索框带前置放大镜（decorative，placeholder 已表意）
                leadingIcon = {
                    Icon(imageVector = SearchIcon, contentDescription = null)
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        QimengChipRow(
            pills = ZONE_OPTIONS.map { (label, zone) ->
                QimengPill(text = label, selected = zone == state.zone)
            },
            onPillClick = { index -> viewModel.selectZone(ZONE_OPTIONS[index].second) },
            modifier = Modifier.padding(horizontal = QimengDimens.ScreenPaddingHorizontal, vertical = QimengDimens.SpaceXS),
        )
        // 排序行（G2：Web .a-sortrow 定行不横滑——三项 QimengSegPill 直排，
        // 胶囊渲染与体系行同源（PillChip 委托），不再经横滑 ChipRow）
        Row(
            modifier = Modifier.padding(horizontal = QimengDimens.ScreenPaddingHorizontal, vertical = QimengDimens.SpaceXS),
            horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceS),
        ) {
            AuthorSortOption.entries.forEach { sort ->
                QimengSegPill(
                    text = sort.label,
                    selected = sort == state.sort,
                    onClick = { viewModel.selectSort(sort) },
                )
            }
        }

        state.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = QimengDimens.ScreenPaddingHorizontal),
            )
        }

        QimengPullToRefresh(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.weight(1f),
        ) {
            // 列表套榜单卡（G2：Web .rank-card.a-list——描边卡盒单源在 core:ui QimengRankCard）
            QimengRankCard(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = QimengDimens.ScreenPaddingHorizontal),
            ) {
                val rows = viewModel.visibleRows(state)
                if (rows.isEmpty()) {
                    // 首载中不占位（既有行为保持）；无匹配行给卡内小字空态（Web .a-empty 口径）
                    if (!state.isLoading) {
                        Text(
                            text = EMPTY_AUTHORS,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = QimengDimens.SpaceL),
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(rows, key = { _, author -> author.id }) { index, author ->
                            // 行间分隔线（Web .a-list li border-bottom，末行无线）
                            if (index > 0) {
                                HorizontalDivider(
                                    thickness = QimengDimens.RankCardRowDividerThickness,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            }
                            AuthorRow(
                                author = author,
                                onToggleFollow = { viewModel.toggleFollow(author) },
                                onAuthorClick = onAuthorClick,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 作者行（G2 对齐 Web .a-list li 两行结构）：首行 = 作者名（·COS 标记，[displayLabel] 单源）
 * + 关注胶囊（G6 形状单源保持不变）；副行 = 「N 个文件」单计数（对齐 Web 现版运行时口径：
 * 浏览次数 2026-09-05 反馈⑤拍板不展示、F7 批统一「N 个文件」文案；null 计 0 同 Web `?? 0`）。
 * 整行可点进作者集合页（回调占位待 G1b）；关注胶囊在行内为子 clickable，
 * 点胶囊不触发行点击（Compose 子组件优先消费点击，等价 Web stopPropagation）。
 */
@Composable
private fun AuthorRow(
    author: AuthorSummary,
    onToggleFollow: () -> Unit,
    onAuthorClick: (authorId: String, displayName: String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onAuthorClick(author.id, author.displayName) }
            // Web .a-list li padding 8px 2px（纵/横段）
            .padding(horizontal = QimengDimens.SpaceXXS, vertical = QimengDimens.SpaceM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = author.displayLabel,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "${author.fileCount ?: 0} 个文件",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            // 胶囊圆角收敛单源 token（G6：原硬编码 RoundedCornerShape(100.dp)）
            shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
            color = if (author.followed) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.clickable(onClick = onToggleFollow),
        ) {
            Text(
                text = if (author.followed) "已关注" else "关注",
                style = MaterialTheme.typography.labelLarge,
                color = if (author.followed) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
                modifier = Modifier.padding(horizontal = QimengDimens.ChipHorizontalPadding, vertical = QimengDimens.SpaceS),
            )
        }
    }
}
