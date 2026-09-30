# 绮梦影库·NAS 多端版（qimeng-media）

**媒体全存 NAS，手机电脑零负担 —— 内网直连的多端媒体库**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE) [![Go](https://img.shields.io/badge/Go-1.27-00ADD8.svg)](https://go.dev) [![Node](https://img.shields.io/badge/Node-24%20LTS-339933.svg)](https://nodejs.org)

> 本项目 100% 由 AI 生成，作者无编程基础，依靠完善的文档体系驱动 AI 协作开发。
> 前身：绮梦影库（Android 单机版，已退役；领域规则提炼进本项目 `docs/DOMAIN_RULES.md`）。

## 这是什么

一个自托管的家庭媒体库系统：

- **服务端**（Go 单体，跑在 NAS 的 Docker 里）：扫描媒体目录、生成缩略图、存储索引与行为数据、计算推荐、提供 API 与媒体直链
- **Web 端**（React PWA）：电脑浏览器打开即用，手机可"安装"到桌面
- **Android 端**（Kotlin + Compose 薄客户端）：原生体验，直链播放
- **使用场景**：媒体文件全部放在 NAS（或任何常开电脑）上，手机下载的资源通过 App 直传 NAS 后删除本地，解放手机与电脑存储

## 核心特性（继承自已验证的单机版需求）

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

CI（GitHub Actions 五 job：openapi 协议校验 / server vet+test+build+golangci-lint / web tsc+build / sdk-chain 生成链重建 / android make sdk+assembleDebug+test+lint）与 `make lint` 同源同配置，本地全绿 ≈ CI 全绿。

## 文档导航

| 文档 | 内容 |
|---|---|
| `AI_README_FIRST.md` | AI 协作强制规则（先读这个） |
| `llms.txt` | 文档导航总索引（LLM 抓取入口，llms.txt v2 格式） |
| `docs/ARCHITECTURE.md` | 架构总纲与技术选型 |
| `docs/DOMAIN_RULES.md` | 领域规则（推荐算法/筛选/统计/作者——从旧项目继承的完整规格） |
| `docs/CAPABILITY_MAP.md` | 能力地图（对标 Jellyfin/Immich/Plex 的现状与缺口，AI 主动提案依据） |
| `docs/SECURITY.md` | 安全设计（鉴权/路径穿越/上传防护/回收站/远程访问） |
| `docs/OBSERVABILITY.md` | 监控与仪表盘 |
| `docs/PROJECT_PLAN.md` | 里程碑 M0-M6+ 与验收标准 |
| `docs/adr/` | 架构决策记录（每个重大决策的"为什么"，索引见 `docs/adr/INDEX.md`） |

## 反馈与讨论

这是一个个人自用项目的开源，主要想交流两件事：**AI 协作开发的工作流**（文档驱动 + 协议先行，见 `AI_README_FIRST.md` 与 `docs/adr/`）和**三端统一的 UI/交互设计**（Web / Android / 桌面）。对界面、交互、架构有任何建议都欢迎提 Issue，聊 UI 的话附截图或录屏最好。

## 开源边界

- 仓库不含任何真实媒体数据、口令与密钥；免密开发模式仅限本机/内网（安全设计见 `docs/SECURITY.md`）。
- 提 Issue 时请自行脱敏：不要贴服务器地址、口令与涉及个人内容的媒体截图。

## License

MIT
