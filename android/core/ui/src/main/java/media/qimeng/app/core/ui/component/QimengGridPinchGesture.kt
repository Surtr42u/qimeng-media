package media.qimeng.app.core.ui.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 缩放步进倍率：累计缩放每跨越 1.25 倍（放大）或 1/1.25 倍（缩小）步进一列。
 * 为什么不用逐事件换算：触控缩放逐帧位移只有百分之几，直接换算成列数会长时间停在
 * 两档之间；累计跨阈值步进与旧版 ScaleGestureDetector 的手势节奏一致（拨一下动一格）。
 */
private const val PINCH_STEP_RATIO = 1.25f

/**
 * 双指缩放调列数手势（DOMAIN_RULES §8「网格列数用户可调，双指缩放即时生效」；
 * 旧版「全部」页 GridView 同款交互，M4-2A-B2）。挂在网格容器 Modifier 上使用：
 * `Modifier.qimengPinchToColumns(onStep = { viewModel.adjustColumnsLive(it) }, onGestureEnd = viewModel::commitPinchColumns)`。
 *
 * 语义约定：
 * - 仅按下指针 ≥2 才计算缩放（[calculateZoom] 前置守卫）——单指拖动零介入，滚动/翻页不受影响；
 * - 事件走 [PointerEventPass.Initial]：父层先于网格滚动看到事件，双指期间消费位移，
 *   防止缩放的同时列表乱滚（手势结束抬起一指后剩余单指自然回落为滚动）；
 * - 列数步进只经 [onStep] 上报（放大=减列、缩小=加列），clamp 与持久化由调用方状态层负责
 *   （组件禁业务规则——铁律 7）；[onGestureEnd] 在全部指针抬起时回调一次（无步进时调用方
 *   做幂等 no-op 即可）。
 */
fun Modifier.qimengPinchToColumns(
    onStep: (Int) -> Unit,
    onGestureEnd: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoomAccumulator = 1f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break // 全部抬起=手势结束
            if (pressed.size >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) {
                    zoomAccumulator *= zoom
                    if (zoomAccumulator >= PINCH_STEP_RATIO) {
                        onStep(-1) // 放大=看更少列（列变宽）
                        zoomAccumulator = 1f
                    } else if (zoomAccumulator <= 1f / PINCH_STEP_RATIO) {
                        onStep(1) // 缩小=看更多列
                        zoomAccumulator = 1f
                    }
                }
                // 双指期间吞掉位移：网格滚动与下拉刷新不得与缩放并发（旧版双指时列表静止）
                event.changes.forEach { it.consume() }
            }
        }
        onGestureEnd()
    }
}
