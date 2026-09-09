package media.qimeng.app.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 舞台全出血有效补偿单源单测（任务K K3c D1 清偿，2026-09-10）：锁定沉浸态补偿
 * 「冻结在记忆的可见态 inset」口径——补偿值=详情页顶部背板色填充条高度（机制见
 * DetailScreen D1 重做注），若跟随实时值，沉浸期系统栏隐藏 → 实时 inset 归零 →
 * 垫条塌缩，壳层主题底色带露出（走查 k2-10b 实证 128px）。
 */
class StageEdgeToEdgeCompensationTest {

    /** 常见状态栏高度代表值（px） */
    private val visibleInset = 128

    @Test
    fun `系统栏可见 - 跟随实时值`() {
        assertEquals(
            visibleInset,
            stageEdgeToEdgeCompensationPx(
                liveInsetPx = visibleInset,
                rememberedVisibleInsetPx = visibleInset,
                barsVisible = true,
            ),
        )
        // 实时变化（分屏/字号）即时响应，不取陈旧记忆
        assertEquals(
            100,
            stageEdgeToEdgeCompensationPx(
                liveInsetPx = 100,
                rememberedVisibleInsetPx = visibleInset,
                barsVisible = true,
            ),
        )
        assertEquals(
            140,
            stageEdgeToEdgeCompensationPx(
                liveInsetPx = 140,
                rememberedVisibleInsetPx = visibleInset,
                barsVisible = true,
            ),
        )
    }

    @Test
    fun `沉浸态系统栏已隐藏 - 冻结在记忆的可见态值（D1 回归锁）`() {
        // 修复前行为：沉浸态实时 inset=0 → 补偿归零 → 舞台顶回落露出主题底色带。
        // 本断言锁定修复后口径：补偿恒为记忆值，舞台屏幕框两态不动
        assertEquals(
            visibleInset,
            stageEdgeToEdgeCompensationPx(
                liveInsetPx = 0,
                rememberedVisibleInsetPx = visibleInset,
                barsVisible = false,
            ),
        )
    }

    @Test
    fun `可见态但实时值瞬时归零 - 回退记忆值防塌缩闪跳`() {
        // show()/hide() 的 inset 派发存在 1~2 帧滞后，可见态首帧实时值可能仍为 0
        assertEquals(
            visibleInset,
            stageEdgeToEdgeCompensationPx(
                liveInsetPx = 0,
                rememberedVisibleInsetPx = visibleInset,
                barsVisible = true,
            ),
        )
    }

    @Test
    fun `无状态栏设备 - 记忆恒零补偿恒零（不产生负补偿）`() {
        // 真实零 inset 设备（如无状态栏形态）：可见态记忆恒 0，沉浸态回退仍 0
        assertEquals(
            0,
            stageEdgeToEdgeCompensationPx(
                liveInsetPx = 0,
                rememberedVisibleInsetPx = 0,
                barsVisible = false,
            ),
        )
    }
}
