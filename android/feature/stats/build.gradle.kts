// :feature:stats —— 数据统计页（M4-6；壳导航「数据」Tab 落点）。M4-0 空壳起步：只挂共享占位页，真页面随对应批次落地。
// feature 只许依赖 core（单向依赖，ADR-0014）；本模块依赖面 = core:ui + Compose M3 + Hilt（ViewModel 预接线）。
// convention 插件（build-logic，NIA 范式）提供：android library + Kotlin Android + Java/Kotlin 17 +
// compileSdk/minSdk + Compose（含 BOM platform）+ Hilt/KSP（含 hilt 依赖）。
plugins {
    alias(libs.plugins.qimeng.android.library)
    alias(libs.plugins.qimeng.android.compose)
    alias(libs.plugins.qimeng.android.hilt)
}

android {
    namespace = "media.qimeng.app.feature.stats"
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:data"))

    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
}
