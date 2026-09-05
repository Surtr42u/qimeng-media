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
    // 生成的 Kotlin 客户端（media.qimeng.sdk）——唯一 API 面，禁止绕过直写 HTTP（ADR-0008/0014）。
    // api 而非 implementation：本模块定位即「SDK 封装层」，:core:data 的 Repository 需捕获
    // ClientException/ServerException 等生成物异常类型做错误分类（M4-2+ 映射 DTO 同理）
    api(project(":sdk"))
    // okhttp 版本与生成物锁定值一致（见 libs.versions.toml 注释）；AuthInterceptor 用
    implementation(libs.okhttp)
    // 服务端地址/token 持久化（ADR-0014 白名单；版本核查记录见 libs.versions.toml）
    implementation(libs.androidx.datastore.preferences)
    // 协程（ADR-0014 技术栈：Coroutine/Flow）——拦截器 401 清 token / AuthApi IO 调度
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
