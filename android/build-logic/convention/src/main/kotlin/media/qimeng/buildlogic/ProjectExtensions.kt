package media.qimeng.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

// 消费方工程的 libs 版本目录访问器（Now in Android 同款）：convention 插件内按别名取依赖，
// 目录本体 = 主工程 gradle/libs.versions.toml（build-logic/settings.gradle.kts 复用同一文件）。
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")
