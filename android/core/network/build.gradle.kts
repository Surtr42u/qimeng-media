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
