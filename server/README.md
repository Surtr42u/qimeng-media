# server - Go 服务端开发入口

> Go 模块化单体；当前模块和依赖边界见 [ARCHITECTURE §5](../docs/ARCHITECTURE.md#5-服务端模块边界serverinternal)。只读本次影响模块，不默认通读 ADR。

## 定位任务

| 工作 | 入口 |
|---|---|
| 组合根/装配 | cmd/qimeng/；配置 → 模块装配 → HTTP |
| 协议/请求适配 | internal/httpapi/；先查 OpenAPI 与 API 专题 |
| 领域纯函数 | recommend、stats、sourcematcher、authoring；先查领域专题 |
| 持久化 | internal/store/、migrations/；ADR-0011 迁移纪律 |
| 扫描/身份合并 | internal/scanner/；ADR-0004、0039 |
| 文件操作/恢复意图 | internal/filing/；SECURITY 文件边界、领域 files |
| 排行页装载 | internal/ranking/；专用热度键 → recommend 纯函数选页 → 当前页元数据/累计计数，见推荐专题 |
| 事件统计编排 | internal/eventstats/；事务内读真相/重建，stats 保持纯函数（ADR-0040） |
| 上传/本机同步 | uploadsess、localsync 与现有 httpapi 适配；新增编排下沉规则见 §5.1 |

此表是定位入口，完整包职责只维护在架构边界表。HTTP 路由使用标准库 ServeMux，废弃的 M0 初始化清单不再适用。

## 开发与验证

仓库根 `make server-run`、`make server-test`、`make lint`，具体目标以 Makefile 为准。先按 AGENTS 读取适用协作规则；广泛服务端影响跑 `go test ./...`，文件平台行为需对应 CI/目标环境。生成接口层不可手改；SDK 生成见根入口。

测试只用临时数据库、合成媒体、临时目录及隔离端口，禁止写入 8420 真库。扫描差集/恢复/续传失败重试边界见 [文件规则](../docs/domain/files.md) 与 [SECURITY](../docs/SECURITY.md)，便携 v1/v2 兼容见 [迁移](../docs/domain/migration.md)。

列表查询策略、核心 SQL/HTTP 基准和隔离复跑命令见 [筛选专题](../docs/domain/filtering.md#查询执行策略与性能边界)。同构投影、窄查询白名单与计划锁防止加速引入漏筛选；协议/schema 不变（ADR-0043）。

统计热榜先按资产聚合再汇总关联；`store.LoadRecommendationInputs` 负责推荐输入的只读装载与规模选路，`recommend` 仍只做纯函数评分。默认宽流保留两次读取，显式媒体/COS 流的额外规模判断与窄候选收益见 [推荐专题](../docs/domain/recommendation.md#16-输入装载策略与性能边界)，热榜基准见 [统计专题](../docs/domain/statistics.md#热榜查询策略与性能边界)（ADR-0044）。

恢复/真实跨卷演练见 [RESTORE](../docs/RESTORE.md)。localsync.PlanScan 负责安全校验后的只读扫描计划，错误不返回可清理状态的半成品；现存导入写入编排仍在 httpapi。作者列表预聚合与原计数口径一致，合成基准不能替代真实大库验收（ADR-0042）。
