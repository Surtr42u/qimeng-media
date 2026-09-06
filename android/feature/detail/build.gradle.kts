// :feature:detail —— 详情页（M4-3）：3a 骨架与排版（Web B站式单列移植+互动+标签+作者+接下来播放），
// 3b 手势/播放器/沉浸、3c 时间轴标签、3d 徽标后续子批落地。
// feature 只许依赖 core（单向依赖，ADR-0014）；本模块依赖面 = core:ui + core:data + core:model（经 core:ui 传递）。
// convention 插件（build-logic，NIA 范式）提供：android library + Kotlin Android + Java/Kotlin 17 +
// compileSdk/minSdk + Compose（含 BOM platform）+ Hilt/KSP（含 hilt 依赖）；lifecycle/hilt-navigation
// 依赖显式声明与 feature/home 依赖块对齐。
plugins {
    alias(libs.plugins.qimeng.android.library)
    alias(libs.plugins.qimeng.android.compose)
    alias(libs.plugins.qimeng.android.hilt)
}

android {
    namespace = "media.qimeng.app.feature.detail"
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:data"))

    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // Media3（M4-3 3c，白名单 ADR-0014）：ExoPlayer 内核 + PlayerView（BiliPlayerView 桥接宿主）
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
}
