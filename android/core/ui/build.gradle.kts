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
    implementation(libs.compose.ui.tooling.preview)

    // Coil 3（拍板：动图缩略图动画；api 传递给 feature 与 :app——AsyncImage 与单例 ImageLoader 装配）
    api(libs.coil.core)
    api(libs.coil.compose)
    api(libs.coil.gif)
    api(libs.coil.network.okhttp)
}
