import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :feature:upload —— 上传主通道：分享接收/SAF/队列（M4-5）。M4-0 空壳起步：只挂共享占位页，真页面随对应批次落地。
// feature 只许依赖 core（单向依赖，ADR-0014）；本模块依赖面 = core:ui + Compose M3 + Hilt（ViewModel 预接线）。
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
    implementation(project(":core:ui"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
