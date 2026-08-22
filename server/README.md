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
