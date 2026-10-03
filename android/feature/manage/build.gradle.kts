// :feature:manage —— 数据管理（U10-6）：我的页「数据管理」合并入口的二级页
// （DataManageScreen hub → 上传文件入口复用既有 Routes.UPLOAD 页 / LibraryManageScreen 库管理）。
// feature 只许依赖 core（单向依赖，ADR-0014）；本模块依赖面 = core:ui + core:data（Repository 接口），
// UI 零直调 SDK、业务在 ViewModel/core 层（ADR-0008 铁律 7）。
// convention 插件（build-logic，NIA 范式）提供：android library + Kotlin Android + Java/Kotlin 17 +
// compileSdk/minSdk + Compose（含 BOM platform）+ Hilt/KSP（含 hilt 依赖）。
plugins {
    alias(libs.plugins.qimeng.android.library)
    alias(libs.plugins.qimeng.android.compose)
    alias(libs.plugins.qimeng.android.hilt)
}

android {
    namespace = "media.qimeng.app.feature.manage"

    testOptions {
        // 2026-09-15 批：浏览数据同步卡迁入本模块（BackupViewModel 直跑 ViewEventQueue.drain），
        // 队列内的 android.util.Log 在 JVM android.jar stub 上默认抛「not mocked」，
        // 此处放行为返回默认值（feature:settings / core:data 同款先例，仅作用于本模块单元测试）。
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // feature 只许依赖 core（单向依赖，ADR-0014）；core:ui 已 api 传递 :core:model
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    // 缩略图缓存页「本地缓存」口径文案引用本机模式端口常量单源（ServerAddress.LOCAL_MODE_PORT；
    // feature→core 单向，ADR-0014；feature:login/settings/home 同款依赖先例）
    implementation(project(":core:network"))

    // 2026-10-03 撤 :sdk 直依赖批（U10-6b 例外记档随之退役）：备份/作者 TXT 的
    // SDK 模型引用与 Serializer 解析已下沉 core:data（BackupValidator 搬 backup 包、
    // BackupRepository/AuthorRepository TXT 族签名域类型化、LegacyImportSummary/
    // TxtImportSummary 消费投影在 core:model）——本模块零 :sdk 引用，铁律 7 不破。

    // U10-6b：作者 TXT/备份两子页的文件选择/落盘（ActivityResultContracts，
    // feature:settings 同款依赖；SAF 读写是屏幕层平台胶水，SettingsScreen 先例）
    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
}
