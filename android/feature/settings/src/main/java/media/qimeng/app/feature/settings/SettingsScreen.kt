package media.qimeng.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import media.qimeng.app.core.ui.component.Dimens

/** 我的页入口行文案（GUIDE_UI §我的页：收藏/浏览历史/作者管理 三行；M4-6 完整我的页前的最小入口） */
private const val ROW_FAVORITE = "收藏"
private const val ROW_HISTORY = "浏览历史"
private const val ROW_AUTHORS = "作者管理"

/**
 * 「我的」Tab（M4-2 最小版）：标题 + 覆盖页入口三行（收藏/浏览历史/作者管理——M4-2 列表族
 * 的可达入口）+ 退出登录。完整设置页/我的页是 M4-6 批次（服务器地址展示/缓存档位/统计入口等）。
 */
@Composable
fun SettingsScreen(
    onOpenFavorite: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenAuthors: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(all = Dimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.ScreenPadding),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            EntryRow(label = ROW_FAVORITE, onClick = onOpenFavorite)
            EntryRow(label = ROW_HISTORY, onClick = onOpenHistory)
            EntryRow(label = ROW_AUTHORS, onClick = onOpenAuthors)
            Button(
                onClick = viewModel::logout,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.settings_logout))
            }
        }
    }
}

/** 入口行（浅面底 + 点击跳覆盖页；行间距由外层 spacedBy 统一调度） */
@Composable
private fun EntryRow(label: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
