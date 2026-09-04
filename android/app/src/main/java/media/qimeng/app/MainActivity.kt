package media.qimeng.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import media.qimeng.app.core.ui.theme.QimengTheme
import media.qimeng.app.navigation.QimengNavHost

/**
 * 全 App 唯一 Activity（壳）：enableEdgeToEdge 交给 Scaffold/NavigationBar 处理 insets。
 * M4-0 只承载五 Tab 壳导航；登录页（M4-1）接入后导航起点在 QimengNavHost 内按登录态切换。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QimengTheme {
                QimengNavHost()
            }
        }
    }
}
