package media.qimeng.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
