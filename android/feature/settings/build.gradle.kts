// :feature:settings —— 设置页与我的页（壳导航「我的」Tab 落点）。
// M4-1 最小版：标题 + 退出登录入口（会话闭环）；完整设置页/我的页随 M4-6 落地。
// feature 只许依赖 core（单向依赖，ADR-0014）。
// convention 插件（build-logic，NIA 范式）提供：android library + Kotlin Android + Java/Kotlin 17 +
// compileSdk/minSdk + Compose（含 BOM platform）+ Hilt/KSP（含 hilt 依赖）。
plugins {
    alias(libs.plugins.qimeng.android.library)
    alias(libs.plugins.qimeng.android.compose)
    alias(libs.plugins.qimeng.android.hilt)
}

android {
    namespace = "media.qimeng.app.feature.settings"

    testOptions {
        // 任务L L5：SettingsViewModel 的「立即同步」用例直跑 ViewEventQueue.drain，
        // 队列内的 android.util.Log 在 JVM android.jar stub 上默认抛「not mocked」，
        // 此处放行为返回默认值（core/data 同款先例，仅作用于本模块单元测试）。
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:data"))

    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
}
