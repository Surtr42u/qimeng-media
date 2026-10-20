# API：上传、整理与回收站

> [API 目录与全局约定](../GUIDE_API.md)。本页解释用法，字段/路径/schema 以 [OpenAPI](../../api/openapi.yaml) 为准；正文日期只标记机制来源。

## 上传整理

端点：POST /assets/upload、**POST /uploads、GET/PATCH/DELETE /uploads/{id}、POST /uploads/{id}/complete**、POST /assets/{id}/move、GET/POST /dirs

直传 NAS（流式；
四道校验；
libraryId 必填查询参数，同名自动重命名 "名 (2).ext"，上限 upload.max_bytes 默认 2GB）；
**2026-10-01 断点续传批（ADR-0028，弱网大文件续传地基）**：新增 POST /uploads（创建会话：body {libraryId, fileName, size 必填, contentType/dir 可选——dir 与直传 dir 参数逐字同语义：库内相对路径、空/缺省=库根，create 校验规范化〔直传同一入口，非法路径 400〕后存入会话、complete 按其落位子目录}，创建即做文件名清洗/dir 目标目录/扩展名白名单/size 上限/库存在且启用/autoAccept 同闸六道前置校验，201 回 UploadSession{id,offset,size}）；
GET /uploads/{id}（断点探测权威 offset；
未知/已过期 404）；
PATCH /uploads/{id}?offset=（追加分片 octet-stream：offset 不等权威值 **409 响应体即权威 UploadSession**〔重同步后重发〕、累计超声明 size 400、单片超 32MB 413、磁盘 IO 错误 500 且会话保留）；
POST /uploads/{id}/complete（对拼装完成的整文件复跑直传四道终检〔魔数嗅探/扩展名/最终大小/路径穿越〕与同一入库管线——library.changed 照发、修订号照 bump、富化照跑，201 回与直传同构 AssetDetail；
**同名自动重命名 "名 (2).ext" 与直传逐字一致、永不 409**；
未传完 400、终检失败会话保留可重试）；
DELETE /uploads/{id}（放弃 204）。会话服务端持有（内存注册表 + DataDir/uploads-tmp 分片文件，服务端重启即全部失效、客户端重建会话），无活动 24h 过期清扫连临时文件回收（清扫不 bump 修订号）；
全部操作 Bearer 鉴权；
**直传通道一字未动，双端按 size 阈值分流——Android/Web 双端已接入（create 透传 dir，≥16MB 分片）**；
**2026-09-25 资产编辑批（ADR-0024）：原上传挂靠批（ADR-0023）增的可选 query `authorId`/`authorName`/`source` 三参数退役删除——上传回归纯上传（参数面仅 libraryId/dir/filename），挂靠入口迁资产编辑页（PUT /assets/{id}/authors，见「资产浏览」行与「关键机制」作者挂靠编辑）**；
移动/重命名保关联（目标冲突 409）；
目录树与新建（幂等）；
**2026-09-25 上传暂存批 / 2026-10-09 智能推荐增强：GET /assets/name-suggestions（作品名序号联想——三级匹配前缀>分词全包含>子串包含、支持中段角色命中；
序号解析扩充全半角圆括号/方括号/前缀标如No.等并保留原始位宽与前导零；
主体聚类轮转平权曝光不同角色彻底防单角色垄断；
无序号独立角色推基名+2；
cap 扩容至 8；
Android 端已在上传页与资产编辑页全面接线防抖调用；
**2026-10-09 第五百零九笔别名/出处反查**：输入角色别名（`女武神`/`Malenia`）或出处昵称（`老头环`）时先按模糊键（去标点/分隔符/空白后小写）反查内置词表，得角色别名与出处变体后以 cleanFuzzy 形态参与匹配（关联词命中不受无序号词边界过滤限制）；
`GET /search/suggestions` 同源反查只补**规范名**（角色/出处 canonical）且逐条校验库内存在；
libraryId 空 400/库不存在 404）**

## 上传整理（本机同步）

端点：**GET /local-sync/status、POST /local-sync/trigger**

本机文件夹自动同步通道（ADR-0030，2026-10-01 新增，路径计数 70→72）：通道状态只读（含 counts{waitingStable,failed,ignored} 与 recentSynced 最近 20）与手动触发一轮扫描（202 异步不等待完成，结果经 status 查询；
未启用 409 code=LOCAL_SYNC_DISABLED）；
通道由 `QIMENG_LOCAL_SYNC_ROOT` 配置启用（空=关），语义见「关键机制」本机自动同步通道

## 回收站

端点：GET /trash、POST /trash/{id}/restore、DELETE /trash/{id}、DELETE /trash

恢复（冲突自动重命名）/单个物理删除/清空（均二次确认场景）

## 本机自动同步通道

（2026-10-01，ADR-0030）：服务端轮询监测本地同步根目录（`QIMENG_LOCAL_SYNC_ROOT`，空=关），结构 = `<同步根>/<库名>/媒体…`（直接子文件夹名=库名，媒体**递归**入库、子目录相对路径映射库内子目录，成功后源文件 move 入库根=从同步源消失）+ `<同步根>/*.txt`（根级 TXT 走 importTxt 统一重建，成功后移 `<同步根>/.synced/` 内部归档目录——点前缀，扫描与同步均不处理，同名加序号）。**语义要点**：库名匹配 = sanitize（trim + `\/:*?"<>|` 九字符→`_`，锚点 App 归档 `InboxFileStore.sanitizeLibraryDirName`）后与库 name 精确比对（大小写敏感），0 命中/多命中/库停用/COS 库=失败原地保留绝不猜；
稳定门槛 = 文件 mtime 年龄≥stable_age 且跨轮 size/mtime 不变才处理（防半截拷贝）；
媒体走直传同款四道校验 + autoAccept 闸（false 时媒体暂停、TXT 不受影响）+ maxBytes，同名自动改名 "名 (2).ext" 永不 409（DOMAIN_RULES §9 同款复用），成功 ingestPlacedUpload（sysmon/upload.done/library.changed/修订号/富化全同款）；
失败 = 文件原地保留每轮重试、status items 可见；
TXT 恒 keep（无人值守绝不 remove）、仅 UTF-8（剥 BOM）、10MB 护栏；
安全 = 同步根与库根/DataDir 双向重叠禁令、隐藏条目与符号链接不处理、根不存在不建目录（详见 SECURITY.md「本机同步通道」）。**配置三键**：`QIMENG_LOCAL_SYNC_ROOT`（空=关）/ `QIMENG_LOCAL_SYNC_INTERVAL`（默认 30s）/ `QIMENG_LOCAL_SYNC_STABLE_AGE`（默认 60s），yaml `local_sync.root/interval/stable_age` 同名。**错误码**：`LOCAL_SYNC_DISABLED`（trigger 在未启用时 409）。**维护页入口**：Web 维护页「本机同步」卡（DbBackupCard 之后：状态徽标/信息行/lastError 警示/失败·忽略·最近成功三段列表/立即同步按钮）

## 文件操作失败重试

恢复返回500时保留回收站meta及已确定落点，可用同一trashId重试，原ID提交后才移除条目。续传complete入库失败时会话保留已发布路径及分片源，重试同一会话不会再生成改名副本；封存后PATCH返回既有400，客户端应继续重试complete或明确放弃。服务端重启使上传会话失效的既定限制仍在。路径包含静态符号链接/junction时拒绝写入；根目录及其祖先是链接时需注册真实路径。部署与外部换链边界见SECURITY。
