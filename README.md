# 绮梦影库（qimeng-media）

**支持手机单机、PC 与 NAS 的自托管多端媒体库**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE) [![Go](https://img.shields.io/badge/Go-1.27-00ADD8.svg)](https://go.dev) [![Node](https://img.shields.io/badge/Node-24%20LTS-339933.svg)](https://nodejs.org)

> 本项目由 AI 实施维护，以协议先行（`api/openapi.yaml`）和文档驱动组织开发；重大技术决策见 `docs/adr/`。
> 旧 Android 单机项目已停用，仅提供历史交互/领域依据；本项目 Android 的内嵌单机模式仍在使用。
> 当前以手机单机与 PC 过渡使用，未来 NAS 硬件须重新部署验收。AI 工作先读 [AGENTS.md](AGENTS.md)，再按任务补读，不默认通读文档。

## 这是什么

一个自托管的家庭媒体库系统：

- **服务端**（Go模块化单体，可部署NAS/常开PC，Android也可内嵌）：扫描媒体目录、生成缩略图、存储索引与行为数据、计算推荐、提供 API 与媒体直链
- **Web 端**（React PWA）：电脑浏览器打开即用，手机可"安装"到桌面
- **Android端**（Kotlin + Compose）：可连接服务端，也可运行内嵌服务端单机使用；直链播放
- **桌面壳**（Tauri 2）：复用同一套 Web UI 的原生桌面应用
- **使用场景**：支持集中存储和手机单机/PC过渡形态。上传后需按独立副本与恢复核对要求再释放原机文件，不能把上传成功当作灾备已具备

## 核心特性

- 10 维自适应推荐算法（9 维权重可调 + 随机扰动 + 每日去重惩罚）
- 排行榜（日/周/月/季/年/总）与多维胶囊筛选
- 出处/角色自动匹配引擎（规范名+变体+角色检索表）
- 作者管理（TXT 导入 + COS 目录扫描双体系）
- 标签、收藏、点赞、视频时间轴标记
- 按天聚合的浏览统计与趋势分析
- 文件上传与整理（移动/重命名/回收站防误删）

## 技术架构（一图流）

```
手机 App (Compose) ─┐                      ┌─ 扫描器 (fsnotify + 轮询)
浏览器/PWA (React) ─┼──► Go 服务端          ┼─ 缩略图管线 (ffmpeg)
桌面壳 (Tauri)     ─┤    SQLite + REST/SSE  ├─ 推荐与统计 (纯函数)
未来: TV           ─┘                       └─ 媒体直链 (HTTP Range)
                          ▲
                          │
                    openapi.yaml ← 协议宪法，三端 SDK 自动生成
```

服务端可在 PC 原生运行、通过 Docker 部署，或内嵌到 Android。Android 单机模式在手机保存媒体和数据并调用本机服务端；连接模式调用 PC/未来 NAS。原件释放条件见[恢复与备份](docs/RESTORE.md)，上传成功不代表已具备独立备份。

## 快速开始（Docker）

以下是集中部署示例，不代表当前已在 NAS 硬件验收。现用 PC/Android 的入口、安装来源与限制见 [HANDOVER](docs/HANDOVER.md)，完整部署前提见 [deploy/README](deploy/README.md)。

```bash
git clone https://github.com/Surtr42u/qimeng-media.git
cd qimeng-media
make docker-build                     # 构建 amd64 镜像（完整流程见 deploy/README.md）
cd deploy && docker compose up -d     # 按注释修改媒体目录挂载
```

浏览器打开 `http://NAS_IP:8420` 注册管理员账号即可使用。默认部署关闭免密开发模式（dev-login）；当前 PC 启动脚本的受信家庭内网例外见 [SECURITY](docs/SECURITY.md#开发模式红线-5-的受限例外)，生产或远程访问须关闭。

## 仓库结构

```
qimeng-media/
├── api/openapi.yaml     协议宪法（先改协议，再生成代码）
├── server/              Go 服务端（模块化单体）
├── web/                 React + Vite + shadcn/ui (PWA)
├── android/             Kotlin + Compose（连接模式 / 内嵌服务端单机模式）
├── desktop/             Tauri 2 桌面壳（连接模式：加载服务端 Web UI，ADR-0020）
└── docs/                架构、领域规则、安全、监控、计划、ADR
```

## 开发

以下列出命令用途。AI 默认将构建、自动单测和基准放到云端；本地检查边界见 [协作规范](AI_README_FIRST.md#验证与交付)，代码审查见 [Review guidelines](AGENTS.md#review-guidelines)。`make server-run` 的默认 8420 是日常真库端口，不能用于测试；隔离验证必须另选端口与临时 DataDir。

```bash
make help          # 查看全部命令
make sdk           # 从 openapi.yaml 重新生成三端 SDK（生成物不入库，改协议后必跑）
make server-run    # 本地跑服务端（:8420）
make server-test   # 服务端全部测试（go test ./...）
make web-dev       # 本地跑 Web 开发服务器
make web-test      # Web 构建/类型检查 + oxlint（不含 Vitest/Playwright）
make lint          # 全部静态检查（redocly + golangci-lint + TS）
make docker-build  # amd64 镜像（deploy/Dockerfile；构建全流程见 deploy/README.md，arm64 为按需储备）
```

CI 覆盖协议、多平台服务端、Web、SDK 生成链、Android 与桌面壳；实际作业与门禁只维护在 [架构 §10](docs/ARCHITECTURE.md#10-工程基础设施)。`make lint` 是本地静态检查入口，不能替代完整 CI。

## 文档导航

| 文档 | 内容 |
|---|---|
| [AGENTS.md](AGENTS.md) | 唯一强制入口：任务路由、短红线与代码审查指南 |
| [AI_README_FIRST.md](AI_README_FIRST.md) | 按需协作细则、验证与文档维护 |
| `llms.txt` | 文档导航总索引（只导航，不增加必读项） |
| `docs/ARCHITECTURE.md` | 架构总纲与技术选型 |
| `docs/DOMAIN_RULES.md` | 领域专题目录（保留旧 §编号，按任务读取） |
| `docs/CAPABILITY_MAP.md` | 能力地图（对标 Jellyfin/Immich/Plex 的现状与缺口） |
| `docs/SECURITY.md` | 安全设计（鉴权/路径穿越/上传防护/回收站/远程访问） |
| `docs/OBSERVABILITY.md` | 监控与仪表盘 |
| `docs/PROJECT_PLAN.md` | 已收官里程碑与未来储备，按需追溯 |
| [GUIDE_UI](docs/GUIDE_UI.md) | 本仓现行交互约束与各端入口 |
| [HANDOVER](docs/HANDOVER.md) | 当前源码、运行包与验证边界 |
| [性能与可靠性任务书](docs/OPTIMIZATION_TASKBOOK_20261025.md) | 已实施范围、基线审查与未完成验收清单 |
| [RESTORE](docs/RESTORE.md) | 备份类型、停服恢复与隔离演练 |
| [历史索引](docs/history/INDEX.md) | 近期/分期 CHANGELOG，按编号关键词查 |
| `docs/adr/` | 架构决策记录（每个重大决策的"为什么"，索引见 `docs/adr/INDEX.md`） |

## License

MIT
