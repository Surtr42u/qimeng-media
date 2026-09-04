import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :app —— 组合根：单 Activity + Navigation Compose 壳导航 + Hilt 装配。
// 只做导航与装配，不含业务规则（ADR-0008）；页面实现全部在各 feature 模块。
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "media.qimeng.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "media.qimeng.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0" // M4-0 工程基建批次
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
    // core 全家（组合根可见全部模块；feature 只许依赖 core，见各 feature 的 build 文件）
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))

    implementation(project(":feature:home"))
    implementation(project(":feature:all"))
    implementation(project(":feature:album"))
    implementation(project(":feature:favorite"))
    implementation(project(":feature:history"))
    implementation(project(":feature:search"))
    implementation(project(":feature:author"))
    implementation(project(":feature:detail"))
    implementation(project(":feature:stats"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:upload"))

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
}
