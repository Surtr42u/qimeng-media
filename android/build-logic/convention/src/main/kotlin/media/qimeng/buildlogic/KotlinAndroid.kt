package media.qimeng.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

// Android 公共版本面（原 17 个模块 build 文件逐字平移的收敛点；取值来源与升级论证统一见
// gradle/libs.versions.toml 头注。compileSdk 36 = 冻结的 AGP 8.13 档上限，升 37 须整体走
// AGP 9 + Gradle 9 路线，禁止单边改动此处）。
internal const val ANDROID_COMPILE_SDK = 36
internal const val ANDROID_MIN_SDK = 26

/**
 * Android 模块公共配置：compileSdk/minSdk/Java 17 字节码 + Kotlin jvmTarget 17。
 * 与 A-S1 收敛前各模块 android{} 块逐字等价（零行为变更）。
 */
internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension<*, *, *, *, *, *>) {
    commonExtension.apply {
        compileSdk = ANDROID_COMPILE_SDK

        defaultConfig.apply {
            minSdk = ANDROID_MIN_SDK
        }

        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
    }

    configureKotlinJvmTarget<KotlinAndroidProjectExtension>()
}

/**
 * 纯 JVM 模块（kotlin("jvm")）公共配置：Java 17 编译档 + Kotlin jvmTarget 17。
 * 与 A-S1 收敛前 :core:model 的 java{}/kotlin{} 块逐字等价（JDK 21 工具链可编 17 目标）。
 */
internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    configureKotlinJvmTarget<KotlinJvmProjectExtension>()
}

/**
 * Kotlin 编译器公共选项收敛点：jvmTarget 统一 17（原各模块 kotlin{compilerOptions{}} 的唯一去处）。
 */
private inline fun <reified T : KotlinBaseExtension> Project.configureKotlinJvmTarget() =
    configure<T> {
        when (this) {
            is KotlinAndroidProjectExtension -> compilerOptions
            is KotlinJvmProjectExtension -> compilerOptions
            else -> TODO("Unsupported project extension $this ${T::class}")
        }.apply {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
