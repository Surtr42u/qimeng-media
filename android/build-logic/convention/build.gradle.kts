import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// convention 插件编译面：AGP/KGP API 以 compileOnly 挂入（仅编译期可见），运行期插件版本由
// 主工程根 build.gradle.kts 的 apply false 声明收口——convention 自身不携带运行时版本，
// 避免构建类路径出现双版本。版本锚定与升级论证统一见 gradle/libs.versions.toml。
plugins {
    `kotlin-dsl`
}

// 编译目标 JDK 17：与主工程构建 JDK 一致（本机 = Android Studio jbr，见 HANDOVER_APP）
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    // Compose 编译器 Gradle 插件 DSL（ComposeCompilerGradlePluginExtension 挂
    // stabilityConfigurationFile 用，2026-10-04 稳定性配置批；artifact 见 toml 同名条目）
    compileOnly(libs.compose.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "qimeng.android.application"
            implementationClass = "media.qimeng.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "qimeng.android.library"
            implementationClass = "media.qimeng.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "qimeng.android.compose"
            implementationClass = "media.qimeng.buildlogic.AndroidComposeConventionPlugin"
        }
        register("androidHilt") {
            id = "qimeng.android.hilt"
            implementationClass = "media.qimeng.buildlogic.AndroidHiltConventionPlugin"
        }
        register("jvmLibrary") {
            id = "qimeng.jvm.library"
            implementationClass = "media.qimeng.buildlogic.JvmLibraryConventionPlugin"
        }
    }
}
