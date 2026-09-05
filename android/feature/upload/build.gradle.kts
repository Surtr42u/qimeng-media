// :feature:upload —— 上传主通道（M4-5）：系统分享接收 + SAF 多选 + 选库/选目录 + 队列状态展示。
// feature 只许依赖 core（单向依赖，ADR-0014）；本模块依赖面 = core:ui + core:data（Repository 接口）。
// 上传字节流/WorkManager 全在 core 层——本模块只有 UI 编排与表单状态，零网络与队列实现（ADR-0008 铁律 7）。
// convention 插件（build-logic，NIA 范式）提供：android library + Kotlin Android + Java/Kotlin 17 +
// compileSdk/minSdk + Compose（含 BOM platform）+ Hilt/KSP（含 hilt 依赖）。
plugins {
    alias(libs.plugins.qimeng.android.library)
    alias(libs.plugins.qimeng.android.compose)
    alias(libs.plugins.qimeng.android.hilt)
}

android {
    namespace = "media.qimeng.app.feature.upload"
}

dependencies {
    // feature 只许依赖 core（单向依赖，ADR-0014）；core:ui 已 api 传递 :core:model
    implementation(project(":core:ui"))
    implementation(project(":core:data"))

    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    // SAF 多选（OpenMultipleDocuments）与通知权限运行时申请（RequestPermission）的 Activity Result 装配
    implementation(libs.androidx.activity.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
}
