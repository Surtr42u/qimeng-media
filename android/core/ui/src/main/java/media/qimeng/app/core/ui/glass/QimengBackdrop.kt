package media.qimeng.app.core.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * Backdrop 库（io.github.kyant0:backdrop，包名 com.kyant.backdrop）的 :core:ui 封装单源
 * （2026-10-03 悬浮坞批评审必修 1）。
 *
 * ## 为什么需要这层包装（编译必炸根因）
 *
 * :core:ui 对库是 `implementation` 依赖（库类型不进消费方编译类路径），而壳层
 * QimengNavHost 此前直接 `import com.kyant.backdrop.*`（rememberLayerBackdrop/
 * layerBackdrop）且本包 FloatingTabDock 公共签名暴露库的 `Backdrop` 类型——:app 编译时
 * unresolved reference 必炸。修复口径：**库类型封装收口在 glass 包内**，壳层只见
 * [QimengBackdropState] 与下方两个包装 API；com.kyant 的 import 仅允许出现在本包
 * （FloatingTabDock.kt + 本文件），feature 与 :app 不得直引库 API。
 *
 * @see rememberQimengBackdropState 组合期工厂
 * @see Modifier.qimengBackdropSource 捕获层挂载
 */
class QimengBackdropState internal constructor(
    /**
     * 库实例本体。internal 可见度：本包包装 API 可解包传给库管线，消费方模块（:app/
     * feature）不可见不可构造——不用 private 是因为 Kotlin 的类私有构造只能由类自身
     * （含 companion）调用，本文件顶层 @Composable 工厂无法触达；internal 已足够挡住
     * 模块外消费方（封装边界=模块边界，与 implementation 依赖一致）。
     */
    internal val backdrop: LayerBackdrop,
)

/**
 * 组合期创建玻璃坞的 backdrop 采样源（库 `rememberLayerBackdrop` 的等价封装）：
 * base 色先 drawRect 再 drawContent()——内容稀疏的屏（如设置页顶部留白）模糊仍有色彩
 * 基底，不透黑（官方 Glass Bottom Bar 教程配方；2026-10-03 前壳层 QimengNavHost 同款
 * 用法逐字迁入，见 ADR-0033）。base 色取画布底色（主题 background，与 AuroraBackdrop
 * 的日夜基底同族）。
 *
 * remember 语义与库原函数一致：onDraw 捕获 [baseColor]（stable），主题切换改变 lambda
 * 身份 → LayerBackdrop 随 remember 键重建；包装对象本体再按 backdrop 记忆，避免每次
 * 重组新建包装实例。
 */
@Composable
fun rememberQimengBackdropState(baseColor: Color): QimengBackdropState {
    val backdrop = rememberLayerBackdrop {
        drawRect(baseColor)
        drawContent()
    }
    return remember(backdrop) { QimengBackdropState(backdrop) }
}

/**
 * 把内容层挂为 backdrop 捕获源（库 `Modifier.layerBackdrop` 的等价封装）：声明在该
 * modifier 链的子树内容会被记录进 [state]，坞体 drawBackdrop 采样之。
 * SOLID/CLASSIC 档不挂捕获（零采样开销），backdrop 对象创建无害。
 */
fun Modifier.qimengBackdropSource(state: QimengBackdropState): Modifier =
    this.layerBackdrop(state.backdrop)
