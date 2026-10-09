package media.qimeng.app.core.ui.component

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 空白隔离：消费落在本节点范围内的点击，阻止触摸穿透到背后图层（媒体网格 / 视频舞台）。
 *
 * 动机（2026-10-09 详情页用户反馈）：悬浮控件（外部 tab 坞、详情页底部四胶囊条）的
 * 胶囊之间、两侧与内边距都是「不接任何手势的透明空白」——这些空隙上的误触会直接落到
 * 背后的可点区域（详情页＝视频海报的「点击起播」），观感就是「没点到胶囊却进了播放」。
 * 解法：空白区铺一层只吃点击的隔离层（本修饰符）；胶囊本体仍是隔离层所在节点的子节点，
 * 命中测试自上而下、上层先消费，故胶囊手势零影响。
 *
 * 实现口径：`detectTapGestures {}` 空实现＝只消费点击（Main pass），不拦拖动/长按等
 * 其它手势，也不产生水波纹与无障碍杂音。单源共用两处：悬浮 tab 坞的左右/底部隔离带
 * （`core:ui` glass/FloatingTabDock）与详情页底部胶囊条（`feature:detail`
 * DetailBottomChrome）——此前两处各自私有一份同名实现，本文件收口为唯一实现。
 *
 * 使用注意：只在「该控件确实可见」时挂载。控件隐藏（如沉浸态 chrome 退场）时若仍消费
 * 点击，会吞掉「点画面唤出控制条」这类本该穿透的原生交互。
 */
fun Modifier.consumeTaps(): Modifier = this.pointerInput(Unit) {
    detectTapGestures { }
}
