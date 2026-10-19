# server/ — Go 服务端

> M0/M1 里程碑在此搭建。动手前必读：`../AI_README_FIRST.md`、`../docs/ARCHITECTURE.md` §5（模块边界）、`../docs/adr/`。

## 目标结构

```
server/
├── cmd/qimeng/main.go        # 入口：配置加载 → 模块装配 → HTTP 启动
├── internal/                  # 模块边界与禁令见 ARCHITECTURE §5
│   ├── config/  httpapi/  auth/
│   ├── scanner/  thumbnail/  search/
│   ├── recommend/  stats/    # 纯函数，禁止 IO
│   ├── filing/               # 上传/移动/回收站
│   ├── store/                # sqlc 生成 + migrations 引导
│   ├── events/  sysmon/
├── migrations/                # golang-migrate 版本化迁移（只增不改）
├── sqlc.yaml
└── go.mod
```

## 初始化检查单（M0）

- [ ] `go mod init github.com/<user>/qimeng-media/server`
- [ ] chi + slog + config 依赖引入（读官方文档确认最新版本）
- [ ] 目录骨架 + 包注释

## 扫描与文件恢复一致性（2026-10-19）

`internal/scanner/reconcile.go` 负责扫描差集的实时复核与事务移动合并；`internal/filing/restore_intent.go` 从回收站meta提供未完成恢复的路径预留索引（显式失效）。路径检查拒绝链接，库内操作优先使用os.Root；具体失败重试与跨根移动边界见 `../docs/adr/0039-扫描提交与文件恢复一致性.md` 和 `../docs/SECURITY.md`。回归测试全部使用临时数据库、合成媒体与临时目录。

## 便携备份与统计一致性（2026-10-19）

`internal/eventstats/` 承载持写锁事务内的全量/单资产日物化重建，`stats/materialize.go` 为无 IO 聚合。v2 便携备份保留原始事件身份，migration 0019 为无键旧事件建持久身份旁表而不修改历史；多库映射歧义跳过。缩略图 URL 与按资产分目录的磁盘键贯通表示版本，Android 预取同时检测策略指纹；兼容边界见 ADR-0040。
