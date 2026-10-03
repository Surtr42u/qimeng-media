// :core:network —— make sdk 生成 SDK 的封装层 + OkHttp/AuthInterceptor（M4-1 落地）。
// M4-0 只立骨架与依赖接线：依赖 :sdk 与 okhttp，保证生成物在工程内可编译（:sdk 编译不过=停手信号）。
// convention 插件（build-logic，NIA 范式）提供：android library + Kotlin Android + Java/Kotlin 17 +
// compileSdk/minSdk + Hilt/KSP（含 hilt 依赖）。
plugins {
    alias(libs.plugins.qimeng.android.library)
    alias(libs.plugins.qimeng.android.hilt)
}

android {
    namespace = "media.qimeng.app.core.network"

    defaultConfig {
        // 本机模式端口的唯一注入点（消费方全部经 ServerAddress.LOCAL_MODE_PORT 派生，
        // 禁止在代码里再写字面端口）。2026-10-03 悬浮玻璃坞预览变体工程化（端口纪律同
        // ADR-0031 预览先例 18431 口径）：云端预览包与正式包并存装机，两个内嵌服务端
        // 同抢回环端口时后装者「bind: address already in use」起不来——预览错开 18432。
        // 同步责任：属性名与 android/app/build.gradle.kts 的 qmPreviewGlassDock 同名联动；
        // 正式构建（不传属性）=18430 与历史一致。跑单测/本地开发一律不带该属性
        // （单测锁定的是正式端口口径），属性仅供 CI 预览包构建。
        buildConfigField(
            "int",
            "QM_LOCAL_MODE_PORT",
            if (providers.gradleProperty("qmPreviewGlassDock").orNull?.toBoolean() == true) "18432" else "18430",
        )
    }

    buildFeatures {
        // 端口构建期注入所需（AGP 9 默认关闭库模块 BuildConfig 生成）
        buildConfig = true
    }
}

dependencies {
    // 生成的 Kotlin 客户端（media.qimeng.sdk）——唯一 API 面，禁止绕过直写 HTTP（ADR-0008/0014）。
    // api 而非 implementation：本模块定位即「SDK 封装层」，:core:data 的 Repository 需捕获
    // ClientException/ServerException 等生成物异常类型做错误分类（M4-2+ 映射 DTO 同理）
    api(project(":sdk"))
    // okhttp 版本与生成物锁定值一致（见 libs.versions.toml 注释）；AuthInterceptor 用
    implementation(libs.okhttp)
    // okhttp-sse（ADR-0029，2026-10-01）：@SseClient 长流客户端派生 + EventSource.Factory
    // 生产绑定（消费逻辑在 core:data，本模块只管通道装配）；同 family 同版本收口
    implementation(libs.okhttp.sse)
    // 服务端地址/token 持久化（ADR-0014 白名单；版本核查记录见 libs.versions.toml）
    implementation(libs.androidx.datastore.preferences)
    // 协程（ADR-0014 技术栈：Coroutine/Flow）——拦截器 401 清 token / AuthApi IO 调度
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
