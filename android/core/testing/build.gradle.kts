// :core:testing —— 共享测试工具（Now in Android 范式的 core:testing 模块）：Repository 测试替身 +
// 协程 Main 调度器规则。只进消费方的 testImplementation 类路径，绝不进生产代码。
// 纯 android library（无 compose/hilt）：fake 只操作 core:data 接口与协程原语，无需 Android 组件。
// convention 插件（build-logic，NIA 范式）提供：android library + Kotlin Android + Java/Kotlin 17 +
// compileSdk/minSdk。
plugins {
    alias(libs.plugins.qimeng.android.library)
}

android {
    namespace = "media.qimeng.app.core.testing"
}

dependencies {
    // api：消费方测试直接 import AuthRepository/LoginResult 与 junit/coroutines-test，不必重复声明
    api(project(":core:data"))
    // FakeAuthRepository 需实现 defaultEndpoint 成员（DefaultEndpoint 在 core:network；
    // core:data 用 implementation 引它不传递，故本模块显式声明）
    implementation(project(":core:network"))
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
    implementation(libs.kotlinx.coroutines.android)
}
