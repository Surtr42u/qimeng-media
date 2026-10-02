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
     *
     * 为什么放枚举体而非 companion（2026-10-03 CI 编译修复）：companion 作用域内 `this`
     * 绑定的是 Companion 对象，`this == LIQUID` 报「Companion 与 TabBarMaterial 不可比较」；
     * 移入枚举体后 `this` 绑定枚举值本身，调用点 `material.usesBackdrop`（QimengNavHost 两处）
     * 语义不变。
     */
    val usesBackdrop: Boolean
        get() = this == LIQUID || this == FROSTED

    companion object {
        /** 默认档：液态玻璃（未持久化过或持久化值非法时回落） */
        val DEFAULT: TabBarMaterial = LIQUID

        /** 持久化值（枚举名）→ 档位；未知值回落默认档 */
        fun fromStored(raw: String?): TabBarMaterial =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/**
 * 主题色彩预设六档（2026-10-03 主题色彩批）：AURORA 极光（默认=现行鸢尾系，观感零变化）/
 * TEAL 青碧（青绿主色）/ AMBER 琥珀（暖金主色）/ ROSE 绯樱（玫红主色）/ JADE 苍翠
 * （松石黄绿主色，与 TEAL 拉开色相距离：TEAL 偏青、JADE 偏黄绿）/ DYNAMIC 动态取色
 * （Material You 官方方案，API 31+ 生效，低于 31 由 Theme 装配层回落 AURORA 基座）。
 *
 * 预设只覆写强调色族（primary/secondary/tertiary 三族角色），中性面（背景/表面/文字）
 * 共用基座不随预设变（改面色风险大收益小，边界记档于 core:ui theme/Color.kt）。
 *
 * 枚举只放稳定 id，中文显示名由设置页 strings.xml 映射（与 AppearanceMode/TabBarMaterial
 * 同口径：文案改动不触碰持久化值）。
 */
enum class ThemeColorPreset {
    /** 极光（默认）：现行鸢尾主色系，升级用户观感零变化 */
    AURORA,

    /** 青碧：青绿主色系 */
    TEAL,

    /** 琥珀：暖金主色系 */
    AMBER,

    /** 绯樱：玫红主色系 */
    ROSE,

    /** 苍翠：松石黄绿主色系（与青碧拉开色相距离） */
    JADE,

    /** 动态取色：Material You 壁纸取色（API 31+；低版本装配层回落极光基座） */
    DYNAMIC,
    ;

    companion object {
        /** 默认档：极光（未持久化过或持久化值非法时回落，兼容升级老用户） */
        val DEFAULT: ThemeColorPreset = AURORA

        /** 持久化值（枚举名）→ 档位；未知值回落默认档（枚举增删/旧版本回滚安全） */
        fun fromStored(raw: String?): ThemeColorPreset =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}
