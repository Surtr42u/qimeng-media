# API：便携迁移与库文件快照

> [API 目录与全局约定](../GUIDE_API.md)。本页解释用法，字段/路径/schema 以 [OpenAPI](../../api/openapi.yaml) 为准；正文日期只标记机制来源。

## 迁移

端点：POST /import/qimeng-backup、GET /export/qimeng-backup

当前导出 schemaVersion=2；
OpenAPI的导入/导出及信封说明与此一致；保留Legacy类型名用于SDK兼容，不表示当前仍导出v1或保证旧客户端无损转存。
v2 按原始事件身份合并，v1 按聚合兼容回放。导入只关联既有媒体，不创建/删除媒体资产或主动清理历史事件；标签组按改动时间裁决，TXT按keep规则重建关联，不表示所有元数据都只增不减；
原件先行。完整版本化映射与幂等边界见 [迁移领域规则](../domain/migration.md)。

## 备份热备

端点：POST/GET /backups、PUT /backups/schedule、GET /backups/{name}/file、DELETE /backups/{name}

**2026-09-19 新增（任务Q 批B）**：SQLite 库文件级在线快照（VACUUM INTO，WAL 下安全不停服，SQLite ≥3.27），存 `DataDir/backups/qimeng-YYYYMMDD-HHMMSS.db`（本地时间命名）。POST 手动触发（201 返回 BackupInfo{name,sizeBytes,createdAt}；
**409 BACKUP_IN_PROGRESS** = 已有快照进行中——定时调度与手动共用防重入闸）；
GET 一次往返拿全 `{items, schedule{enabled,intervalHours,retention}}`（schedule 回显**当前生效值**：含 PUT /backups/schedule 覆盖值，未覆盖时为启动配置；
默认 true/24h/7 份）。**PUT /backups/schedule（2026-10-03 第四百四十笔，热生效批）**：全量提交三键 `{enabled, intervalHours(1–8760), retention(1–365)}`，服务端**先持久化再热生效、无需重启**——写 kv_settings（键 backup_schedule，migrations/0003 既有 KV 机制）后立即驱动调度器（interval 变更重置周期计时=从保存时刻重新起算；
enabled=false 只停定时面，手动触发仍可用；
retention 即时作用于下次轮转）；
校验失败 400 且两步都不做（无「看似生效、重启回退」半态）。**优先级：本端点保存值 > env `QIMENG_BACKUP_*` > yaml `backup:` > 默认**——保存过一次后 env/yaml 对调度三键不再生效，重新 PUT 即改写（覆盖值损坏时启动回落 env/yaml，不锁死调度）；
GET …/file 下载 `application/octet-stream` + Content-Disposition attachment（支持 Range）；
DELETE 删除单份（物理删除，204；
快照可再生不走回收站）。{name} 严格白名单 `^qimeng-\d{8}-\d{6}\.db$`（非法 400 INVALID_PARAM）+ 服务端 os.Root 锚定备份目录；
**快照含 argon2 口令哈希 = 敏感数据**，全端点 Bearer 鉴权（见 SECURITY.md「备份快照」节）。**恢复 = 停服 → 用快照替换数据目录库文件 → 起服（文档化手动流程，v1 无在线恢复端点）**。与「迁移」行旧版 JSON 备份（行为数据）互为补充：本行是库文件全量副本。**Web 管理面撤下（2026-10-04，用户拍板「用不到」）**：维护页「库文件快照备份」卡移除——服务端端点与自动备份调度原样运行（快照继续按计划生成/轮转），下载/删除单份可通过已鉴权 API 或受控读取 backups 目录；调度参数通过 API 热更新，未保存过覆盖值时也可用 `QIMENG_BACKUP_*`。

## 跨端迁移工作流（备份导入导出，2026-10-01 语义澄清）

本机模式导出（备份 qimeng_backup.json 落手机 SAF 目录）→ App 登录 NAS 服务器 → 导入按版本规则幂等合并（不创建/删除媒体资产；标签替换/TXT keep等规则见「迁移」行与DOMAIN_RULES §10）；
前提=媒体文件本体先行（走上传通道或 ADR-0030 本机同步通道入库，备份不含文件本体）；
TXT 作者片段已随备份迁移（keep 合并，2026-10-01 起；
import-txt 手动通道保留为备用）。

## 当前便携格式 v2

- GET `/export/qimeng-backup` 当前输出 schemaVersion=2；`data.viewEvents` 为事件真相（原身份/会话/时间/秒数/日键），旧聚合段保留给工具查看，history 全量、无 500 截断。POST 接受旧 v1 聚合回放与新 v2 身份合并；v2 必须携带事件数组（可为空），缺段或引用非法在任何写入前 400。已有事件按身份跳过，新事件按原始内容写入，`eventsReplayed` 仅计实际新增；不使用旧 exportedAtMillis 快速跳过，以免漏掉同时间导出的独立数据。

- v2 `mediaFiles` 新增 assetId/libraryName/relPath，recordKey 全份唯一；本库ID优先，跨库唯一匹配才导入，歧义/多对一跳过并 warnings 提示。v1 保持旧文件名/父目录/最早规则。未匹配条目仍跳过、不创建媒体。升级服务端及客户端后再导出 v2；旧客户端可能在转存时丢新字段，不能据其转存文件证明身份守恒。

导入由portablebackup编排。完整成功，或前段已提交而后段失败/取消时，都会推进库修订号、失效推荐并广播library.changed，使其他设备/标签页重校验已持久数据；该事件是变更通知，不代表整份导入成功。写前校验失败或取消且没有已提交段时不通知。HTTP仍返回原阶段错误，已提交段保留并可幂等重试；导出查询绑定同一事务快照。

## 快照调度与 UI 状态

快照调度 PUT 热生效，API 可读写；Web 调度卡已撤下。快照只含库文件，不含原件；恢复需停服替换库文件。停服恢复、独立原件副本、WAL 隔离及可重复演练见 [恢复指南](../RESTORE.md)；设备剩余验收见能力地图。
