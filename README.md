# 绮梦影库·NAS 多端版（qimeng-media）

**媒体全存 NAS，手机电脑零负担 —— 内网直连的多端媒体库**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE) [![Go](https://img.shields.io/badge/Go-1.27-00ADD8.svg)](https://go.dev) [![Node](https://img.shields.io/badge/Node-24%20LTS-339933.svg)](https://nodejs.org)

> 本项目 100% 由 AI 生成，作者无编程基础，依靠完善的文档体系驱动 AI 协作开发。
> 前身：[绮梦影库](../..)（Android 单机版，作为领域知识库继续存在，架构不共享）。

## 这是什么

一个自托管的家庭媒体库系统：

- **服务端**（Go 单体，跑在 NAS 的 Docker 里）：扫描媒体目录、生成缩略图、存储索引与行为数据、计算推荐、提供 API 与媒体直链
- **Web 端**（React PWA）：电脑浏览器打开即用，手机可"安装"到桌面
- **Android 端**（Kotlin + Compose 薄客户端）：原生体验，直链播放
- **使用场景**：媒体文件全部放在 NAS（或任何常开电脑）上，手机下载的资源通过 App 直传 NAS 后删除本地，解放手机与电脑存储

## 核心特性（继承自已验证的单机版需求）

- 10 维自适应推荐算法（9 维权重可调 + 随机扰动 + 每日去重惩罚）
- 排行榜（日/周/月/年/总）与多维胶囊筛选
- 出处/角色自动匹配引擎（规范名+变体+角色检索表）
- 作者管理（TXT 导入 + COS 目录扫描双体系）
- 标签、收藏、点赞、视频时间轴标记
- 按天聚合的浏览统计与趋势分析
- 文件上传与整理（移动/重命名/回收站防误删）

## 技术架构（一图流）

```
手机 App (Compose) ─┐                      ┌─ 扫描器 (fsnotify 增量)
浏览器/PWA (React) ─┼──► Go 服务端 (Docker) ─┼─ 缩略图管线 (ffmpeg)
未来: TV/桌面壳    ─┘   SQLite + REST/SSE   ├─ 推荐与统计 (纯函数)
                          ▲                 └─ 媒体直链 (HTTP Range)
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
└── docs/                架构、领域规则、安全、监控、计划、ADR
```

## 开发

```bash
make help          # 查看全部命令
make sdk           # 从 openapi.yaml 重新生成三端 SDK
make server-run    # 本地跑服务端
make web-dev       # 本地跑 Web 开发服务器
make docker-build  # 构建双架构镜像
```

## 文档导航

| 文档 | 内容 |
|---|---|
| `AI_README_FIRST.md` | AI 协作强制规则（先读这个） |
| `docs/ARCHITECTURE.md` | 架构总纲与技术选型 |
| `docs/DOMAIN_RULES.md` | 领域规则（推荐算法/筛选/统计/作者——从旧项目继承的完整规格） |
| `docs/SECURITY.md` | 安全设计（鉴权/路径穿越/上传防护/回收站/远程访问） |
| `docs/OBSERVABILITY.md` | 监控与仪表盘 |
| `docs/PROJECT_PLAN.md` | 里程碑 M0-M5 与验收标准 |
| `docs/adr/` | 架构决策记录（每个重大决策的"为什么"） |

## License

MIT
