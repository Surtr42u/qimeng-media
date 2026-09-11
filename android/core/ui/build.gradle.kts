// :core:ui —— 主题 token（品牌色对照 web prototype.css 换算）+ 共享组件 + 图标矢量。
// 依赖白名单内仅 Compose M3；禁止在此调 API/网络（ADR-0008 铁律 7）。
// convention 插件（build-logic，NIA 范式）提供：android library + Kotlin Android + Java/Kotlin 17 +
// compileSdk/minSdk + Compose（含 BOM platform）。
plugins {
    alias(libs.plugins.qimeng.android.library)
    alias(libs.plugins.qimeng.android.compose)
}

android {
    namespace = "media.qimeng.app.core.ui"
}

dependencies {
    // 网格卡片/分组段需要领域模型（core→core 单向依赖，feature 经此传递可见 :core:model）
    api(project(":core:model"))

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    // api 而非 implementation（现状陈述，任务W W2 清偿——原注释引用的已删件
    // motion/QimengSharedTransition.kt 见第一百九十八笔）：本件 QimengSegPill 用
    // animation.core（animateFloatAsState/tween），且 :app 壳层与 feature 各页的
    // AnimatedVisibility/fade 动画（QimengNavHost/DetailScreen/DetailChromeBars/
    // DetailSections 等 5 文件）均未自声明 animation 构件、经此 api 传递可见
    //（版本随 BOM，禁止单独升级）
    api(libs.compose.animation)
    implementation(libs.compose.ui.tooling.preview)

    // Coil 3（拍板：动图缩略图动画；api 传递给 feature 与 :app——AsyncImage 与单例 ImageLoader 装配）
    api(libs.coil.core)
    api(libs.coil.compose)
    api(libs.coil.gif)
    api(libs.coil.network.okhttp)

    // 格式化纯函数单测（QimengFormatTest；口径冻结对照 web format.ts，JVM 最快档）
    testImplementation(libs.junit)
}
