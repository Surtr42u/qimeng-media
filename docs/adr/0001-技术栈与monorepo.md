# ADR-0001：整体技术栈与 monorepo 结构

- 状态：已接受（2026-08-22）
- 决策人：用户 + AI（多轮架构研究后定论）

## 背景

用户需要"多端 + NAS 后端"的媒体库（前身是 2.4 万行 Kotlin Android 单机版），要求：先进、实用、快速开发、多平台适配、架构长期稳定。用户无编程基础，全部代码 AI 生成——**AI 生成质量的稳定性是一等选型约束**。

## 决策

- **Go 1.27 + chi + sqlc + SQLite + golang-migrate** 服务端（模块化单体）
- **React + Vite + TS + shadcn/ui + Tailwind + TanStack Query + Framer Motion** Web/PWA
- **Kotlin + Jetpack Compose + Coil 3 + Media3** Android 客户端
- **OpenAPI 3.1 协议宪法**：`api/openapi.yaml` 唯一事实源，oapi-codegen 生成 Go 接口层，@hey-api/openapi-ts 生成 TS 客户端，openapi-generator 生成 Kotlin 客户端（2026-08-22 M0 实施定案：TS 生成器由 openapi-generator 改为 @hey-api——对 OpenAPI 3.1 支持更完整，产物零外部运行时依赖；生成链见根 Makefile `make sdk`）
- **monorepo**（api/server/web/android/docs 同仓库）：协议一改，AI 单任务同步三端

## 理由

- Go：单二进制、低内存（PhotoPrism/Gitea/Syncthing 验证的 NAS 软件路线）、纯静态类型、AI 语料厚且出错率低；交叉编译 + Docker buildx 双架构让 PC→fnOS→NAS 零成本迁移。
- React 生态：组件 + 动效库解决用户"UI 不好看"的历史痛点；AI 语料最大。
- Compose：用户 Kotlin/AI 协作经验延续；Coil/ExoPlayer 原生吃 HTTP 直链，薄客户端天然契合。
- 协议生成是"多端快速适配"的引擎：接口改动 → 三端 SDK 重生成 → 编译错误指路，杜绝各端漂移。

## 放弃的方案

- Immich 式 TS 全栈（NestJS）：团队级选择，依赖链与内存对单人+AI 是负担
- Flutter/RN 全端：用户 Kotlin 积累归零，Web 端性能一般
- Compose Multiplatform 全端（2026 桌面 stable / Web beta）：桌面视频播放生态弱、AI 语料少
- Kotlin/Ktor 服务端：为复用 40% 领域代码绑 JVM 不值得（领域规则靠文档迁移，不靠代码复用）
- Rust：AI 大型生成出错率与 debug 成本对无编程基础用户致命

## 后果

- 三端各有 UI 代码（不共享 UI），换来的每端都是最成熟路线
- 任何接口改动有固定三步流程（改协议→生成→适配），纪律成本换来长期不腐化
