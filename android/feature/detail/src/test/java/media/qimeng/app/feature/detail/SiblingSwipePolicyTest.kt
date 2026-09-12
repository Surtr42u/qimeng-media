package media.qimeng.app.feature.detail

import media.qimeng.app.feature.detail.image.isHorizontalDominantDrag
import media.qimeng.app.feature.detail.image.swipeDeltaFromDrag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 兄弟切件横滑判定纯函数单测（任务V V2 起，任务Z Z1 增补）：锁定「距离 或 速度 任一达阈值
 * 即切」的 ViewPager 语义（V1「距离 且 速度」双与门挡死慢拖，用户实测慢拖不切件后拍板放宽）、
 * swipeDeltaFromDrag 的「距离达阈值且横向占优」口径与 isHorizontalDominantDrag 的「横向主导
 * 即请求父层不拦截」口径（Z1 根修：海报态斜滑被 verticalScroll 竖向 slop 拦截 CANCEL 致不切件）。
 * 被测函数均为文件级 internal 纯函数、无 Android/Compose 依赖（先例 StageBackdropTest：JVM
 * 单测直调纯函数锁行为）。
 */
class SiblingSwipePolicyTest {

    // ---------- posterSwipeDelta（海报态：方向取拖动符号，dragX<0=左滑=+1） ----------

    @Test
    fun `海报慢拖距离达阈值速度不足 - 切（V1 双与门的回归锁）`() {
        // 慢拖：位移 100px 达 80px 阈值、收尾速度 100 远低于 800——V1 不切（缺陷），
        // V2 必须切，本断言即用户拍板的回归锁
        assertEquals(1, posterSwipeDelta(dragX = -100f, velocityX = 100f, swipeDistancePx = 80f))
    }

    @Test
    fun `海报快甩速度达阈值距离不足 - 切`() {
        assertEquals(1, posterSwipeDelta(dragX = -10f, velocityX = 900f, swipeDistancePx = 80f))
    }

    @Test
    fun `海报双阈值均不达 - 不切`() {
        assertNull(posterSwipeDelta(dragX = -10f, velocityX = 100f, swipeDistancePx = 80f))
    }

    @Test
    fun `海报方向 - 左滑加一右滑减一`() {
        assertEquals(1, posterSwipeDelta(dragX = -100f, velocityX = 0f, swipeDistancePx = 80f))
        assertEquals(-1, posterSwipeDelta(dragX = 100f, velocityX = 0f, swipeDistancePx = 80f))
    }

    // ---------- swipeDeltaFromDrag（图片未放大态：accumX>0=左滑=+1，与 onFling 口径一致） ----------

    @Test
    fun `图片慢拖横向位移达阈值 - 切下一件`() {
        assertEquals(1, swipeDeltaFromDrag(accumX = 100f, accumY = 10f, swipeDistancePx = 80f))
    }

    @Test
    fun `图片纵向位移占优 - 不切`() {
        // 横向达阈值但纵向更大（斜划/上下滚动收尾），不构成切件意图
        assertNull(swipeDeltaFromDrag(accumX = 100f, accumY = 200f, swipeDistancePx = 80f))
    }

    @Test
    fun `图片距离阈值不达 - 不切`() {
        assertNull(swipeDeltaFromDrag(accumX = 50f, accumY = 10f, swipeDistancePx = 80f))
    }

    @Test
    fun `图片方向 - 左滑加一右滑减一`() {
        // onScroll distanceX 口径：手指左移 accumX>0 → +1；右移 accumX<0 → -1
        assertEquals(1, swipeDeltaFromDrag(accumX = 100f, accumY = 0f, swipeDistancePx = 80f))
        assertEquals(-1, swipeDeltaFromDrag(accumX = -100f, accumY = 0f, swipeDistancePx = 80f))
    }

    // ---------- isHorizontalDominantDrag（图片未放大态：横向主导 → 请求父层不拦截） ----------

    @Test
    fun `横向占优且过门槛 - 请求不拦截`() {
        // 斜滑（dy=500 类实验手势，dx 更大）必须首个 onScroll 即锁定手势归属
        assertTrue(isHorizontalDominantDrag(accumX = 30f, accumY = 20f, requestThresholdPx = 11f))
        // 方向对称：左右滑都该请求
        assertTrue(isHorizontalDominantDrag(accumX = -30f, accumY = 20f, requestThresholdPx = 11f))
    }

    @Test
    fun `纵向占优 - 不请求（W 拍板八海报态可小幅下滑）`() {
        // 下滑带横向抖动：竖向主导，交还父层滚动
        assertFalse(isHorizontalDominantDrag(accumX = 20f, accumY = 30f, requestThresholdPx = 11f))
        // 纯下滑
        assertFalse(isHorizontalDominantDrag(accumX = 0f, accumY = 100f, requestThresholdPx = 11f))
    }

    @Test
    fun `横向未过门槛 - 不请求`() {
        // 未过门槛（哪怕横向占优）：门槛低于系统 touch slop 的取值论证见
        // HORIZONTAL_DOMINANT_REQUEST_DP 常量注，此处锁行为
        assertFalse(isHorizontalDominantDrag(accumX = 10f, accumY = 5f, requestThresholdPx = 11f))
    }

    @Test
    fun `门槛取等 - 不请求（严格大于）`() {
        // |accumX| == threshold 不触发（与 60dp 切件阈值同口径：严格大于）
        assertFalse(isHorizontalDominantDrag(accumX = 11f, accumY = 0f, requestThresholdPx = 11f))
    }
}
