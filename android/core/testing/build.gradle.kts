import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core:testing —— 共享测试工具（Now in Android 范式的 core:testing 模块）：Repository 测试替身 +
// 协程 Main 调度器规则。只进消费方的 testImplementation 类路径，绝不进生产代码。
// 纯 android library（无 compose/hilt）：fake 只操作 core:data 接口与协程原语，无需 Android 组件。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "media.qimeng.app.core.testing"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // api：消费方测试直接 import AuthRepository/LoginResult 与 junit/coroutines-test，不必重复声明
    api(project(":core:data"))
    api(libs.junit)
    api(libs.kotlinx.coroutines.test)
    implementation(libs.kotlinx.coroutines.android)
}
