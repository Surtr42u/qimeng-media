package media.qimeng.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.network.ServerAddress
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.component.QimengTopBar
import media.qimeng.app.core.ui.theme.QimengDimens

// ---------- 页面文案（模块惯例：展示文案在代码常量、注释记档口径；端口字面量零新增——
// 本机模式预填值插值引用 core ServerAddress.LOCAL_MODE_PRESET 单源，故不可 const） ----------
private const val TITLE_SERVER = "服务器"
private const val SECTION_SERVER_URL = "服务器地址"
private const val PLACEHOLDER_SERVER_URL = "输入新的服务器地址"
private const val BUTTON_SAVE_AND_RELOGIN = "保存并重新登录"
private const val ERROR_INVALID_URL = "服务器地址格式不正确，请检查后重试"
private const val SECTION_LOCAL_MODE = "本机模式"
private const val BUTTON_SWITCH_LOCAL = "一键切换本机模式"

/** 本机模式卡副文案（原设置页 SUBTITLE_LOCAL_MODE 随 U10-4 迁此，行入口改卡内说明；插值预设常量） */
private val SUBTITLE_LOCAL_MODE =
    "服务端跑在本机时适用：退出登录并预填 ${ServerAddress.LOCAL_MODE_PRESET}，在登录页确认后生效"

/** 换址说明（原设置页 HINT_SERVER_URL 口径随 U10-4 迁此并按可改语义续写：保存即退出重登） */
private const val HINT_RELOGIN =
    "媒体库与账号都归这台服务端管；更换地址会退出当前登录，保存后在新地址上重新登录"

/**
 * 服务器设置子页（U10-4：原设置页「服务器地址」只展示卡与「本机模式」入口行合并为
 * 单入口子页，pushed 覆盖页；GUIDE_UI §设置页语义，交互零新增协议）：
 * ① 当前地址展示 + 修改（保存=规范化 → 登出并预置新地址，登录页带出确认后生效）；
 * ② 本机模式卡（预填值可改端口，「一键切换本机模式」走同一登出预置链路）；
 * ③ 换址需重新登录说明。
 * 视觉语言对齐设置页现有 16dp 圆角纯白卡规格；全走 [ServerSettingsViewModel]，UI 不直调 API。
 */
@Composable
fun ServerSettingsScreen(
    onBack: () -> Unit,
    viewModel: ServerSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 登出完成事件：壳层登录态流翻 LoggedOut 会整体切登录页（本页随主壳离树），
    // 这里只做切页前的兜底退栈（幂等；宿主已离树时 popBackStack 为 no-op）
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ServerSettingsEvent.LoggedOut -> onBack()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        QimengTopBar(title = TITLE_SERVER, onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = QimengDimens.ScreenPaddingHorizontal),
        ) {
            ServerUrlEditCard(
                currentUrl = state.currentUrl,
                urlInput = state.urlInput,
                saving = state.isSaving,
                invalid = state.urlInvalid,
                onUrlChange = viewModel::onUrlChange,
                onSave = viewModel::saveAndRelogin,
                modifier = Modifier.padding(top = QimengDimens.SpaceL, bottom = QimengDimens.SpaceL),
            )
            LocalModeCard(
                localUrlInput = state.localUrlInput,
                saving = state.isSaving,
                invalid = state.localUrlInvalid,
                onLocalUrlChange = viewModel::onLocalUrlChange,
                onSwitch = viewModel::switchToLocalMode,
                modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
            )
            Text(
                text = HINT_RELOGIN,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
            )
        }
    }
}

/**
 * 服务器地址卡：当前地址展示 + 新地址输入 +「保存并重新登录」。
 * 校验在 ViewModel（ServerAddress.normalize），这里只回显错误态；输入框禁用跟随提交中位。
 */
@Composable
private fun ServerUrlEditCard(
    currentUrl: String,
    urlInput: String,
    saving: Boolean,
    invalid: Boolean,
    onUrlChange: (String) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CardContainer(modifier = modifier) {
        Text(text = SECTION_SERVER_URL, style = MaterialTheme.typography.titleSmall)
        Text(
            text = currentUrl.ifBlank { "—" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        QimengCapsuleTextField(
            value = urlInput,
            onValueChange = onUrlChange,
            placeholder = PLACEHOLDER_SERVER_URL,
            singleLine = true,
            enabled = !saving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (invalid) {
            InvalidHint()
        }
        Button(
            onClick = onSave,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = BUTTON_SAVE_AND_RELOGIN)
        }
    }
}

/**
 * 本机模式卡：预设地址说明 + 预填输入框 +「一键切换本机模式」（原设置页入口行迁入）。
 */
@Composable
private fun LocalModeCard(
    localUrlInput: String,
    saving: Boolean,
    invalid: Boolean,
    onLocalUrlChange: (String) -> Unit,
    onSwitch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CardContainer(modifier = modifier) {
        Text(text = SECTION_LOCAL_MODE, style = MaterialTheme.typography.titleSmall)
        Text(
            text = SUBTITLE_LOCAL_MODE,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 预填值可改端口（U10-4 拍板）：语义仅「下次登录预填」——不在此持久化、不动 core
        // ServerAddress 常量（避免第二地址源）；真正生效仍在登录页确认后的既有持久化链路
        QimengCapsuleTextField(
            value = localUrlInput,
            onValueChange = onLocalUrlChange,
            singleLine = true,
            enabled = !saving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSwitch() }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (invalid) {
            InvalidHint()
        }
        TextButton(
            onClick = onSwitch,
            enabled = !saving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = BUTTON_SWITCH_LOCAL)
        }
    }
}

/** 卡容器：对齐设置页 16dp 圆角纯白 surface 卡 + 16/12 内边距 + 8dp 纵向节奏 */
@Composable
private fun CardContainer(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(QimengDimens.CardCornerRadius),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

/** 规范化失败提示（文案与登录页非法地址同口径） */
@Composable
private fun InvalidHint() {
    Text(
        text = ERROR_INVALID_URL,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
    )
}
