// build-logic included build（Now in Android 范式，ADR-0014 架构对齐）：convention 插件宿主。
// 作为独立 Gradle 构建求值，由主工程 settings.gradle.kts 的 pluginManagement.includeBuild 接入；
// libs 版本目录直接复用主工程的 gradle/libs.versions.toml（版本单一事实源，禁止在此另写一份）。

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

include(":convention")
