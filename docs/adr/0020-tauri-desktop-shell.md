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

构建纪律：Rust 首次编译有依赖峰值，统一 `CARGO_BUILD_JOBS=8` 限核（对齐
项目 Gradle ≤50% CPU 口径，20 核机）；日常使用零编译。

## 状态（Status）

Accepted——已接受（2026-09-16）。

## 后果（Consequences）

- 新增独立 `desktop/` 目录（Tauri 2 工程），与 web/android/server 并列；
- 桌面端 commit scope 约定为 **`desktop`**（现有 `api/web/app/server/docs`
  约定之外的扩展，在此记档）；
- 代价：引入 Rust 工具链，首次编译存在依赖峰值（已用 `CARGO_BUILD_JOBS=8`
  限核）；Windows 端依赖系统 WebView2 组件（一般已随系统预装）；
- 受影响文件/文档：`desktop/`（新增）、`docs/adr/INDEX.md`、`docs/CHANGELOG.md`。
