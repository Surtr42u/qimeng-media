package media.qimeng.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import media.qimeng.app.core.ui.component.Dimens

/**
 * 「我的」Tab（M4-1 最小版）：标题 + 退出登录入口。
 * 完整设置页/我的页是 M4-6 批次（服务器地址展示/缓存档位/统计入口等），此处只交付会话闭环所需的最小登出。
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    Surface(modifier = modifier.fillMaxSize()) {
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
            Button(
                onClick = viewModel::logout,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.settings_logout))
            }
        }
    }
}
