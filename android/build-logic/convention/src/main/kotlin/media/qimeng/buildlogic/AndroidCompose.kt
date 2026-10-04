package media.qimeng.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/**
 * Compose 模块公共配置：buildFeatures.compose = true + Compose BOM platform 收口
 * （原 12 个 Compose 模块重复两行的收敛点）。Compose 具体依赖（ui/material3/…）各模块依赖面
 * 不同，留在模块 build 文件；BOM 版本锚定与升级论证见 gradle/libs.versions.toml。
 *
 * 稳定性配置（2026-10-04 批，Now in Android 同款思路）：core:model 值对象虽全 val，
 * 但 List 字段被编译器判 unstable——UiState.copy() 出新实例即引发订阅子树全量重组；
 * 经官方 stabilityConfigurationFiles 把逐类核验过的不可变类标记 stable
 * （清单见根 compose_compiler_config.conf，宁可少列不可错列）。
 * 注意用复数属性（stabilityConfigurationFiles）：单数形态在 Kotlin 2.4.20 已废弃
 * 且本项目 build-logic 按错误拦截，2.5.0 将移除——NIA 上游同款迁移。
 * 前置条件：唯一调用方 AndroidComposeConventionPlugin 先 apply
 * org.jetbrains.kotlin.plugin.compose 再进本函数，composeCompiler 扩展必然已注册。
 */
internal fun Project.configureAndroidCompose(commonExtension: CommonExtension) {
    commonExtension.apply {
        buildFeatures.apply {
            compose = true
        }
    }

    extensions.configure<ComposeCompilerGradlePluginExtension> {
        stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_compiler_config.conf"))
    }

    dependencies {
        val bom = libs.findLibrary("compose-bom").get()
        "implementation"(platform(bom))
    }
}
