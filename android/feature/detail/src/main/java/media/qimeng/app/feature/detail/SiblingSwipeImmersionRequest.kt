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
 *
 * handoff-ack（任务S S2 2026-09-13 落地，沿革：CHANGELOG 第二百一十笔 W7 留档）：滑切
 * push 详情→详情时，旧屏 SystemBarsImmersiveEffect.onDispose 的 show(systemBars) 在
 * applyChanges 阶段同步执行，先于新屏 LaunchedEffect 的 hide(systemBars)（协程后调度），
 * 系统栏闪现 1-2 帧亮色白条。因消费点（新屏组合期 initializer）次序上先于旧屏
 * onDispose，consume() 命中即置「交接在途」标记；旧屏 onDispose 经
 * [consumeHandoffAndClear] 查询命中则跳过 show()，让栏保持隐藏与新屏 hide() 幂等汇合，
 * 消除白条。标记读后即清：非滑切离场（详情→作者页 / pop 回列表）标记必为 false，
 * onDispose 照常恢复 show()，列表页不丢栏。
 *
 * 耦合声明（S2 对抗审查 CONCERN 记档 2026-09-13）：上述「消费先于 dispose」的前提隐式
 * 依赖壳层两个外部事实——QimengNavHost 详情转场 enter/exit/popEnter/popExit 全 None
 * （无转场动画后移 dispose）与 Z5 换件 popUpTo(inclusive=true) 栈深恒 1（任意时刻至多
 * 一个待 dispose 的 DetailScreen，无「两次 dispose 抢一个标记」）。若未来引入动画转场，
 * dispose 后移将拉宽标记残留窗口、快速连滑可复现误跳 show()——届时须先重审本单时序。
 */
internal object SiblingSwipeImmersionRequest {

    /** 一次性待消费标志（主线程读写；滑切手势与导航组合均在主线程，无须原子化） */
    private var pending = false

    /** 交接在途标志（S2 handoff-ack）：consume() 命中即置位，供旧屏 onDispose 查询后读后即清 */
    private var handoffInFlight = false

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
        // S2 handoff-ack：命中即标记「滑切交接在途」——组合期消费先于旧屏 onDispose，
        // 旧屏据此跳过 show() 避免白条；未命中不动标记（正常入口不产生交接）
        if (consumed) handoffInFlight = true
        return consumed
    }

    /**
     * 旧屏 onDispose 查询并读后即清（S2 handoff-ack）。返回 true = 本次离场是滑切交接
     * 在途：时序依据是新屏组合期 consume() 先于旧屏 onDispose 的 applyChanges，命中即
     * 滑切 push 详情→详情正在交接，旧屏应跳过 show() 让栏保持隐藏，与新屏 hide() 幂等
     * 汇合，消除 1-2 帧白条（W7 第二百一十笔留档）。
     *
     * 为什么读后即清：标记若残留，后续非滑切离场（详情→作者页→返回 / pop 回列表）的
     * onDispose 会误跳过 show()，导致列表页丢系统栏。
     */
    fun consumeHandoffAndClear(): Boolean {
        val inFlight = handoffInFlight
        handoffInFlight = false
        return inFlight
    }
}
