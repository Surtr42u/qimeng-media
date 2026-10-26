package media.qimeng.app.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.ui.component.QimengPageIntro
import media.qimeng.app.core.ui.component.QimengSettingsGroup
import media.qimeng.app.core.ui.component.QimengSettingsRow
import media.qimeng.app.core.ui.glass.TabDockDefaults
import media.qimeng.app.core.ui.icon.AuthorPeopleIcon
import media.qimeng.app.core.ui.icon.CloudUploadIcon
import media.qimeng.app.core.ui.icon.FavoriteBookmarkIcon
import media.qimeng.app.core.ui.icon.HistoryRecentIcon
import media.qimeng.app.core.ui.icon.InfoCircleIcon
import media.qimeng.app.core.ui.icon.PaletteColorIcon
import media.qimeng.app.core.ui.icon.ServerDnsIcon
import media.qimeng.app.core.ui.icon.TuneSlidersIcon
import media.qimeng.app.core.ui.theme.QimengDimens
import media.qimeng.app.core.ui.theme.softDaylightBackground

internal const val ROW_PREFS = "推荐偏好"

/** 我的只负责组织入口；数据及操作仍由原 ViewModel/导航持有。 */
@Composable
fun SettingsScreen(
    onOpenFavorite: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenAuthors: () -> Unit = {},
    onOpenDataManage: () -> Unit = {},
    onOpenServerDetail: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val appearanceMode by viewModel.appearanceMode.collectAsStateWithLifecycle()
    val tabBarMaterial by viewModel.tabBarMaterial.collectAsStateWithLifecycle()
    // 状态在分支外持有，外观页返回后恢复主列表位置。
    val listState = rememberLazyListState()
    var showThemePage by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    if (showThemePage) {
        BackHandler { showThemePage = false }
        AppearanceScreen(
            appearanceMode = appearanceMode,
            tabBarMaterial = tabBarMaterial,
            onAppearanceModeSelect = viewModel::setAppearanceMode,
            onTabMaterialSelect = viewModel::setTabBarMaterial,
            onBack = { showThemePage = false },
        )
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().softDaylightBackground(),
            contentPadding = PaddingValues(
                start = QimengDimens.ScreenPaddingHorizontal,
                end = QimengDimens.ScreenPaddingHorizontal,
                bottom = TabDockDefaults.bottomClearance(),
            ),
            verticalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM),
        ) {
            item(key = "overview") {
                Column(modifier = Modifier.padding(top = QimengDimens.SpaceL)) {
                    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold)
                    QimengPageIntro("你的收藏、浏览足迹与影库偏好")
                    Text("当前媒体库", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = QimengDimens.SpaceM))
                    Row(horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM)) {
                        CountCard(COUNT_CARD_IMAGE, state.imageCount, Modifier.weight(1f))
                        CountCard(COUNT_CARD_VIDEO, state.videoCount, Modifier.weight(1f))
                    }
                }
            }
            state.writeError?.let { message ->
                item(key = "write-error") { WriteErrorBanner(message, viewModel::dismissWriteError) }
            }
            item(key = "content") {
                QimengSettingsGroup("我的内容") {
                    QimengSettingsRow(FavoriteBookmarkIcon, "收藏", "留住喜欢的图片和视频", onOpenFavorite)
                    QimengSettingsRow(HistoryRecentIcon, "浏览历史", "接着查看最近打开的作品", onOpenHistory)
                    QimengSettingsRow(AuthorPeopleIcon, "作者总览", "发现作者，查看作品与关注", onOpenAuthors)
                }
            }
            item(key = "manage") {
                QimengSettingsGroup("影库与连接") {
                    QimengSettingsRow(CloudUploadIcon, "数据管理", "上传、媒体库、词表与备份", onOpenDataManage)
                    QimengSettingsRow(ServerDnsIcon, "服务器", "连接地址、本机模式与登录", onOpenServerDetail)
                }
            }
            item(key = "preferences") {
                QimengSettingsGroup("使用偏好") {
                    QimengSettingsRow(PaletteColorIcon, "外观", "主题与底部导航材质", { showThemePage = true })
                    QimengSettingsRow(TuneSlidersIcon, ROW_PREFS, "选择适合自己的推荐方式", viewModel::openPrefsSheet)
                }
            }
            item(key = "about") {
                QimengSettingsGroup("关于") {
                    QimengSettingsRow(InfoCircleIcon, "版本信息", "服务端 ${state.serverVersion ?: "未知"}", null)
                }
            }
            item(key = "logout") {
                OutlinedButton(
                    onClick = { confirmLogout = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().padding(vertical = QimengDimens.SpaceL),
                ) { Text(stringResource(R.string.settings_logout)) }
            }
        }
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出当前登录？") },
            text = { Text("退出后可重新连接服务器，已保存的服务器地址会保留。") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; viewModel.logout() }) { Text("退出登录") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
        )
    }
    if (state.prefsSheetOpen) {
        PrefsBottomSheet(
            appliedPreset = state.appliedPreset,
            applying = state.prefsApplying,
            loading = state.prefsLoading,
            loadFailed = state.prefsLoadFailed,
            writeError = state.writeError,
            onRetryLoad = viewModel::retryLoadPrefs,
            onApply = viewModel::applyPreset,
            onDismiss = viewModel::closePrefsSheet,
        )
    }
}
