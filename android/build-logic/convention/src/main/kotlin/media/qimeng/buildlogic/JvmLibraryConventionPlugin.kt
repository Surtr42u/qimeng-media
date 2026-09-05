package media.qimeng.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply

// 纯 JVM library convention（Now in Android 范式）：kotlin("jvm") + Java/Kotlin 17 字节码。
// 供零 Android 依赖的模块使用（当前 = :core:model）。
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "org.jetbrains.kotlin.jvm")

            configureKotlinJvm()
        }
    }
}
