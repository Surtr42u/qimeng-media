import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core:data —— Repository 接口 + 实现（UI -> ViewModel -> Repository -> SDK 链路的中间层）。
// M4-0 空壳起步：首个 Repository（AuthRepository/ServerConfigDataSource）随 M4-1 落地。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "media.qimeng.app.core.data"
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
    api(project(":core:model"))
    implementation(project(":core:network"))

    // Flow/协程主源码直用（core:network 的同款依赖不传递到本模块编译类路径）
    implementation(libs.kotlinx.coroutines.android)

    // 客户端本地偏好（搜索历史/网格列数）：DataStore 白名单依赖，与 :core:network 共用同一版本收口
    implementation(libs.androidx.datastore.preferences)

    // 上传队列（M4-5）：WorkManager 白名单依赖 + @HiltWorker（androidx.hilt 同族接线）。
    // 官方来源与版本论证见 libs.versions.toml 的 work 版本注释。
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // 上传响应/错误体解析（M4-5）：moshi 反射（SDK 生成物同款 KotlinJsonAdapterFactory，单版本原则）
    implementation(libs.moshi.kotlin)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // 登录流程走真实生成 SDK + JDK HttpServer 打全链路（okhttp 仅为测试内构造客户端）
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp)
}
