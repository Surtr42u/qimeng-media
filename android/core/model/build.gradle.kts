// :core:model —— 纯 Kotlin 数据类（映射 SDK DTO 的领域模型），零依赖、无 Android/Compose。
// M4-0 空壳起步：暂无源码，首个数据模型随 M4-2 列表族批次落地。
// convention 插件（build-logic，NIA 范式）提供：kotlin("jvm") + Java/Kotlin 17 字节码。
plugins {
    alias(libs.plugins.qimeng.jvm.library)
}

dependencies {
    // 领域纯逻辑单测（JVM 最快档；筛选状态机/分批/分组标签全在此锁定）
    testImplementation(libs.junit)
}
