package media.qimeng.app.feature.login

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.data.repository.LoginError
import media.qimeng.app.core.network.DefaultEndpoint
import media.qimeng.app.core.network.shouldRequestLocalNetworkPermission
import media.qimeng.app.core.ui.component.Dimens
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.glass.AuroraBackdrop
import media.qimeng.app.core.ui.glass.GlassSurface
import media.qimeng.app.core.ui.theme.QimengDimens
import media.qimeng.app.core.ui.theme.QimengShapes
import media.qimeng.app.core.ui.theme.qimengFilledButtonColors

/**
 * 登录页（M4-1 冻结最小版）：服务器地址 + 密码两字段。
 * 服务器地址支持 localhost/127.0.0.1 形式（ADR-0015 单机形态预留；规范化在 core 的
 * ServerAddress.normalize，UI 不做格式校验）。错误文案按 LoginError 分类映射中文资源。
 * 本页渲染在主壳 Scaffold 之外（QimengNavRoot 未登录分支），edge-to-edge 下无壳层
 * innerPadding 让位系统栏——根容器自行补系统栏三段 padding（G3；审查清偿：底部改
 * ime union navigationBars 取最大值——两类 insets 顺序叠加会在键盘弹出时多让出
 * 一个手势条高度）。
 *
 * 局域网权限门（任务P P4b，ADR-0022）：Android 17+ 对 targetSdk 37 强制
 * ACCESS_LOCAL_NETWORK（未授权连不了局域网 NAS），登录/键盘 Done 提交统一先过门——
 * 未授权先请求，授权即登录、拒绝不出网改出引导文案。判定口径单源
 * core:network [shouldRequestLocalNetworkPermission]。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoginScreen(
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val errorMessage = uiState.error?.let { stringResource(it.toMessageRes()) }
    val context = LocalContext.current
    var localNetworkGuidance by rememberSaveable { mutableStateOf(false) }

    val localNetworkLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.submit() else localNetworkGuidance = true
    }
    val submit: () -> Unit = {
        localNetworkGuidance = false
        if (shouldRequestLocalNetworkPermission(
                sdkInt = Build.VERSION.SDK_INT,
                granted = context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        ) {
            localNetworkLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            viewModel.submit()
        }
    }

    // ADR-0031 流光玻璃改版：极光氛围底 + 玻璃登录卡（视觉重做；提交门/端点选择/错误映射
    // 逻辑逐字未动）。原 Surface 实底根容器退役——登录页在主壳 Scaffold 之外，自持氛围底
    Box(modifier = modifier.fillMaxSize()) {
        AuroraBackdrop(modifier = Modifier.fillMaxSize())
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(all = Dimens.ScreenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 标题区：大字标题 + 一句话定位副标题（玻璃语言的「受光面」排印，Type.kt 标题族 SemiBold）
            Text(
                text = stringResource(R.string.login_title),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(TitleSpacing / 2))
            Text(
                text = stringResource(R.string.login_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(TitleSpacing))
            // 玻璃卡承载全部表单（ADR-0031 GlassSurface 单源；API<31 自动降级半透明+阴影）
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = QimengShapes.panel,
                elevation = 20.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(all = GlassCardPadding),
                    verticalArrangement = Arrangement.spacedBy(FieldSpacing),
                ) {
                    // 登录端两选项（第三百六十三笔，用户拍板：外部登录只留「服务器 / 本机」两个选项）；
                    // 服务器选项下挂地址输入（进页按记忆解析回填、可改），本机选项只读展示地址
                    EndpointRow(
                        label = stringResource(R.string.login_option_server),
                        caption = uiState.serverUrl,
                        selected = uiState.selected == DefaultEndpoint.NAS,
                        enabled = !uiState.isSubmitting,
                        onClick = { viewModel.onEndpointSelected(DefaultEndpoint.NAS) },
                    )
                    if (uiState.selected == DefaultEndpoint.NAS) {
                        // 胶囊输入框 label 走 placeholder 语义（G6：对齐 Web，组件不支持 label 浮动）；
                        // 冻结占位示例 http://192.168.x.x:8420（规范化在 core ServerAddress，UI 不校验格式）
                        QimengCapsuleTextField(
                            value = uiState.serverUrl,
                            onValueChange = viewModel::onServerUrlChange,
                            placeholder = stringResource(R.string.login_server_url_placeholder),
                            singleLine = true,
                            enabled = !uiState.isSubmitting,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    EndpointRow(
                        label = stringResource(R.string.login_option_local),
                        caption = uiState.localUrl,
                        selected = uiState.selected == DefaultEndpoint.LOCAL,
                        enabled = !uiState.isSubmitting,
                        onClick = { viewModel.onEndpointSelected(DefaultEndpoint.LOCAL) },
                    )
                    QimengCapsuleTextField(
                        value = uiState.password,
                        onValueChange = viewModel::onPasswordChange,
                        placeholder = stringResource(R.string.login_password_label),
                        singleLine = true,
                        enabled = !uiState.isSubmitting,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (errorMessage != null) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (localNetworkGuidance) {
                        // 拒绝局域网权限的定向引导（与既有 LoginError 错误行同款式；任务P P4b）
                        Text(
                            text = stringResource(R.string.login_error_local_network_denied),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = { submit() },
                        enabled = !uiState.isSubmitting,
                        // 提交中=禁用态大面积容器，夜间走不透明禁用底消 dither 横带（W6 #49）
                        colors = qimengFilledButtonColors(),
                        // ADR-0031：胶囊形 + 加高（52dp）提交钮——全 App 按钮统一胶囊语言
                        shape = RoundedCornerShape(QimengDimens.PillCornerRadius),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(SubmitButtonHeight),
                    ) {
                        if (uiState.isSubmitting) {
                            // V6：expressive LoadingIndicator 替换（仅控件替换，size 约束原样）
                            LoadingIndicator(modifier = Modifier.size(ButtonIndicatorSize))
                            Spacer(modifier = Modifier.width(IndicatorTextSpacing))
                        }
                        Text(
                            text = stringResource(R.string.login_submit),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

/** [LoginError] 分类 → 中文文案资源（资源 id 的映射是 UI 关注点，不属业务规则）。 */
private fun LoginError.toMessageRes(): Int = when (this) {
    LoginError.InvalidAddress -> R.string.login_error_invalid_address
    LoginError.ServerUnreachable -> R.string.login_error_server_unreachable
    LoginError.WrongPassword -> R.string.login_error_wrong_password
    LoginError.DevLoginUnavailable -> R.string.login_error_dev_unavailable
    is LoginError.Other -> R.string.login_error_other
}

/**
 * 登录端选项行（第三百六十三笔）：单选钮 + 端名 + 地址小字（选中态高亮整行可点）。
 * 视觉语义与设置页「默认登录」单选一致——同一概念在两页同款式。
 */
@Composable
private fun EndpointRow(
    label: String,
    caption: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = RowLabelSpacing)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (caption.isNotEmpty()) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val RowVerticalPadding = 8.dp
private val RowLabelSpacing = 4.dp

private val TitleSpacing = 24.dp
private val FieldSpacing = 12.dp

/** 玻璃登录卡内边距（ADR-0031：面板 20dp 档，与筛选面板同档） */
private val GlassCardPadding = 20.dp

/** 胶囊提交钮高度（ADR-0031：52dp 主行动档；筛选面板 48dp 次档） */
private val SubmitButtonHeight = 52.dp

/** 提交钮内指示器直径单源到 QimengDimens（V8 #5：16dp 强缩失衡 → 对齐 labelLarge 文字行高） */
private val ButtonIndicatorSize = QimengDimens.ButtonLoadingIndicatorSize
private val IndicatorTextSpacing = 8.dp
