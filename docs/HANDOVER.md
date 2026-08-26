# HANDOVER - AI 交接说明

> 写给下一位接手的 AI（任何模型/工具）。人类用户无编程基础，全部代码由 AI 生成。
> 最后更新：2026-08-22（M1 完成时点）

## 接手第一步

1. 读 `AI_README_FIRST.md`（协作强制规则，含签名纪律与冲突优先级）
2. 读 `docs/PROJECT_PLAN.md`（里程碑勾选状态 = 进度唯一真相）
3. 读 `docs/CHANGELOG.md`（历次变更 + AI 署名）
4. 按任务类型查 `AGENTS.md` 的任务→文档路由表

## 当前进度（2026-08-22）

- **M0 地基 ✅**、**M1 服务端核心闭环 ✅**（PC 侧全链验收通过；手机真机验收待用户执行）
- **下一步：M2（Web 端完整体验）**，之后 M3（算法移植）→ M4（Android）→ M5（飞牛 NAS 部署）

## 怎么跑起来

- **一键启动**：双击根目录 `启动服务端.bat`（端口 8420，数据目录 `qimeng-data/`）
- **验收页**：浏览器开 `http://127.0.0.1:8420`（中文界面：设密码→注册媒体目录→扫描→浏览→播放）
- **测试**：`cd server && go test ./...`（9 包全绿是底线）；Web：`cd web && npx tsc --noEmit`
- **协议改动**：先改 `api/openapi.yaml` → `make sdk` → 按编译错误适配三端（铁律 1）
- CI 在 GitHub Actions（push 自动跑四道门禁），仓库：`Surtr42u/qimeng-media`（私有，gh 已登录）

## 后端还剩什么（重要：不是"只剩 UI"）

M1 只完成了**浏览闭环**。剩余后端工作量不小：

| 剩余项 | 里程碑 | 规模预估 |
|---|---|---|
| 上传/移动/重命名/回收站端点（filing 件已就绪，只差接线+磁盘 IO） | M2 | 中 |
| FTS5 全文搜索（search 包未建） | M2 | 中 |
| sysmon 接线（/metrics、/system/status、PerCore 补协议） | M2 | 小 |
| **推荐算法 10 维移植（DOMAIN_RULES §1 逐字遵守 + 自适应权重回收 + 三个后处理）** | M3 | 大 |
| 统计聚合/趋势分桶（§5 口径） | M3 | 中 |
| SourceMatcher 出处匹配引擎（131 SourceGroup 表从旧仓库翻译） | M3 | 大 |
| 作者双体系（TXT 三格式解析 + COS 目录扫描） | M3 | 大 |
| 旧数据迁移端点（qimeng_backup.json 17 段映射） | M3 | 中 |

UI（M2 前半 + M4 Android）与上述后端并行推进。

## 本项目的特色纪律（换 AI 最容易踩的坑）

1. **协议先行**：任何接口改动第一步永远是 openapi.yaml，手写客户端 SDK 是事故
2. **migration 唯一**：0001 已发布（commit 1625db1），改表结构只能新增 0002+
3. **DOMAIN_RULES「逐字遵守」的常量/公式**：改动必须先经用户确认
4. **用户强偏好**：查看媒体**永远发原件**（缩放副本方案已被否决，commit 4a1f3da）；措辞用"看视频/图片"不用"看片"
5. **commit 格式**：`类型(模块): 简述 | 文档: 已更新XXX`，代码+文档同一 commit
6. **并发子代理上限 2 个**（第 3 个会撞 user concurrency limit 直接失败）
7. **Windows 环境**：无 winget；JDK 免安装在 `../dev-tools/jdk17`；bat/make 运行时输出必须纯 ASCII（cmd 代码页坑）
8. 生成物（gen/generated/android-sdk）不入库，`make sdk` 重建

## 领域知识库（旧项目）

`<旧项目目录>`（Android 单机版，2.4 万行）：**领域规则照搬、实现代码禁止搬运**。算法细节参考其 `docs/GUIDE_ALGORITHM.md`、`GUIDE_AUTHOR.md`（SourceMatcher 131 表源数据）、M4 交互规格参考 `GUIDE_UI.md`；近期审查/修复沉淀的需求级结论见本仓库 `docs/LEGACY_REQUIREMENTS.md`（标签管理/缩略图代表帧/搜索作者维度/统计口径等，M2~M4 实现前对照）。
