package media.qimeng.app.feature.login

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import media.qimeng.app.core.data.repository.LoginError
import media.qimeng.app.core.network.DefaultEndpoint
import media.qimeng.app.core.network.shouldRequestLocalNetworkPermission
import media.qimeng.app.core.ui.component.QimengCapsuleTextField
import media.qimeng.app.core.ui.theme.QimengDimens
import media.qimeng.app.core.ui.theme.qimengFilledButtonColors
import media.qimeng.app.core.ui.theme.softDaylightShadow

/** 本机/NAS 双模式入口；网络动作经 ViewModel，发现与登录共用局域网权限门。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoginScreen(modifier: Modifier = Modifier, viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var guidance by rememberSaveable { mutableStateOf(false) }
    var submitAfterGrant by rememberSaveable { mutableStateOf(false) }
    var showManual by rememberSaveable { mutableStateOf(false) }
    fun needsPermission() = shouldRequestLocalNetworkPermission(
        sdkInt = Build.VERSION.SDK_INT,
        granted = context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED,
    )
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        guidance = !granted
        if (granted) {
            if (submitAfterGrant) viewModel.submit() else viewModel.discoverServers()
        }
        submitAfterGrant = false
    }
    val discover: () -> Unit = {
        guidance = false
        submitAfterGrant = false
        if (needsPermission()) launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        else viewModel.discoverServers()
    }
    val submit: () -> Unit = {
        guidance = false
        submitAfterGrant = true
        if (needsPermission()) launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        else viewModel.submit()
    }
    // 冷启动权限由MainActivity请求；自动发现等恢复到前台且已授权后再开始，
    // 避免与壳层同时弹两份权限请求。主动点NAS/重新寻找仍走页面权限门。
    LifecycleResumeEffect(state.selected) {
        if (state.selected == DefaultEndpoint.NAS && !needsPermission()) {
            guidance = false
            viewModel.discoverServers()
        }
        onPauseOrDispose { viewModel.stopDiscovery() }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.login_brand), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.login_heading), style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.login_intro), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModeButton(stringResource(R.string.login_option_local), stringResource(R.string.login_local_caption),
                        state.selected == DefaultEndpoint.LOCAL, !state.isSubmitting, Modifier.weight(1f)) {
                        viewModel.onEndpointSelected(DefaultEndpoint.LOCAL)
                    }
                    ModeButton(stringResource(R.string.login_option_server), stringResource(R.string.login_nas_caption),
                        state.selected == DefaultEndpoint.NAS, !state.isSubmitting, Modifier.weight(1f)) {
                        viewModel.onEndpointSelected(DefaultEndpoint.NAS)
                        discover()
                    }
                }
                Spacer(Modifier.height(20.dp))
                val shape = RoundedCornerShape(24.dp)
                Surface(modifier = Modifier.fillMaxWidth().softDaylightShadow(shape), shape = shape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (state.selected) {
                            DefaultEndpoint.LOCAL -> {
                                Text(stringResource(R.string.login_local_title), style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.login_local_description), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            DefaultEndpoint.NAS -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(if (state.isDiscovering) R.string.login_searching else R.string.login_nas_title),
                                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    if (state.isDiscovering) LoadingIndicator(Modifier.size(QimengDimens.ButtonLoadingIndicatorSize))
                                    else TextButton(onClick = discover, enabled = !state.isSubmitting) {
                                        Text(stringResource(R.string.login_refresh))
                                    }
                                }
                                Text(stringResource(R.string.login_search_hint), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val addresses = (state.discoveredServers + state.rememberedServers).distinct()
                                addresses.forEach { address ->
                                    AddressRow(address, address == state.serverUrl,
                                        address in state.discoveredServers, !state.isSubmitting) {
                                        viewModel.selectDiscoveredServer(address)
                                    }
                                }
                                if (state.discoveryFinished && state.discoveredServers.isEmpty()) {
                                    Text(stringResource(if (state.discoveryFailed) R.string.login_search_failed else R.string.login_search_empty),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { showManual = !showManual }, enabled = !state.isSubmitting) {
                                    Text(stringResource(if (showManual) R.string.login_manual_hide else R.string.login_manual))
                                }
                                if (showManual) QimengCapsuleTextField(
                                    value = state.serverUrl, onValueChange = viewModel::onServerUrlChange,
                                    placeholder = stringResource(R.string.login_server_url_placeholder), singleLine = true,
                                    enabled = !state.isSubmitting, modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                                )
                                // 兼容旧单地址记忆、预置与手填地址，不让选中目标藏在折叠区。
                                if (state.serverUrl.isNotEmpty() && state.serverUrl !in addresses && !showManual) {
                                    Text(state.serverUrl, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            null -> Text(stringResource(R.string.login_choose_mode), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
                QimengCapsuleTextField(
                    value = state.password, onValueChange = viewModel::onPasswordChange,
                    placeholder = stringResource(R.string.login_password_label), singleLine = true,
                    enabled = !state.isSubmitting, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }), modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(it.toMessageRes()), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium)
                }
                if (guidance) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.login_error_local_network_denied), color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(20.dp))
                Button(onClick = submit, enabled = !state.isSubmitting && state.selected != null && !state.isDiscovering,
                    colors = qimengFilledButtonColors(), modifier = Modifier.fillMaxWidth()) {
                    if (state.isSubmitting) LoadingIndicator(Modifier.size(QimengDimens.ButtonLoadingIndicatorSize))
                    Text(stringResource(if (state.isSubmitting) R.string.login_connecting else R.string.login_submit))
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.login_footer), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ModeButton(title: String, caption: String, selected: Boolean, enabled: Boolean,
    modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Surface(modifier = modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            Text(caption, style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddressRow(address: String, selected: Boolean, found: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(if (found) R.string.login_address_found else R.string.login_address_saved),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(address, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun LoginError.toMessageRes(): Int = when (this) {
    LoginError.InvalidAddress -> R.string.login_error_invalid_address
    LoginError.ServerUnreachable -> R.string.login_error_server_unreachable
    LoginError.WrongPassword -> R.string.login_error_wrong_password
    LoginError.DevLoginUnavailable -> R.string.login_error_dev_unavailable
    is LoginError.Other -> R.string.login_error_other
}
