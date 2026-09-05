package media.qimeng.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

// :app 用的 application convention（Now in Android 范式）：application + Kotlin Android 插件 +
// 公共 Android 面（compileSdk/minSdk/Java 17/jvmTarget 17）。
// targetSdk/applicationId/versionName 等模块差异留在 :app 的 build 文件。
class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.android.application")
            apply(plugin = "org.jetbrains.kotlin.android")

            extensions.configure<ApplicationExtension> {
                configureKotlinAndroid(this)
            }
        }
    }
}
