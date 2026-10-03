package media.qimeng.app.core.model

/**
 * 外观模式（2026-10-03 悬浮玻璃坞批新增的「外观」手动开关三档）：
 * SYSTEM 跟随系统 / LIGHT 浅色 / DARK 深色。持久化于 DataStore（stable id = 枚举名），
 * 解析口径单源在 [AppearanceMode.resolveDarkTheme]——壳层 Theme 与玻璃件的日夜判定都从
 * 解析结果取，禁止一处 isSystemInDarkTheme 一处 DataStore 各判各的。
 *
 * 为什么放 :core:model：持久化端口（:core:data 仓库）与 UI 层（:core:ui 玻璃坞/:app 壳层/
 * feature:settings 设置页）三方都要类型化地消费它，core:model 是三层共享单源（core:ui 与
 * core:data 均 api(project(":core:model"))；core:data 依赖 core:ui 会违反分层）。
 */
enum class AppearanceMode {
    /** 跟随系统明暗 */
    SYSTEM,

    /** 手动锁定浅色 */
    LIGHT,

    /** 手动锁定深色 */
    DARK,
    ;

    companion object {
        /** 默认档：跟随系统（未持久化过或持久化值非法时回落，兼容升级老用户） */
        val DEFAULT: AppearanceMode = SYSTEM

        /** 持久化值（枚举名）→ 档位；未知值回落默认档（枚举增删/旧版本回滚安全） */
        fun fromStored(raw: String?): AppearanceMode =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/**
 * 底栏材质四档（2026-10-03 悬浮玻璃坞批）：LIQUID 液态玻璃（默认，真 backdrop 采样 +
 * vibrancy/blur/lens 全量特效）/ FROSTED 磨砂玻璃（真 blur，无 lens）/ SOLID 纯色坞
 * （M3 官方坞形态，零捕获零模糊的性能/兼容档）/ CLASSIC 经典（主线现行 M3
 * NavigationBar 逐字保底回退档）。
 *
 * 枚举只放稳定 id，中文显示名由设置页 strings.xml 映射（文案改动不触碰持久化值）。
 */
enum class TabBarMaterial {
    /** 液态玻璃：backdrop 全量特效（vibrancy + blur + lens，API 33+；低版本自动降级） */
    LIQUID,

    /** 磨砂玻璃：真模糊无折射（与 Web 端 Aurora Glass 的 blur(22px) 同源档） */
    FROSTED,

    /** 纯色坞：不透明 M3 表面色，零捕获零模糊 */
    SOLID,

    /** 经典：主线现行 M3 NavigationBar（保底回退档） */
    CLASSIC,
    ;

    /**
     * 该材质是否需要 backdrop 捕获与玻璃坞渲染：CLASSIC 直接渲染 M3 NavigationBar、
     * SOLID 是不透明纯色坞，两者零捕获零开销；LIQUID/FROSTED 才挂 layerBackdrop 捕获。
     * 实例属性（非 companion）——判定主体是单个材质档，this 必须指枚举实例。
     */
    val usesBackdrop: Boolean
        get() = this == LIQUID || this == FROSTED

    companion object {
        /** 默认档：液态玻璃（未持久化过或持久化值非法时回落） */
        // 2026-10-03 默认档改 SOLID（真机帧实测：玻璃采样档掉帧率 9.6–11.4%/p99 40–69ms，
        // 纯色档 7.4%/25ms 与零玻璃基线持平——坞外形保留，玻璃爱好者可在设置切回）
        val DEFAULT: TabBarMaterial = SOLID

        /** 持久化值（枚举名）→ 档位；未知值回落默认档 */
        fun fromStored(raw: String?): TabBarMaterial =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}
