import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core:model —— 纯 Kotlin 数据类（映射 SDK DTO 的领域模型），零依赖、无 Android/Compose。
// M4-0 空壳起步：暂无源码，首个数据模型随 M4-2 列表族批次落地。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions {
        // 与 Android 模块统一 17 字节码（AGP 8.x 默认工具链之上跑，JDK 21 可编译 17 目标）
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // 领域纯逻辑单测（JVM 最快档；筛选状态机/分批/分组标签全在此锁定）
    testImplementation(libs.junit)
}
