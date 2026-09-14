package media.qimeng.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import media.qimeng.app.core.ui.theme.QimengTheme
import media.qimeng.app.navigation.QimengNavRoot
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestHighestRefreshRate()
        handleShareIntent(intent)
        setContent {
            QimengTheme {
                QimengNavRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
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
