package media.qimeng.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

// Hilt convention（Now in Android 范式）：Hilt Gradle 插件 + KSP + hilt-android 运行时与
// hilt-compiler 注解处理器收口（原 9 个模块重复四行的收敛点）。
// 模块内其余 KSP 处理器（如 androidx.hilt.compiler）属模块差异，留在模块 build 文件。
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.google.dagger.hilt.android")
            apply(plugin = "com.google.devtools.ksp")

            dependencies {
                "implementation"(libs.findLibrary("hilt-android").get())
                "ksp"(libs.findLibrary("hilt-compiler").get())
            }
        }
    }
}
