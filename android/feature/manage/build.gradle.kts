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
}

dependencies {
    // feature 只许依赖 core（单向依赖，ADR-0014）；core:ui 已 api 传递 :core:model
    implementation(project(":core:ui"))
    implementation(project(":core:data"))

    // U10-6b 例外记档：备份/作者 TXT 端口签名直用生成传输模型（LegacyBackupFile/
    // LegacyBackupImport/TxtImportResult，拍板接口），VM 与 BackupValidator 只做类型
    // 搬运/JSON 解析，不发网络（网络仍收口 core:data Repository，铁律 7 不破）。
    // feature→core 单向依赖于此处对 :sdk 的模型只读引用；后续若上提映射型应收回。
    implementation(project(":sdk"))

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
