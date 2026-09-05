package media.qimeng.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Compose 模块公共配置：buildFeatures.compose = true + Compose BOM platform 收口
 * （原 12 个 Compose 模块重复两行的收敛点）。Compose 具体依赖（ui/material3/…）各模块依赖面
 * 不同，留在模块 build 文件；BOM 版本锚定与升级论证见 gradle/libs.versions.toml。
 */
internal fun Project.configureAndroidCompose(commonExtension: CommonExtension<*, *, *, *, *, *>) {
    commonExtension.apply {
        buildFeatures.apply {
            compose = true
        }
    }

    dependencies {
        val bom = libs.findLibrary("compose-bom").get()
        "implementation"(platform(bom))
    }
}
