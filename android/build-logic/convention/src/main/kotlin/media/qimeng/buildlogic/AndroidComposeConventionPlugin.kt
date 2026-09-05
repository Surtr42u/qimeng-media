package media.qimeng.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply

// Compose convention（Now in Android 范式）：Kotlin Compose 编译器插件（Kotlin 2.x 起随 Kotlin
// 同版本发布，见 libs.versions.toml）+ buildFeatures.compose + Compose BOM platform 收口。
// 必须在 qimeng.android.application/library 之后应用（模块 plugins 块内按序声明）。
// 扩展查找按具体接口逐级回退：AGP 8.x 下按泛型基类 CommonExtension 查询匹配不到注册实例，
// 必须用 ApplicationExtension/LibraryExtension 的 Class 匹配（app/library 两类宿主通吃）。
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "org.jetbrains.kotlin.plugin.compose")

            val extension: CommonExtension<*, *, *, *, *, *> =
                extensions.findByType(ApplicationExtension::class.java)
                    ?: extensions.findByType(LibraryExtension::class.java)
                    ?: throw IllegalStateException(
                        "qimeng.android.compose 必须在 qimeng.android.application/library 之后应用",
                    )
            configureAndroidCompose(extension)
        }
    }
}
