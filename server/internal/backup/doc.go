// Package backup 是库文件备份热备模块：VACUUM INTO 在线快照 + 定时调度 +
// 轮转保留 + 快照目录管理。
//
// 职责（单职责边界，ADR-0010 / ARCHITECTURE §5.1）：
//   - 调度：标准库 ticker 定时触发（间隔来自 config backup.interval）；
//     定时与手动共用同一防重入闸（同一时刻至多一个快照在跑）；
//   - 轮转：快照完成后按保留份数（backup.retention）删除超龄最旧快照；
//   - 目录管理：备份目录（DataDir/backups）运行时惰性创建、快照列表、
//     单份删除与打开（供 httpapi 下载端点流式读出）。
//
// 边界：
//   - SQL 执行（VACUUM INTO）属 store 边界——本包不 import store、不持
//     数据库连接，快照执行器由组装方（main）以 SnapshotFunc 注入，本包
//     对 SQLite 零感知；
//   - 指标/埋点经 OnSuccess 回调外流（本包不感知 sysmon）；
//   - 安全：快照文件名严格白名单（snapshotNamePattern，防路径穿越），
//     文件访问一律经 os.Root 锚定在备份目录内（铁律 6，与 httpapi/spa.go
//     同一标准库底座）。
//
// 快照内容 = 完整 SQLite 库文件（含 argon2 口令哈希），属敏感数据；
// 端点面（httpapi/backups.go）全部走 Bearer 鉴权，见 docs/SECURITY.md。
package backup
