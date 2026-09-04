import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core:network —— make sdk 生成 SDK 的封装层 + OkHttp/AuthInterceptor（M4-1 落地）。
// M4-0 只立骨架与依赖接线：依赖 :sdk 与 okhttp，保证生成物在工程内可编译（:sdk 编译不过=停手信号）。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "media.qimeng.app.core.network"
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
    // 生成的 Kotlin 客户端（media.qimeng.sdk）——唯一 API 面，禁止绕过直写 HTTP（ADR-0008/0014）
    implementation(project(":sdk"))
    // okhttp 版本与生成物锁定值一致（见 libs.versions.toml 注释）；AuthInterceptor(M4-1) 用
    implementation(libs.okhttp)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
