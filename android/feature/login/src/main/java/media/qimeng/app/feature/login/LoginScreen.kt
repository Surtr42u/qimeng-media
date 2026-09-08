package media.qimeng.app.feature.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.data.repository.LoginError
import media.qimeng.app.core.ui.component.Dimens
import media.qimeng.app.core.ui.component.QimengCapsuleTextField

/**
 * 登录页（M4-1 冻结最小版）：服务器地址 + 密码两字段。
 * 服务器地址支持 localhost/127.0.0.1 形式（ADR-0015 单机形态预留；规范化在 core 的
 * ServerAddress.normalize，UI 不做格式校验）。错误文案按 LoginError 分类映射中文资源。
 * 本页渲染在主壳 Scaffold 之外（QimengNavRoot 未登录分支），edge-to-edge 下无壳层
 * innerPadding 让位系统栏——根容器自行补系统栏三段 padding（G3；审查清偿：底部改
 * ime union navigationBars 取最大值——两类 insets 顺序叠加会在键盘弹出时多让出
 * 一个手势条高度）。
 */
@Composable
fun LoginScreen(
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val errorMessage = uiState.error?.let { stringResource(it.toMessageRes()) }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(all = Dimens.ScreenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.login_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(TitleSpacing))
            // 胶囊输入框 label 走 placeholder 语义（G6：对齐 Web，组件不支持 label 浮动）
            QimengCapsuleTextField(
                value = uiState.serverUrl,
                onValueChange = viewModel::onServerUrlChange,
                placeholder = stringResource(R.string.login_server_url_placeholder),
                singleLine = true,
                enabled = !uiState.isSubmitting,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(FieldSpacing))
            QimengCapsuleTextField(
                value = uiState.password,
                onValueChange = viewModel::onPasswordChange,
                placeholder = stringResource(R.string.login_password_label),
                singleLine = true,
                enabled = !uiState.isSubmitting,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(FieldSpacing))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(FieldSpacing))
            Button(
                onClick = viewModel::submit,
                enabled = !uiState.isSubmitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ButtonIndicatorSize),
                        strokeWidth = ButtonIndicatorStrokeWidth,
                    )
                    Spacer(modifier = Modifier.width(IndicatorTextSpacing))
                }
                Text(text = stringResource(R.string.login_submit))
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

private val TitleSpacing = 24.dp
private val FieldSpacing = 12.dp
private val ButtonIndicatorSize = 16.dp
private val ButtonIndicatorStrokeWidth = 2.dp
private val IndicatorTextSpacing = 8.dp
