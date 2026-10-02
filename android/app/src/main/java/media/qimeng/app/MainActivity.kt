package media.qimeng.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import media.qimeng.app.core.model.AppearanceMode
import media.qimeng.app.core.network.shouldRequestLocalNetworkPermission
import media.qimeng.app.core.ui.theme.QimengTheme
import media.qimeng.app.navigation.QimengNavRoot
import media.qimeng.app.session.AppearanceViewModel
import media.qimeng.app.session.MainViewModel

/**
 * 全 App 唯一 Activity（壳）：enableEdgeToEdge 交给 Scaffold/NavigationBar 处理 insets。
 * 登录态分支（M4-1）在 QimengNavRoot：未登录进登录页、已登录进四 Tab 主壳。
 *
 * 系统分享接收（M4-5 上传入口①）：SEND/SEND_MULTIPLE 的流 URI 交 [MainViewModel] 暂存，
 * 壳层导航观察到未消费的分享即进「选库/选目录」上传流。launchMode=singleTask 保证
 * 冷启动走 onCreate、热任务复用走 onNewIntent，两条路都汇入同一处理函数。
 * 这里只做 intent 解包，不做 MIME 白名单过滤——类型校验唯一口径在服务端四道检查（M4-5 冻结）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    /** 外观偏好（2026-10-03 悬浮玻璃坞批）：外观模式/底栏材质的壳层单源（Activity 作用域，
     *  QimengNavHost 与设置页经 hiltViewModel() 解析到同一实例） */
    private val appearanceViewModel: AppearanceViewModel by viewModels()

    /** 局域网权限请求回调（任务P P4b）：拒绝不做动作——登录页提交门有定向引导，
     *  已登录老用户由既有失败态/下次冷启动兜底（系统对永久拒绝不再弹窗，无打扰循环）。 */
    private val localNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestHighestRefreshRate()
        maybeRequestLocalNetworkPermission()
        handleShareIntent(intent)
        setContent {
            // 外观模式三档（2026-10-03 悬浮玻璃坞批）：跟随系统/浅色/深色，持久化于
            // DataStore（AppearanceViewModel，Activity 作用域单源——QimengNavHost 的坞材质
            // 与设置页选择器同读一份流）。暗色布尔在此解析后传给 Theme，AuroraBackdrop/
            // 玻璃件经 isQimengDarkTheme()（colorScheme 亮度判定）与本次解析同源，全 App
            // 只此一处决定暗色分支。
            val appearanceMode by appearanceViewModel.appearanceMode.collectAsStateWithLifecycle()
            val darkTheme = when (appearanceMode) {
                AppearanceMode.SYSTEM -> isSystemInDarkTheme()
                AppearanceMode.LIGHT -> false
                AppearanceMode.DARK -> true
            }
            QimengTheme(darkTheme = darkTheme) {
                QimengNavRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // 前台回归自检（2026-09-25 冻结事故）：本机模式下内嵌服务端子进程可能随壳进程
        // 一起被系统冻结成「活着但调度停摆」，回前台必须探测一次 /healthz，无响应自动
        // 重拉——否则用户面对的就是详情页/全 App 加载失败，只能手动重启 App
        mainViewModel.onAppForeground()
    }

    /**
     * 局域网权限冷启动补请求（任务P P4b，ADR-0022；判定单源 core:network）：Android 17+
     * 对 targetSdk 37 强制该权限（未授权连不了局域网 NAS）。放壳层冷启动是因为已登录
     * 老用户升级装包后不再经过登录页，首次冷启动即补一次系统询问；未授权回调不做动作，
     * 未登录者到登录页提交门有定向引导、已登录者走既有「加载失败」态，下次冷启动再补。
     */
    private fun maybeRequestLocalNetworkPermission() {
        if (shouldRequestLocalNetworkPermission(
                sdkInt = Build.VERSION.SDK_INT,
                granted = checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        ) {
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }

    /**
     * 请求设备最高刷新率（任务Y-Y5，用户问题5「帧率同步手机实际帧率」）。
     *
     * 为什么需要：高刷屏设备（如用户真机努比亚 NX721J）系统可能为省电把 App 默认锁在
     * 60Hz，不显式声明就永远跑不到面板峰值；App 侧「帧率同步显示器/手机实际帧率」的
     * 官方口径即 [android.view.WindowManager.LayoutParams.preferredDisplayModeId] +
     * [Display.getSupportedModes]（API 23+，minSdk 26 无需 guard；modeId 随设备变化，
     * 禁止硬编码，必须运行时从模式表取）。出处：
     * developer.android.com/develop/ui/views/layout/improving-frame-rates
     *
     * 策略：只在「与当前 mode 同分辨率」的候选里取 refreshRate 最高者（避免切到分辨率
     * 不同的模式导致画质/密度抖动）；已处于最高刷或模式表为空则不动。
     * 全程防御式判空，任何一步拿不到都直接返回，绝不阻断启动。
     */
    private fun requestHighestRefreshRate() {
        val display: Display? =
            if (Build.VERSION.SDK_INT >= 30) {
                display // Context.getDisplay（API 30+；onCreate 时 Activity 必已附着）
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay // API 26-29 兜底
            }
        val current = display?.mode ?: return
        val best =
            display.supportedModes
                .filter {
                    it.physicalWidth == current.physicalWidth &&
                        it.physicalHeight == current.physicalHeight
                }
                .maxByOrNull { it.refreshRate }
                ?: return
        if (best.modeId == current.modeId) return // 已处于同分辨率最高刷，不重设免多余 relayout
        val attrs = window.attributes
        attrs.preferredDisplayModeId = best.modeId
        window.attributes = attrs // 回写触发 ViewRootImpl 应用新模式
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = resolveStreamExtra(intent)
                if (uri != null) mainViewModel.receiveSharedUris(listOf(uri.toString()))
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = resolveStreamListExtra(intent)
                if (!uris.isNullOrEmpty()) mainViewModel.receiveSharedUris(uris.map { it.toString() })
            }
        }
    }

    private fun resolveStreamExtra(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun resolveStreamListExtra(intent: Intent): ArrayList<Uri>? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        }
}
