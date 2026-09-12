// :app —— 组合根：单 Activity + Navigation Compose 壳导航 + Hilt 装配。
// 只做导航与装配，不含业务规则（ADR-0008）；页面实现全部在各 feature 模块。
// convention 插件（build-logic，NIA 范式）提供：application + Kotlin Android + Java/Kotlin 17 +
// compileSdk/minSdk + Compose（含 BOM platform）+ Hilt/KSP（含 hilt 依赖）。模块差异留在下方。
plugins {
    alias(libs.plugins.qimeng.android.application)
    alias(libs.plugins.qimeng.android.compose)
    alias(libs.plugins.qimeng.android.hilt)
}

android {
    namespace = "media.qimeng.app"

    defaultConfig {
        applicationId = "media.qimeng.app"
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0" // M4-2 列表族批次
    }

    buildTypes {
        release {
            // 本地实测用非发布签名：复用 debug 签名（convention 未配 signingConfig，
            // AGP 隐式 release=无签名包不可装机；正式对外发布前需另配 release 签名档）。
            signingConfig = signingConfigs.getByName("debug")
            // 任务R R2：开启 R8 混淆+资源收缩——目标包体（R8 去无用代码 + shrinkResources
            // 去无用资源）与冷启动（随本批 baseline-prof.txt 编译进 APK，ART 提前 AOT）。
            // 任务Y-Y5 的 keep 缺口本批已补：moshi 反射序列化按包 keep（proguard-rules.pro，
            // sdk.models 包 + UploadApiBodies 嵌套 DTO 已 keep）；Hilt/Room/kotlin-reflect 等
            // 由依赖制品内嵌 consumer 规则自动覆盖（见该文件注释）。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    // core 全家（组合根可见全部模块；feature 只许依赖 core，见各 feature 的 build 文件）
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))

    implementation(project(":feature:home"))
    implementation(project(":feature:login"))
    implementation(project(":feature:all"))
    implementation(project(":feature:favorite"))
    implementation(project(":feature:history"))
    implementation(project(":feature:search"))
    implementation(project(":feature:author"))
    implementation(project(":feature:detail"))
    implementation(project(":feature:stats"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:upload"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)

    // WorkManager 自定义初始化（M4-5 上传队列）：Application 直接引用 Configuration/HiltWorkerFactory
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)

    testImplementation(libs.junit)
    testImplementation(project(":core:testing"))
}
