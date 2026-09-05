package media.qimeng.app.core.ui.component

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 双击 Tab 回顶总线（GUIDE_UI §导航结构：400ms 内同一 Tab 二击精确回顶）。
 * 计时与判定在壳层（QimengNavHost），列表页收集本流做 scrollToItem(0)——
 * 精确回顶（旧版 scrollToPositionWithOffset(0,0) 语义，非平滑滚动）。
 */
object TabScrollController {

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** 键 = Tab 路由字符串；列表页按自身路由过滤 */
    val events: SharedFlow<String> = _events

    fun requestScrollToTop(route: String) {
        _events.tryEmit(route)
    }
}
