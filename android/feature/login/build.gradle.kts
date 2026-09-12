// :feature:login —— 登录页（M4-1）：服务器地址 + 密码两字段，提交经 AuthRepository（探活→登录→持久化）。
// 登录成功后壳层由登录态流自动进主壳，本模块不持有导航职责（UI 禁内嵌业务规则，ADR-0008 铁律 7）。
// convention 插件（build-logic，NIA 范式）提供：android library + Kotlin Android + Java/Kotlin 17 +
// compileSdk/minSdk + Compose（含 BOM platform）+ Hilt/KSP（含 hilt 依赖）。
plugins {
    alias(libs.plugins.qimeng.android.library)
    alias(libs.plugins.qimeng.android.compose)
    alias(libs.plugins.qimeng.android.hilt)
}

android {
    namespace = "media.qimeng.app.feature.login"
}

dependencies {
    // feature 只许依赖 core（单向依赖，ADR-0014）：core:ui 主题/尺寸 token、core:data 的 AuthRepository、
    // core:network 的 ServerAddress（T3 本机模式预设常量单源；normalize 仍在 AuthRepository 内消费）
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:network"))

    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)

    // collectAsStateWithLifecycle（runtime-compose）+ hiltViewModel()（androidx hilt navigation 扩展）
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)

    // ViewModel 状态机单测：共享替身与协程规则统一来自 :core:testing
    testImplementation(project(":core:testing"))
}
