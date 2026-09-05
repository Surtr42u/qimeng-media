// :core:data —— Repository 接口 + 实现（UI -> ViewModel -> Repository -> SDK 链路的中间层）。
// M4-0 空壳起步：首个 Repository（AuthRepository/ServerConfigDataSource）随 M4-1 落地。
// convention 插件（build-logic，NIA 范式）提供：android library + Kotlin Android + Java/Kotlin 17 +
// compileSdk/minSdk + Hilt/KSP（含 hilt 依赖）。
plugins {
    alias(libs.plugins.qimeng.android.library)
    alias(libs.plugins.qimeng.android.hilt)
}

android {
    namespace = "media.qimeng.app.core.data"
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:network"))

    // Flow/协程主源码直用（core:network 的同款依赖不传递到本模块编译类路径）
    implementation(libs.kotlinx.coroutines.android)

    // 客户端本地偏好（搜索历史/网格列数/缓存档位）：DataStore 白名单依赖，与 :core:network 共用同一版本收口
    implementation(libs.androidx.datastore.preferences)

    // Coil ImageLoader 全局单例组装（M4-6 C5）：磁盘缓存档位读自本模块 DataStore，
    // 组装必须与档位仓库同模块（依赖方向：core:data -> core:network 单向，network 无法反向依赖本模块）。
    // GIF 解码器（coil-gif，同家族白名单）；coil-network-okhttp 取图器经 :core:ui 的 api 传递进
    // APK classpath 由 ServiceLoader 注册，此处无需声明。
    implementation(libs.coil.core)
    implementation(libs.coil.gif)

    // 上传队列（M4-5）：WorkManager 白名单依赖 + @HiltWorker（androidx.hilt 同族接线）。
    // 官方来源与版本论证见 libs.versions.toml 的 work 版本注释。
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // 上传响应/错误体解析（M4-5）：moshi 反射（SDK 生成物同款 KotlinJsonAdapterFactory，单版本原则）
    implementation(libs.moshi.kotlin)

    // 登录流程走真实生成 SDK + JDK HttpServer 打全链路（okhttp 仅为测试内构造客户端）
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
}
