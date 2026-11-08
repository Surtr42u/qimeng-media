# Android 前台能力与无头执行覆盖

> 维护日期：2026-11-08。说明 Android 各业务操作在前台、Go API、AI 工具与后台 Worker 四条通道上的覆盖情况；协议以 [api/openapi.yaml](../api/openapi.yaml) 为准，设备结果包细节见 [设备 API](api/device.md)，维护账本见 [维护 API](api/maintenance.md)，AI 工具见 [tools/ai_maintenance/README.md](../tools/ai_maintenance/README.md)。

## 1. 结论

- 绝大多数业务写入都由 Android 前台通过正式 Go API 完成；同一 API 已向独立 AI 工具开放的部分可以直接无头调用。
- 真正只能前台完成的只剩**本机系统交互**：SAF/目录选择器、"所有文件访问"授权、通知权限、分享/系统文件选择、应用内登录。
- 后台执行目前只有三类：设备结果包 Worker（15 分钟周期 + 立即链）、上传队列 Worker、浏览事件补传 Worker。其余操作需要前台进程或外部 AI 执行器经 API 触发。
- 手机内嵌单机模式的设备令牌由 App 用本机管理员会话经 `POST /api/v1/ai-clients` 自助生成；PC/NAS 模式仍由服务端本机 CLI 生成后填入。详见 [设备 API](api/device.md)。

## 2. 覆盖矩阵

图例：✅ 已具备；◐ 有 API/AI 工具但 Android 无对应前台入口；— 不适用或纯本机。

| 业务操作 | Android 前台 | Go API | AI 工具 | 无头/后台 |
|---|---|---|---|---|
| 浏览/搜索/筛选/统计 | ✅ | `GET /assets`、`/search/suggestions`、`/stats/*` | `asset.query`、`library.query`、`author.query` | 只读，可外部 AI 直接调用 |
| 详情/时间轴/播放 | ✅ | `GET /assets/{id}`、`/timeline-tags` | `asset.query` 组合 | 只读 |
| 点赞/收藏/进度上报 | ✅ | `PUT /assets/{id}/like|favorite|progress` | 无专用工具（scope `asset.write` 可调用） | 事件补传 Worker 负责浏览事件 |
| 资产标签编辑 | ✅ | `PUT/DELETE /assets/{id}/tags` | `asset.tags.replace` | 外部 AI 可无头执行 |
| 资产编辑/作者关联 | ✅ | `GET/PUT /assets/{id}`、`PUT /authors` | `asset.authors.prepare/apply` | 外部 AI 可无头执行 |
| 移动/改名/组图命名 | ✅ 单项 | `POST /assets/{id}/move` | `media.organize.prepare/apply` | 外部 AI 可规划/执行/回读 |
| 删除资产（进回收站） | ✅ | `DELETE /assets/{id}` | `asset.trash`（需确认） | 外部 AI 可无头执行 |
| 回收站恢复/清空 | ◐ 无 UI | `POST /trash/{id}/restore`、`DELETE /trash*` | `trash.list`、`trash.restore` | 外部 AI 可无头执行；清空需 `asset.delete` |
| 作者列表/新建/改名/删除 | ✅ | `/authors*` | `author.query`、`author.cleanup.*`、`author.merge` | 外部 AI 可无头执行（删除/合并需高风险 scope） |
| 作者公开档案/头像 | ✅ | `/authors/{id}/profile*` | `author.profile.read/prepare/update` | 外部 AI 可无头执行；头像抓取见研究工具 |
| 作者 TXT 导入/重建/镜像 | ✅ | `/authors/import-txt*`、`/authors/mirror` | 无专用工具（scope `author.write` 可直调） | 设备结果包 Worker 可应用 masterTxt |
| v2 备份导出/导入 | ✅ | `GET /export/qimeng-backup`、`POST /import/qimeng-backup` | `backup.export`（导入仍 planned） | 设备结果包 Worker 可应用 v2Backup |
| 维护目录选择/两代导出 | ✅（纯本机） | 仅 `PUT /authors/mirror` 副作用 | — | 需前台 SAF/所有文件访问 |
| 设备结果包领取/应用/回传 | ✅ 设置页 | `/device/apply-tasks*` | `device.apply.prepare/submit/status` | WorkManager 周期 + 立即链 |
| 库注册/扫描/启停/删除 | ✅ | `/libraries*` | 无 AI 写工具（scope `library.write` 可直调） | 前台或外部 AI 经 API |
| 上传（直传/分片） | ✅ | `/assets/upload`、`/uploads*` | 无专用工具（scope `asset.upload`） | 上传 Worker 串行执行 |
| 来源词表/合并同步 | ✅（客户端编排） | `/sources/custom*` | 无专用工具（scope `source.write`） | 需前台两段编排 |
| 偏好/外观/缓存清理 | ✅（纯本机） | `PUT /recommendations/prefs` | — | 纯本机 |
| 登录/切服/登出 | ✅ | `/auth/*` | — | 需前台会话 |

## 3. 仍无法完全无头化的操作

1. **SAF 目录选择与持久授权**：系统 `OpenDocumentTree` 必须前台交互；维护目录/备份目录路径不能在后台凭空获得。
2. **"所有文件访问"授权**：需跳转系统设置由用户确认；Worker 在 claim 前校验，不满足只提示、不消耗任务。
3. **通知权限**：内嵌服务前台通知需要用户授权；拒绝后后台拉起能力受限（待真机验证）。
4. **应用登录/本机管理员会话**：设备令牌生成依赖本机会话；会话失效必须前台重新登录。会话 token 不能替代设备令牌。
5. **纯本机设置**：缩略图缓存清理、外观偏好、扫描充电联动等不经过服务端，无法由远端 AI 触发。
6. **词表合并同步**：当前是客户端两段 GET+PUT 编排，没有原子服务端端点；外部 AI 只能分别调用（非等价）。

## 4. 后台执行边界

- 设备结果包 Worker：15 分钟系统下限 + 立即链；远端要求 `CONNECTED`，本机回环放宽为 `NOT_REQUIRED`；进程被杀后靠 pending+claimed 全量翻页恢复，不持久化段级进度。超过 2000 条积压明确报错，不无限翻页。
- 上传/事件 Worker：分别负责上传队列与浏览事件补传；不做业务编排。
- 内嵌 Go 服务由前台 Service 持有；应用被系统停止后不保证常驻，下一次触发（冷启动/Worker）重新拉起并等待端口就绪。Doze/厂商后台限制、后台 FGS 启动、SAF 撤回仍需真机验收。
- PC→手机：服务端设备结果包 API + 手机 Worker 已具备；设备配对/证明、包清理 GC、Web 任务中心仍未实现。

## 5. 相关文档

- [设备结果包 API](api/device.md)（manifest、expectedState、权威回读、状态机、错误码）
- [维护任务 API](api/maintenance.md)（账本、外部执行器受控领取、LEASE_LOST）
- [Android 后台执行分析](android-background-execution.md)（系统限制与待验证清单）
- [AI 工具平台](../tools/ai_maintenance/README.md)（工具目录、权限、计划/确认/回读）
- [SECURITY](SECURITY.md#独立-ai-客户端凭据scope-与审计adr-0057-p1p22026-11-08)（scope、限流、执行器租约）
