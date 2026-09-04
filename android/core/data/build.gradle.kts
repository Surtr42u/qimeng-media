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

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
