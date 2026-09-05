// qimeng-media Android 工程设置（M4 Compose 重建，ADR-0014 冻结多模块结构）
// 依赖单向：feature -> core；:app 组合根。:sdk 为 make sdk 的生成物（git 忽略），
// checkout 后不存在——任何缺 :sdk 的报错先在仓库根跑 `make sdk`，禁止手改其内容。

pluginManagement {
    // convention 插件宿主（NIA 范式，A-S1 收敛）：qimeng.android.* / qimeng.jvm.library 由它提供；
    // 必须放在 pluginManagement 内，插件才会经 included build 参与解析。
    includeBuild("build-logic")
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

// 不显式设 repositoriesMode（默认 PREFER_PROJECT）：:sdk 生成物自带 project 级仓库声明，
// 收紧为 PREFER_SETTINGS/FAIL_ON_PROJECT_REPOS 会把生成物构建掐死（禁手改生成物）。
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "qimeng-media-android"

include(":app")
include(":core:model")
include(":core:network")
include(":core:data")
include(":core:ui")
include(":core:testing")
include(":feature:login")
include(":feature:home")
include(":feature:all")
include(":feature:favorite")
include(":feature:history")
include(":feature:search")
include(":feature:author")
include(":feature:detail")
include(":feature:stats")
include(":feature:settings")
include(":feature:upload")
// make sdk 生成物（android/sdk，原样接入；禁止手改）。生成物自带的 build.gradle 用 Gradle 7 时代的
// wrapper{} DSL（Gradle 8 起移除按名配置任务语法），在冻结的 Gradle 8.13 下无法求值——
// buildFileName 指向工程侧脚本 sdk.gradle（同样由 `make sdk` 的 sdk-kotlin 步骤生成，勿手改），
// 不破坏「生成物/接线文件一律 make sdk 重建」的不变式。
include(":sdk")
project(":sdk").buildFileName = "sdk.gradle"
