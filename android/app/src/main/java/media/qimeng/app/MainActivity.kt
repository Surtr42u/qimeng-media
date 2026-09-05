package media.qimeng.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import media.qimeng.app.core.ui.theme.QimengTheme
import media.qimeng.app.navigation.QimengNavRoot

/**
 * 全 App 唯一 Activity（壳）：enableEdgeToEdge 交给 Scaffold/NavigationBar 处理 insets。
 * 登录态分支（M4-1）在 QimengNavRoot：未登录进登录页、已登录进五 Tab 主壳。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QimengTheme {
                QimengNavRoot()
            }
        }
    }
}
