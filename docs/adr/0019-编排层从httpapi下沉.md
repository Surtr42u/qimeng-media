# ADR-0019：编排层从 httpapi 下沉

## 背景（Context）

ARCHITECTURE §5 写明 `httpapi` 职责是「请求校验、鉴权、调编排服务、序列化」，禁止业务规则；但现实是**编排服务并不存在**——多步业务流直接长在 handler 里：上传的「校验→冲突解析→落盘→入库→富化→广播」（`upload.go`）、移动的「先动文件后改库行」（`filing.go`）、统计重建的「读事件→过滤已删→按天聚合→写物化表」（`stats.go` 的 `RebuildAssetDailyStatsFromEvents`，`stats/doc.go` 亦交叉引用此函数）。httpapi 正在变成 god package：业务编排、协议适配、错误码混写一处，流程逻辑只能裹着 HTTP 测试，边界文档与代码持续漂移。

## 决策（Decision）

1. **多步业务编排必须下沉到专职包**（新建 `server/internal/orchestration/` 或等价 app 层包）：跨 store/业务模块的流程编排、步骤顺序、失败语义归该包；`httpapi` 只做鉴权、参数校验、序列化、调用编排入口。
2. **增量迁移，不搞大爆炸**：既有厚 handler 允许按触碰逐步下沉；**新增多步业务流禁止继续堆进 httpapi**——新流程从第一天起放在编排包。
3. **不引入编排框架**（workflow engine / CQRS 框架等一概不进）：当前体量用普通 Go 函数 + 显式依赖注入足够；引入新框架须另开 ADR。
4. **`cmd/qimeng` 仍是唯一组合根**：编排包的依赖注入只在 main 发生（与 ADR-0010 的 depguard 例外一致）；编排包禁止反向依赖 `httpapi`。

## 状态（Status）

Accepted

## 后果（Consequences）

- **积极**：httpapi 回归薄壳，多步流可脱离 HTTP 单测；god package 增长止血。
- **代价**：迁移期同一业务流可能暂存两处（handler 旧路径 / 新包），触碰时须尽快收敛。
- **约束**：新代码若在 httpapi 写多步编排，自审/评审直接打回（`AI_README_FIRST.md` 自审清单「架构边界」行）。
- **联动**：`server/internal/httpapi/upload.go`、`filing.go`、`stats.go`（含 `RebuildAssetDailyStatsFromEvents`）、`server/internal/stats/doc.go` 交叉引用、`docs/ARCHITECTURE.md` §5/§5.1、`AI_README_FIRST.md`。
