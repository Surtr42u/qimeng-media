package media.qimeng.app.core.model

/**
 * 底栏材质档（2026-10-03 自悬浮玻璃坞批移植）：LIQUID=液态玻璃（默认，
 * RenderEffect/AGSL 折射）、FROSTED=磨砂玻璃（RenderEffect 模糊+高遮盖 scrim）、
 * SOLID=纯色 M3 官方坞（零捕获零模糊，性能兼容档）、CLASSIC=经典 M3
 * NavigationBar 悬浮化保底档。API 降级链（≥33 全量/31-32 去折射/≤30 伪玻璃）
 * 收口在 core:ui FloatingTabDock 的 resolveBackdropCapabilities 单点。
 *
 * 本分支（流光玻璃）壳层当前固定取 [DEFAULT]（LIQUID）；四档枚举保留完整形态，
 * 供后续合并主线「底栏材质四选」体系时直连。BACKDROP 库依赖为
 * io.github.kyant0:backdrop（Apache-2.0，类型封装收口 core:ui glass 包）。
 */
enum class TabBarMaterial {
    LIQUID,
    FROSTED,
    SOLID,
    CLASSIC;

    /** 是否需要背景捕获管线（玻璃两档）；纯色/经典档零捕获零模糊（性能档） */
    val usesBackdrop: Boolean
        get() = this == LIQUID || this == FROSTED

    companion object {
        /** 默认档（液态玻璃——本分支设计主角） */
        val DEFAULT: TabBarMaterial = LIQUID
    }
}
