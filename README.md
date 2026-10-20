# 绮梦影库·NAS 多端版（qimeng-media）

**媒体全存 NAS，手机电脑零负担 —— 内网直连的多端媒体库**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE) [![Go](https://img.shields.io/badge/Go-1.27-00ADD8.svg)](https://go.dev) [![Node](https://img.shields.io/badge/Node-24%20LTS-339933.svg)](https://nodejs.org)

> 本项目 100% 由 AI 生成：以协议先行（`openapi.yaml`）+ 文档驱动的工程体系组织 AI 协作开发，每个重大决策都有 ADR 可追溯（`docs/adr/`）。
> 前身：绮梦影库（Android 单机版，已退役）；现行领域规则见专题目录。
> 当前以手机单机与 PC 过渡使用，未来 NAS 硬件须重新部署验收。AI 工作先读 [AGENTS.md](AGENTS.md)，再按任务补读，不默认通读文档。

## 这是什么

一个自托管的家庭媒体库系统：

- **服务端**（Go 单体，跑在 NAS 的 Docker 里）：扫描媒体目录、生成缩略图、存储索引与行为数据、计算推荐、提供 API 与媒体直链
- **Web 端**（React PWA）：电脑浏览器打开即用，手机可"安装"到桌面
- **Android 端**（Kotlin + Compose 薄客户端）：原生体验，直链播放
- **桌面壳**（Tauri 2）：复用同一套 Web UI 的原生桌面应用
- **使用场景**：媒体文件全部放在 NAS（或任何常开电脑）上，手机下载的资源通过 App 直传 NAS 后删除本地，解放手机与电脑存储

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
手机 App (Compose) ─┐                      ┌─ 扫描器 (fsnotify 增量)
浏览器/PWA (React) ─┼──► Go 服务端 (Docker) ─┼─ 缩略图管线 (ffmpeg)
桌面壳 (Tauri)     ─┤    SQLite + REST/SSE  ├─ 推荐与统计 (纯函数)
未来: TV           ─┘                       └─ 媒体直链 (HTTP Range)
                          ▲
                          │
                    openapi.yaml ← 协议宪法，三端 SDK 自动生成
```

## 快速开始（Docker）

```bash
git clone https://github.com/Surtr42u/qimeng-media.git
cd qimeng-media
make docker-build                     # 构建 amd64 镜像（完整流程见 deploy/README.md）
cd deploy && docker compose up -d     # 按注释修改媒体目录挂载
```

浏览器打开 `http://NAS_IP:8420` 注册管理员账号即可使用。免密开发模式（dev-login）默认关闭，仅限本机调试临时开启。

## 仓库结构

```
qimeng-media/
├── api/openapi.yaml     协议宪法（先改协议，再生成代码）
├── server/              Go 服务端（模块化单体）
├── web/                 React + Vite + shadcn/ui (PWA)
├── android/             Kotlin + Compose 薄客户端
├── desktop/             Tauri 2 桌面壳（连接模式：加载服务端 Web UI，ADR-0020）
└── docs/                架构、领域规则、安全、监控、计划、ADR
```

## 开发

```bash
make help          # 查看全部命令
make sdk           # 从 openapi.yaml 重新生成三端 SDK（生成物不入库，改协议后必跑）
make server-run    # 本地跑服务端（:8420）
make server-test   # 服务端全部测试（go test ./...）
make web-dev       # 本地跑 Web 开发服务器
make web-test      # Web 检查（tsc 类型检查 + lint）
make lint          # 全部静态检查（redocly + golangci-lint + TS）
make docker-build  # amd64 镜像（deploy/Dockerfile；构建全流程见 deploy/README.md，arm64 为按需储备）
```

CI 覆盖协议、多平台服务端、Web、SDK 生成链、Android 与桌面壳；实际作业与门禁只维护在 [架构 §10](docs/ARCHITECTURE.md#10-工程基础设施)。`make lint` 是本地静态检查入口，不能替代完整 CI。

## 文档导航

| 文档 | 内容 |
|---|---|
| [AGENTS.md](AGENTS.md) | 唯一强制入口：任务路由与短红线 |
| [AI_README_FIRST.md](AI_README_FIRST.md) | 按需协作细则、验证与文档维护 |
| `llms.txt` | 文档导航总索引（只导航，不增加必读项） |
| `docs/ARCHITECTURE.md` | 架构总纲与技术选型 |
| `docs/DOMAIN_RULES.md` | 领域专题目录（保留旧 §编号，按任务读取） |
| `docs/CAPABILITY_MAP.md` | 能力地图（对标 Jellyfin/Immich/Plex 的现状与缺口） |
| `docs/SECURITY.md` | 安全设计（鉴权/路径穿越/上传防护/回收站/远程访问） |
| `docs/OBSERVABILITY.md` | 监控与仪表盘 |
| `docs/PROJECT_PLAN.md` | 已收官里程碑与未来储备，按需追溯 |
| [GUIDE_UI](docs/GUIDE_UI.md) | 本仓现行交互约束与各端入口 |
| [HANDOVER](docs/HANDOVER.md) | 当前事项与验证边界 |
| [RESTORE](docs/RESTORE.md) | 备份类型、停服恢复与隔离演练 |
| [历史索引](docs/history/INDEX.md) | 近期/分期 CHANGELOG，按编号关键词查 |
| `docs/adr/` | 架构决策记录（每个重大决策的"为什么"，索引见 `docs/adr/INDEX.md`） |

## License

MIT
