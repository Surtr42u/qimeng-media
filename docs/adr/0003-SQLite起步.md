# ADR-0003：SQLite 起步（含 Postgres 演进路径）

- 状态：已接受（2026-08-22）

## 决策

数据库用 **SQLite**（`modernc.org/sqlite` 纯 Go 驱动，无 CGO，交叉编译无痛），SQL 经 **sqlc** 生成类型安全 Go 代码，schema 演进只经 **golang-migrate** 迁移文件。

## 理由

- 内网单用户负载：读写并发远低于 SQLite 上限（WAL 模式下单机数万 QPS 读）
- 单文件 = 备份即拷文件（`VACUUM INTO` 在线快照），NAS 场景运维成本为零
- Gitea/PhotoPrism 等主流自托管项目同款选择，低配 NAS 内存友好
- 全文搜索用 FTS5（中文按字/trigram 索引），覆盖文件名/标签/作者/出处检索需求

## Postgres 演进路径（触发条件）

满足任一才考虑迁移：FTS5 中文检索质量不达标 / 多用户并发写入成为瓶颈 / 需要向量检索等扩展。迁移路径已预留：数据访问全部在 `store` 包 + sqlc，换库 = 重写 store 层 SQL + 一次性数据导出导入（单用户数据量分钟级）。在触发条件出现前迁移属于过度设计。
