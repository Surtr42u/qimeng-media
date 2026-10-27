# API：鉴权、配置与系统

> [API 目录与全局约定](../GUIDE_API.md)。本页解释用法，字段/路径/schema 以 [OpenAPI](../../api/openapi.yaml) 为准；正文日期只标记机制来源。

## 认证

端点：POST /auth/setup、POST /auth/login、POST /auth/verify、**POST /auth/logout**

首次设密码领 token（只显示一次）；
密码登录换新 token（换设备/清缓存找回通道；
每次登录签发独立会话，其他设备 token 不失效）；
校验 token；
**登出=吊销当前 Bearer 对应会话**（ADR-0021 多会话模型下单设备退出，其他设备会话不受影响；
幂等恒 204——已吊销 token 会先被 401 拦下）

## 认证（开发专用）

端点：POST /auth/dev-login

仅服务端 `auth_dev_mode=true` 时可用：免密码直接签发 token（未初始化自动建 admin 占位）。404 = 未开启，生产零行为变化；
适用范围及已接受的日常家庭 LAN 例外统一见 SECURITY「开发模式」；生产/远程/隧道关闭 dev。可选共享密钥门禁（2026-09-30 批A）：配置 `auth_dev_shared_secret` 时须携带 `X-Qimeng-Dev-Secret` 头且匹配否则 401，未配置不校验零影响——Android 内嵌形态随机注入防同机越权

## 客户端配置

端点：GET/PUT /config

设置页配置持久化于kv_settings.client_config；GET无记录默认workers=0（自动）、thumbEdge=800、autoAccept=true。PUT仍须四个叶子齐全，缺键/越界400；响应返回规范化全量。

workers=0按runtime可用CPU并发和可用内存保守分配1–8个同时扫描的媒体库，手动1–32。后续全量扫描准入读取最新配置，手动/轮询/监听共用名额，等待可取消；降低并发不打断运行任务。单库文件顺序不变，thumbEdge仍是预留。

maxBytesMb已退役为旧SDK必填兼容字段；PUT接受0–2147483647，服务端忽略具体值并固定回显2147483647。采用正数哨兵避免旧客户端把0视为全部超限；新客户端不据此限制单文件。upload.autoAccept实时开关保留，详情见[上传专题](files.md#上传整理)；分片暂存总预算/会话数及物理空间检查仍有效。

## 客户端异常

端点：POST/GET /client-logs

Web/Android 异常上报通道（kv_settings `client_logs` 环形缓冲最新 200 条，超出丢最旧）：POST 批量 1~50 条/请求、level 三枚举、message 超 2000 字 400（不截断）→ 204；
GET 新→旧（维护页排查表数据源，Web 上报器 lib/client-logs.ts、Android 上报器 core:data `diagnostics` 包〔2026-09-18 接线：全局崩溃 + Coil 图片错误 + 回前台补传〕写入）

## 系统

端点：/healthz、/readyz、/metrics、GET /system/status、**GET /library/revision**、**GET /thumbnails/progress**

探针协议面为 `/api/v1/healthz`、`/api/v1/readyz`（免鉴权，openapi.yaml `security: []`；
根路径 `/healthz` `/readyz` 是运维探针别名——docker/k8s 惯例，行为一致，不入协议面）；
readyz 检查数据库、数据目录与启用媒体根可读性/余量，失败 503；healthz 仅存活检查，不能用 readyz 失败直接推断进程崩溃。配置与边界见 [监控](../OBSERVABILITY.md#健康检查)。
/metrics 与 /system/status 要求管理 token；
负载/流量面板；新增appRxBytes/appTxBytes为本服务实例实际读取/输出的HTTP正文字节（重启归零），Web只采样这两个字段；netRxBytes/netTxBytes保留全机网卡口径。HEAD不计响应正文，ReaderFrom在输出结束后累计，不含HTTP头/TLS/其他进程或服务主动出站请求，详见[采样范围](../OBSERVABILITY.md#影库服务流量范围)。旧服务端缺应用字段须提示升级，不回退全机流量。
status 含 `devMode`（auth_dev_mode 透出，2026-09-30 批A——Web 维护页「开发模式未关」提醒条数据源）；
**GET /library/revision（2026-09-30 ADR-0026）**：全局库内容修订号及预取指纹 `{revision: int64, thumbnailVersion?: string}`（kv_settings 键 `library_revision`，Bearer 鉴权）——资产集合任何变更（扫描/上传/回收站/整理移动/标签/库删除/库开关/导入）单调 +1，事件订阅一处收口 + 物删/清空/清扫/导入四条不发事件路径显式 bump；
只保证资产集合面（收藏/进度/作者展示字段不保证）；
Android 预取同时比较 revision、thumbnailVersion 与服务器身份；策略变化或旧记录缺指纹必须重拉，其他跳过防线见 ADR-0026（清空缓存池会失效记录强制补齐）；
**GET /thumbnails/progress（2026-09-15 批，此前漏收于本文；
2026-10-04 分子口径修正）**：缩略图覆盖进度 `{totalAssets, thumbsOnDisk}`——分母=启用库资产总数，分子=已有 md 预生成档缩略图的资产数（逐资产判定、与预热候选同口径；
缓存键换代期旧键孤儿不进分子；
以扫描元数据定位当前表示，分页检查，复用近期回填快照及进程内 30s TTL 缓存；库变更通知使快照失效。分页期间的库变化、尚未被扫描发现的原件变化可能短暂滞后，实际生成/媒体出口仍复核原件版本），缓存进度页轮询数据源，纯统计无副作用。完整回填与覆盖规则见[缩略图专题](../domain/thumbnails.md#回填与并发)。

## 库修订号与预取策略指纹

thumbnailVersion 表示策略/格式/配置指纹，预取判断见上文系统端点；原件变更经 library.changed 推进 revision。

系统 status 的既有 version 字符串与 SSE hello 共用 `发布版本+提交短号[.dirty]`，缺 VCS 数据显式显示 unknown；只供识别运行包，不作为跨版本兼容判定。离线二进制身份检查见 [服务端入口](../../server/README.md#构建身份)。本轮不新增字段或改变 SDK。
