package media.qimeng.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.network.DefaultEndpoint
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

// ---------- 默认登录端（2026-09-20 用户拍板） ----------
private const val SECTION_DEFAULT_ENDPOINT = "默认登录"
private const val LABEL_ENDPOINT_NAS = "NAS 服务器"
private const val LABEL_ENDPOINT_LOCAL = "本机模式"
private const val HINT_DEFAULT_ENDPOINT =
    "登录页默认选中的端；实际登录成功后也会自动跟随你登的端"
private const val BADGE_ACTIVE_ENDPOINT = "正在使用"

// ---------- 仅充电时扫描（批C 任务Q C-3，仅本机模式渲染） ----------
private const val SECTION_CHARGE_ONLY_SCAN = "仅充电时扫描"
private const val SUBTITLE_CHARGE_ONLY_SCAN =
    "本机模式下扫描耗本机电量：未充电时点「重新扫描」会先记下，接入电源后自动开始"

/** 本机模式卡副文案（原设置页 SUBTITLE_LOCAL_MODE 随 U10-4 迁此，行入口改卡内说明；插值预设常量） */
private val SUBTITLE_LOCAL_MODE =
    "服务端跑在本机时适用：退出登录并预填 ${ServerAddress.LOCAL_MODE_PRESET}，在登录页确认后生效"

/** 换址说明（原设置页 HINT_SERVER_URL 口径随 U10-4 迁此并按可改语义续写：保存即退出重登） */
private const val HINT_RELOGIN =
    "媒体库与账号都归这台服务端管；更换地址会退出当前登录，保存后在新地址上重新登录"

/**
 * 地址固化说明（任务S 批S3 → 第三百六十三笔按新行为改写）：地址卡改的是「服务器」
 * 端的地址；保存后回登录页确认，登录成功才落记忆槽（本机连接不覆盖 NAS 槽）。
 */
private const val HINT_URL_MEMORY =
    "这里修改的是「服务器」的地址：保存后回到登录页确认；登录成功会自动记住，下次免填"

// ---------- 媒体库存储权限卡（2026-09-15 批）：内嵌服务端读注册媒体根唯一通道=「所有文件
// 访问」（ADR-0015 预留方案；手机实测 .nomedia 隐藏目录内 376 文件、无权限时服务端直读 0）。
// 清单声明 MANAGE_EXTERNAL_STORAGE 后系统开关才可拨，本卡=非技术用户的授权引导入口。 ----------
private const val SECTION_STORAGE_PERM = "媒体库存储权限"
private const val BUTTON_GRANT_STORAGE = "去系统设置授权"
private const val STORAGE_PERM_GRANTED = "已授权：本机服务端可读取注册媒体目录的文件"
private const val STORAGE_PERM_MISSING = "未授权：本机模式的媒体库会读不到文件，点下方按钮去系统设置打开「所有文件访问」"

/** 当前是否持「所有文件访问」（API 30+ 判定；更早版本无 scoped storage 强制，视为已授权） */
private fun hasAllFilesAccess(context: Context): Boolean =
    Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

/** 打开系统的「所有文件访问」授权页（部分 ROM 无 per-app 页时退回全量列表页） */
private fun openAllFilesAccessSettings(context: Context) {
    val perApp = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )
    runCatching { context.startActivity(perApp) }
        .recoverCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
}

/**
 * 服务器设置子页（U10-4：原设置页「服务器地址」只展示卡与「本机模式」入口行合并为
 * 单入口子页，pushed 覆盖页；GUIDE_UI §设置页语义，交互零新增协议）：
 * ① 当前地址展示 + 修改（保存=规范化 → 登出并预置新地址，登录页带出确认后生效）；
 * ② 本机模式卡（预填值可改端口，「一键切换本机模式」走同一登出预置链路）；
 * ③ 换址需重新登录说明；
 * ④ 媒体库存储权限卡（2026-09-15 批：单机形态注册媒体根的「所有文件访问」授权引导，
 *    授权页往返后 ON_RESUME 重读状态刷新卡片）。
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

    // U11 批次E reviewer P2-4：内嵌服务端的两态通知（运行中/已停止）在 API 33+ 依赖
    // POST_NOTIFICATIONS 运时授权，全仓库此前只有上传流申请过——本机模式启用是唯一
    // 入口，在这里补申请（拒绝不阻断切换：服务照跑，仅通知不可见，与上传流同口径）。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    val context = LocalContext.current
    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                onSwitch = {
                    requestNotificationPermissionIfNeeded()
                    viewModel.switchToLocalMode()
                },
                modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
            )
            // 默认登录端单选（2026-09-20 用户拍板；排序拍板=第三张卡：服务器→本机→默认）：
            // 登录页预选跟随本选项，直写持久化；行尾「正在使用」标记当前实际连接的端
            DefaultEndpointCard(
                selected = state.defaultEndpoint,
                activeEndpoint = state.activeEndpoint,
                onSelect = viewModel::onDefaultEndpointChange,
                modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
            )
            // 仅充电时扫描（批C 任务Q C-3）：仅本机模式渲染——扫描烧的是手机自己的电；
            // 连 NAS 的常规模式不受限（冻结语义），整行隐藏而非禁用，避免无效开关噪音
            if (state.isLocalMode) {
                ChargeOnlyScanCard(
                    enabled = state.chargeOnlyScanEnabled,
                    onEnabledChange = viewModel::onChargeOnlyScanChange,
                    modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
                )
            }
            StoragePermissionCard(modifier = Modifier.padding(bottom = QimengDimens.SpaceL))
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
        // 地址固化说明（批S3）：一行文案，无新设置项（任务书 §3 UI 冻结口径）
        Text(
            text = HINT_URL_MEMORY,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
 * 默认登录端卡（2026-09-20 用户拍板）：两个单选行，选中即直写持久化；null = 未选
 * （登录页保持「记忆上次」）。语义仅「下次登录预填哪个端」，不动当前连接。
 * 行尾「正在使用」小徽标（第三百六十四笔）标记当前实际连接的端——默认/预选与
 * 「正在用」是两个概念，视觉上分开避免再混淆。
 */
@Composable
private fun DefaultEndpointCard(
    selected: DefaultEndpoint?,
    activeEndpoint: DefaultEndpoint?,
    onSelect: (DefaultEndpoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    CardContainer(modifier = modifier) {
        Text(text = SECTION_DEFAULT_ENDPOINT, style = MaterialTheme.typography.titleSmall)
        Text(
            text = HINT_DEFAULT_ENDPOINT,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        EndpointRow(
            label = LABEL_ENDPOINT_NAS,
            inUse = activeEndpoint == DefaultEndpoint.NAS,
            selected = selected == DefaultEndpoint.NAS,
            onClick = { onSelect(DefaultEndpoint.NAS) },
        )
        EndpointRow(
            label = LABEL_ENDPOINT_LOCAL,
            inUse = activeEndpoint == DefaultEndpoint.LOCAL,
            selected = selected == DefaultEndpoint.LOCAL,
            onClick = { onSelect(DefaultEndpoint.LOCAL) },
        )
    }
}

/** 单选行：RadioButton + 标签整行可点（selectable 语义，无障碍 role 随单选钮）+「正在使用」徽标。 */
@Composable
private fun EndpointRow(
    label: String,
    inUse: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        if (inUse) {
            ActiveEndpointBadge(modifier = Modifier.padding(start = BadgeSpacing))
        }
    }
}

/** 「正在使用」小徽标（第三百六十四笔）：主色小胶囊，标在当前实际连接的端旁。 */
@Composable
private fun ActiveEndpointBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(percent = 50),
        modifier = modifier,
    ) {
        Text(
            text = BADGE_ACTIVE_ENDPOINT,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = BadgeHorizontalPadding, vertical = BadgeVerticalPadding),
        )
    }
}

private val BadgeSpacing = 8.dp
private val BadgeHorizontalPadding = 8.dp
private val BadgeVerticalPadding = 2.dp

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

/**
 * 仅充电时扫描卡（批C 任务Q C-3，PROJECT_PLAN M6 性能项）：本机模式下扫描烧手机自己的
 * 电，默认开启「未充电时不立即扫、记待扫标记，接通电源自动补扫」。仅本机模式渲染
 * （调用方条件已保证）；Switch 直写 DataStore（经 [ServerSettingsViewModel]），无协议交互。
 */
@Composable
private fun ChargeOnlyScanCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    CardContainer(modifier = modifier) {
        Text(text = SECTION_CHARGE_ONLY_SCAN, style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = SUBTITLE_CHARGE_ONLY_SCAN,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
    }
}

/**
 * 媒体库存储权限卡（2026-09-15 批）：展示「所有文件访问」授权态 + 未授权时给系统设置
 * 深链按钮。状态为 UI 平台胶水直读（Environment.isExternalStorageManager，非业务规则，
 * 铁律 7 不涉），不进 ViewModel——纯系统权限镜像，无协议交互。
 */
@Composable
private fun StoragePermissionCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 授权页往返后系统开关变化不触发本应用重组：ON_RESUME 重读一次刷新卡片
    var granted by remember { mutableStateOf(hasAllFilesAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = hasAllFilesAccess(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    CardContainer(modifier = modifier) {
        Text(text = SECTION_STORAGE_PERM, style = MaterialTheme.typography.titleSmall)
        Text(
            text = if (granted) STORAGE_PERM_GRANTED else STORAGE_PERM_MISSING,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!granted) {
            TextButton(
                onClick = { openAllFilesAccessSettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = BUTTON_GRANT_STORAGE)
            }
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
