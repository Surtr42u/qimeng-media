package media.qimeng.app.feature.detail

/**
 * 兄弟滑切沉浸语义交接单（任务W W7，#20 对标旧版「媒体层恒全屏」）。
 *
 * 为什么存在：兄弟横滑切件 = 路由 push 叠栈（QimengNavHost 详情路由无 launchSingleTop，
 * push 即新 DetailScreen 实例），目标屏 chromeVisible 初值恒 true（排版态）——沉浸态滑切后
 * 兄弟资产落排版态，与旧版「全屏态左右滑兄弟切换后仍全屏」不符（用户 2026-09-11 拍板
 * 对齐旧版语义）。本单是源屏 → 目标屏的一次性进程内信号：源屏在沉浸态（chromeVisible=false）
 * 触发滑切、且 VM.moveBy 解析出有效目标后置位；目标屏 DetailScreen 首次组合时消费，
 * 命中则 chromeVisible 以 false（沉浸）起步。海报态滑切不置位，目标落排版态不变。
 *
 * 为什么不用路由参数：immersive 是「源屏即时 UI 态」而非目标资产的路由身份——进参数要改
 * DetailScreen.onOpenAsset 契约 + 壳层 DetailRoutes 路由串 + 全部入口默认值，波及面远超
 * 本批最小改动原则；进程内单与信号无跨进程/跨会话语义（滑切与目标组合在同一主线程帧序内
 * 完成，详见消费点 DetailScreen 的时序注释），与 TabScrollController（core/ui 裸 object）、
 * FavoriteMutationTracker（core/data @Singleton）同款「纯进程内跨屏状态」先例。
 *
 * 时序保证（消费点为何安全）：详情互 push 时「新页先组合、旧页后 dispose」（Compose
 * applyChanges 次序，VideoStage D2 注有同款实证）——置位发生在 navigate 同步调用前，
 * 消费发生在目标屏首个 rememberSaveable 初值计算，二者之间无其他 DetailScreen 新实例
 * 组合窗口；消费即清零，孤儿置位（理论不发生：置位后必跟 navigate）不残留。
 */
internal object SiblingSwipeImmersionRequest {

    /** 一次性待消费标志（主线程读写；滑切手势与导航组合均在主线程，无须原子化） */
    private var pending = false

    /**
     * 源屏置位：沉浸态滑切且目标解析成功后、onOpenAsset 之前调用。
     * 重复置位幂等（连续快滑两次也是同一语义：目标沉浸）。
     */
    fun request() {
        pending = true
    }

    /**
     * 目标屏消费：首个新组合的 DetailScreen 读走并清零。返回 true = 本次进入应保持
     * 沉浸（chromeVisible 初值 false）。已保存实例状态恢复（返回 pop / 进程重建）不走
     * 本路径（rememberSaveable 有值时 initializer 不执行），不受残留标志影响。
     */
    fun consume(): Boolean {
        val consumed = pending
        pending = false
        return consumed
    }
}
