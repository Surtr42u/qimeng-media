# ADR-0020：PC 桌面客户端采用 Tauri 2 壳（连接模式）

## 背景（Context）

用户拍板要「哔哩哔哩桌面客户端那样」的 PC 端——浏览器标签页打开 Web UI
没有独立 App 的感觉。项目现状对桌面壳非常友好：Go 服务端已在 8420 端口
托管完整桌面风格 Web UI（`web/dist`），开放协议 `api/openapi.yaml` 是三端
唯一事实源（SDK 自动生成）；`docs/PROJECT_PLAN.md` M7+ 储备中原已列有
「Tauri 桌面壳」一项，本次提前落地。

## 决策（Decision）

采用 **Tauri 2** 做连接模式桌面壳，放全新 `desktop/` 目录。候选对比：

| 候选 | 结论 |
|---|---|
| 浏览器/PWA | 零成本，但无独立 App 感，也不能内嵌服务端 |
| Electron | 自带 Chromium，产物 100~200MB，对本项目无对应优势 |
| **Tauri 2（选定）** | 系统自带 WebView2 渲染 + Rust 壳，产物几 MB；将来可扩展 sidecar 内嵌 Go 服务端 |

壳的职责严格限定为：**首次启动填服务器地址 → 主窗口加载该地址的 Web UI**。
明确约束（用户拍板「不改 UI、不重构」）：

- `web/`、`android/`、`server/`、`api/openapi.yaml` **零改动**；
- **不做**内嵌服务端单机版——若将来立项须另写 ADR，本 ADR 范围仅连接模式。

构建纪律：**不设任何 CPU 限核**——Rust 首次编译有依赖峰值属正常现象，一律
全量构建（2026-09-16 用户拍板：项目内一切 CPU 限核约束全部解除，旧 Gradle
`--max-workers`/`-Dorg.gradle.priority=low` 与 Cargo `CARGO_BUILD_JOBS` 限核
口径一并作废，不得重建）；日常使用零编译。

## 状态（Status）

Accepted——已接受（2026-09-16）。

**补记（2026-10-04，全仓重构批文档对齐）**：上文「壳的职责严格限定为」
一句为立项时口径；实际交付形态还含三件超出该句的能力（托盘与导航守卫两件
随批记档于 `desktop/README.md`「行为说明」与 ARCHITECTURE §8；无边框 +
titlebar.js 注入一件当时仅记档于 CHANGELOG〔历史档第二百八十五笔〕，本 ADR
补记对齐）：托盘（左键聚焦/右键菜单）、导航守卫（主窗口仅放行配置主机的
http/https）、无边框窗口 + `titlebar.js` 注入（依赖 Web TopBar 的类名/
title 文案契约，见 `desktop/README.md`「跨仓契约」节）。另：`web/` 零改动
约束在 TopBar 处存在软性例外——TopBar 为桌面壳保留三枚窗口控件的 title
文案（有注释互指），属消费契约而非行为改动。

## 后果（Consequences）

- 新增独立 `desktop/` 目录（Tauri 2 工程），与 web/android/server 并列；
- 桌面端 commit scope 约定为 **`desktop`**（现有 `api/web/app/server/docs`
  约定之外的扩展，在此记档）；
- 代价：引入 Rust 工具链，首次编译存在依赖峰值（全核构建，不限核）；
  Windows 端依赖系统 WebView2 组件（一般已随系统预装）；
- 受影响文件/文档：`desktop/`（新增）、`docs/adr/INDEX.md`、`docs/CHANGELOG.md`。
