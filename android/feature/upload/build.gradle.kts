import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :feature:upload —— 上传主通道（M4-5）：系统分享接收 + SAF 多选 + 选库/选目录 + 队列状态展示。
// feature 只许依赖 core（单向依赖，ADR-0014）；本模块依赖面 = core:ui + core:data（Repository 接口）。
// 上传字节流/WorkManager 全在 core 层——本模块只有 UI 编排与表单状态，零网络与队列实现（ADR-0008 铁律 7）。
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "media.qimeng.app.feature.upload"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // feature 只许依赖 core（单向依赖，ADR-0014）；core:ui 已 api 传递 :core:model
    implementation(project(":core:ui"))
    implementation(project(":core:data"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    // SAF 多选（OpenMultipleDocuments）与通知权限运行时申请（RequestPermission）的 Activity Result 装配
    implementation(libs.androidx.activity.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":core:testing"))
}
