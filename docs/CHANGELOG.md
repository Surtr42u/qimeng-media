## fix(app): 标签取消勾选图标并将当前标签置顶（2026-11-13 第七百二十九笔）

执行 AI：Codex（完整模型名称及变体：本会话未提供可核实元数据；负责实现与验证）

- 按用户要求移除候选标签的勾选图标及空位，仅用边框与底色表示选中；保留复选状态读屏语义与短颜色过渡。
- 当前标签移至候选池上方，固定内容区高度、超出内部滚动；下方候选池独立滚动，避免选中/取消或摘要换行推动候选位置。名称自然排序、保存/解绑语义保持。
- 同步 GUIDE_UI 与 Android README；源码 `a4cbf188` 的[云端检查 43418766498](https://github.com/Surtr42u/qimeng-media/actions/runs/43418766498)首轮通过 Release 构建、内嵌完整性、Android 单测与 Release Lint。指定 qimeng_api35/隔离服务/24 个合成标签核验浅深色新包，确认无勾和空位、当前标签在上；从 6 项选到 10 项、上方内容滚动后，24 个候选与底栏坐标均未变，取消后仍恢复已保存六项。截图/坐标记录留忽略目录 build/tag-preview.local；文档检查 156 文件 0 错误，差异与隐私检查通过，未合并或更新现用手机。
- 本轮预览结束已移除临时测试用户 10（回到用户 0，列表只剩 Owner）、停止隔离服务并关闭模拟器；上一笔未完成的临时用户清理本轮已完成。最旧第六百九十九笔移入 CHANGELOG-20261107，正文保持、相对链接随路径修正。

## fix(app): 标签视觉层级与名称自然排序（2026-11-13 第七百二十八笔）

执行 AI：Codex（完整模型名称及变体：本会话未提供可核实元数据；负责实现、文档与验证）

- 手机详情标签池改为 14sp 中等字重、36dp 最小高度、12dp 圆角与细描边，选中采用主色容器底和固定勾选；增大行距，标题左对齐并展示已选数量，新建按钮使用「添加」文字。固定弹层/底部按钮及选中不移位沿用上一笔。
- 在标签编辑逻辑中统一按中文拼音/数字自然顺序排列；打开和新建时排序，勾选不重排，不改服务端/备份中的标签顺序。新增排序回归，现有 VM 场景覆盖乱序打开、新建插入与勾选顺序保持。
- 同步 Android README、GUIDE_UI；工具 README 按 ZCode 官方文档补充 MCP 添加入口、绝对路径、只读凭据示例和验证边界；Telegram token 与绮梦凭据分开。未修改 ZCode 设置/机器人、未签发真实凭据或创建自动任务。
- 文档检查 156 文件 0 错误、差异与隐私检查通过；源码 `349bd6ce` 的[云端检查 43403527356](https://github.com/Surtr42u/qimeng-media/actions/runs/43403527356)第二次通过 Release 构建、内嵌完整性、Android 单测（含新增排序回归）与 Release Lint。首轮未修改的 BackupViewModelTest「目录有文件导入走校验确认链路」失败，未跳过/修改用例，同源码复跑通过；保留首轮记录，不把第一次写成全绿。
- 指定 qimeng_api35、隔离服务（旧云端 Go 产物）、合成图片与 24 个标签完成新包浅/深色呈现、连续选中及摘要换行后候选/底栏坐标不变、保存六项后重开及取消草稿恢复的交互核验；证据留忽略目录 build/tag-preview.local。未向 8420 写测试数据，未合并/安装至现用手机。隔离服务已停、模拟器已关闭；临时用户 10 的移除返回失败，重新启动模拟器清理的组合命令被自动审批拦截（仅返回 blocked by policy），本轮未完成该用户清理。
- 最旧第六百九十八笔原样归档至 CHANGELOG-20261107，近期保持 30 笔。

## fix(app): 标签选择保留位置与短过渡（2026-11-13 第七百二十七笔）

执行 AI：Codex（完整模型名称及变体：本会话未提供可核实元数据；负责定位、实现与验证）

- Android 详情标签管理将全量池置于勾选摘要之前，选中不从池中移走，避免其余标签补位和摘要换行推动候选；弹层固定 80% 屏高、标题/底部固定、正文独立滚动，避免内容增长使底部锚定的整层上移；勾选图标固定占位，主色/文字作 120ms 过渡，并提供复选状态语义。
- 普通点选仍为保存前草稿，摘要 × 仍立即解绑已有文件关联；未改业务接口、Web 标签池或全局导航动画，未复制 Telegram 源码或增加依赖。
- 同步 GUIDE_UI 与 Android README。文档检查 156 文件 0 错误、只读隐私扫描与差异空白检查通过；源码 `97387790` 的[云端检查 43400494175](https://github.com/Surtr42u/qimeng-media/actions/runs/43400494175)通过 Release 构建、内嵌三件套断言、既有 Android 单测（含 core:model）与 Release Lint；实际呈现与设备交互未验收，未合并/安装至现用手机。
- 最旧第六百九十七笔原样归档至 CHANGELOG-20261107，近期保持 30 笔。

## fix(api): AI 工作流分序、改名防错与真实 MCP 闭环（2026-11-12 第七百二十六笔）

执行 AI：Codex（完整模型名称及变体：本会话未提供可核实元数据；负责实现、定向核对与验证）

- 显式资产改名在任何副作用前拒绝 U+FFFD/坏 UTF-8，沿用 INVALID_FILENAME；OpenAPI 同步 400，SDK 由云端 make sdk 生成并回收摘要锁。
- 命名引擎 1.1.0 / plan schema 2 按图片/视频分别续号与补页，类别参与指纹/验证，旧计划重新 prepare；完整目录占用检查保留。
- 平台/工具契约 1.5.0、规则 2026-11-12.1：新增 asset.upload.prepare/asset.upload/library.scan、media.ingest，复用统一 HTTP、计划、权限、读根与业务端点；流式上传稳定 uploadId，回读路径/大小/时间，扫描受理不冒充完成。
- 新增云端同 revision 临时 Go + 真正 stdio MCP 受限凭据集成，六项通过；命名 42 项、AI 回归 291 项及 Python 全集 395 项通过（后两者各 11 跳过，范围不同不叠加计数）。真实服务回归发现 MCP 吞掉远程 HTTP 错误，已保留状态/服务端 code/脱敏正文并补回归。SDK 来自云端 make sdk 43196658612；完整 CI 及文档门禁见 HANDOVER/PR，不沿用旧 head。
- 本次模型后台手动 stdio 调用 15 次（12 成功、3 预期拒绝），完成合成上传/fileRef/重放与作者 revision 回读；云端 Windows 产物身份与功能提交一致，临时资源已清理。该隔离验证阶段未触碰真实库/手机；本地未运行应用构建或自动单测，未宣称新 AI 盲测或其他客户端接入通过。
- 后续按用户“手机和电脑一起更新”授权，已安装同源 `9137ce0a` 云端产物：手机 0.2.2/code37 同签名保留数据覆盖，已安装 APK 回拉摘要一致；PC exe/Web 整体更新并隐藏启动，实际端口响应进程、healthz/readyz、Web 字节及 schema26/六表计数核对通过。同 SHA 完整 CI/安全/文档/MCP/双端出包全绿，签名/内嵌三件套与 artifact 摘要均匹配。未拉前台、未向真实库写测试数据；仅安装核验手机，未验收后台/SAF/Doze；详情和旧包留存见 HANDOVER。未来 Next 仍无手机端后端。
- 用户随后授权实际双端数据合并：手机新导出与 PC 快照全部匹配 7758 项，35 项标签组新增差异经正式 v2 导入使 PC 标签关联 1200→1258、增加 385 条原身份事件；回读标签/关联/22432 条便携事件及资料内容一致，手机已含电脑本次便携范围原有状态，无需反向重复导入。未查看私人内容、未改真实媒体，快照与回执留忽略目录；实例内 revision 和已知/未知标签时间各自保留，不把本次新增合并当严格最新覆盖/清空场景验收。Next 仓外规划补 R23/设计 §6.6/S31–S32（PLN-20261112-05），明确单后端版本校验、删除/清空保护与旧数据时间边界；未实施 Next 代码。
- 专题同步：领域 files、API files、SECURITY、工具 README、平台任务书、ADR-0056、HANDOVER/CAPABILITY_MAP；未来 Next 业务流程规划在独立仓外文件同步，不将现项目内嵌服务带入目标架构。最旧第六百九十六笔原样移入历史档，近期保持 30 笔。

## fix(deps): 修复 npm 告警并升级 Go 工具链到 1.27.4（2026-11-11 第七百二十五笔）

执行 AI：DeepSeek V4.1 Flash（主代理：依赖链定位/回移植/验证/文档）

- minimatch（GHSA-g3hv-75wg-m249）：workbox-build→glob 11 固定 minimatch 10（受影响 <11.0.1）。实测只覆盖 minimatch 11 而保留 glob 11 会使 PWA 预缓存匹配为空（96→0 项）；改为 overrides 将 glob 11.1.0→14.0.4（上游配套 minimatch ^11.0.5，锁到 11.0.7），filelist 的 minimatch 5 不在受影响范围且保持不变。
- deepmerge（GHSA-gcqx-w48m-w2h4 / CVE-2026-93753）：上游 4.3.1 后无新版本、维护者关闭修复 PR。新增 `web/vendor/deepmerge`，按发布产物 dist/cjs.js 回移植 [PR #274](https://github.com/TehShrike/deepmerge/pull/274) 的 `__proto__` 守卫，版本标为本地补丁 4.3.2，经 package.json workspaces 链接替换依赖树中的 4.3.1；来源/许可/退役条件见 vendor README，knip 忽略该工作区。
- Go 工具链（GO-2026-6859）：PR 门禁在 Go 1.27.3 上命中 net/http 标准库漏洞，官方修复版本为 1.27.4；`server/go.mod` 最低工具链升至 1.27.4，SECURITY 与 ARCHITECTURE 同步，不关闭或忽略扫描。
- npm 两项均属开发/构建链，不增加应用运行时依赖，不改接口、UI、业务规则；Go 仅补丁版本提升，不扩展为依赖批量升级。
- 验证：npm ci 干净安装、SDK 生成、knip 0 孤儿、tsc/build、vitest 41 文件 391 项通过；修复后 dist/sw.js 与修复前干净 HEAD 构建逐字节一致；npm audit 0 漏洞；go1.27.4 下 go build/vet 通过，govulncheck v1.9.0 0 可达漏洞（另有 2 个未调用包、6 个未调用模块告警）。Playwright 浏览器用例未在本地运行；Dependabot 告警需推送后由 GitHub 重新扫描，未在本笔声称已关闭。

## fix(tools): PR #53 本地执行安全与 Recipe 失败一致性（2026-11-10 第七百二十四笔）

执行 AI：DeepSeek V4.1 Flash（主代理：审查修复/集成/文档；子代理 A/B 修复范围见 PR #53）

- P0 原语部分失败：工具与原语结果统一在 recipe 循环检查；`partial`/`failed` 写入 artifact（保留成功/失败项）后停止后续步骤，不再记为 ok。恢复只重跑非 ok 步骤，`media.transfer` 对已成功且内容一致的目标、`device.push` 对已推送且远端大小一致的条目按 `alreadyPresent` 跳过，只重试失败项；执行前被策略拒绝的步骤标 failed 而非不确定。
- P0 覆盖安全：`media.transfer` 在任何写入前用 `os.path.samefile`/`dev+ino` 拒绝源目标同路径、硬链接或同一底层文件（`overwrite=true` 不能绕过，避免先截断后读取）；覆盖改为同目录临时文件 + size/SHA-256 校验 + `os.replace`，失败保留旧目标并清理临时文件；无覆盖发布优先硬链接，不支持时退回排他占位 + 原子替换。新增 `safefile.py` 承载文件身份/临时文件/发布，不复制业务逻辑。
- P1 只读一致性：`media.probe` 仅在含 `frames` 时提升为 `local.files.write` 条件副作用；纯元数据/dHash 查询仍只读；只读模式关闭 probe/fingerprint/scan 的媒体缓存写入，避免“声明只读、实际写盘”。
- P1 Gate 范围：Gate 只授权其后的具体步骤（显式 `authorizes`，否则到下一道 gate 之前）；恢复时核对 `authorizedSteps`，范围漂移拒绝续跑；条件跳过的 gate 沿用此前已完成 gate 的授权以保持既有 recipe 流程；一次 `--yes` 仍只通过一个门禁。
- P1 ADB 拉取：`device.pull` 默认拒绝已有本地目标；先拉同目录临时文件、核对远端大小后安全发布；失败/链接目标/未授权根不破坏旧文件；仍强制显式 `QIMENG_ADB_SERIAL` 并拒绝默认模拟器。
- 可选：transfer 对 fileRef 源不再回显绝对路径（返回 `sourceFileRef`/`sourceName`/`targetName`）；`files.search` 仍无状态 cursor、每页有界重扫并排序，超过 20000 条目只提示收窄，未改为索引/缓存（记录为已知限制）。
- 版本：`platformVersion` 1.4.1、`rulepackVersion` 2026-11-10.4；`media.probe`/`media.transfer` 原语 1.1.0、`ADB_PRIMITIVE_VERSION` 1.1.0、`RECIPE_ENGINE_VERSION` 1.1.0；`author.discover` 1.0.1 增加 `local.files.write`。
- 验证：`python -X utf8 -m unittest discover -s tools -p "test_ai_*.py"` 288 项通过（新增 `test_ai_pr53_fixes.py` 22 项，覆盖部分失败/恢复、同文件/硬链接、安全覆盖、probe 权限、Gate 范围、模拟 ADB 拉取）；`check_docs` 0 错误、隐私检查通过、规则一致性 0 条。未接真实设备/NAS/真实媒体；普通路径检查与 `os.replace` 之间仍有极短竞态窗口，ffprobe/ffmpeg 读取换链窗口未消除。

## feat(tools): AI 接入层本地文件引用、执行权限与工具契约规范化（2026-11-10 第七百二十三笔）

执行 AI：DeepSeek V4.1 Flash（主代理：接口协调/集成/文档/验收；子代理 A：本地权限、fileRef、Recipe；子代理 B：注册表、按需上下文、规则、MCP）

- 本地执行域：新增 `QIMENG_AI_READ_ROOTS`/`QIMENG_AI_WRITE_ROOTS` 读写根分离（默认拒绝未授权目录，媒体根计入读根，工作根/`.test-temp/`/`build/` 始终可用）；写路径拒绝根以下符号链接/junction/reparse 与 `..` 逃逸，哈希/复制前后复核，默认不覆盖、不删源。未入库文件可在授权目录直接处理；已入库资产/作者/标签/业务关联仍只走 Go `/api/v1`。
- 逻辑存储与 fileRef：新增 `QIMENG_AI_STORAGES`、`fileref.py` 与 `files.storages`/`files.search`；只返回 storageId/相对路径/不透明引用（storageId+相对路径+size+mtime），不返回物理绝对路径；文件变化返回 `FILE_REF_STALE`，伪造/越界 `FILE_REF_INVALID`，storage 移除 `FILE_REF_UNAVAILABLE`。`media.probe`/`file.hash`/`media.transfer` 源/`research.media.probe.fileRefs` 可复用 fileRef，原 `path` 参数兼容。
- 原语统一契约：`PrimitiveSpec` 声明权限/只读/副作用/确认/超时；`execution.policy_denied_error` 对有副作用原语强制 `QIMENG_AI_READONLY`、`QIMENG_AI_DISABLED_TOOLS`（支持原语 ID）与 `QIMENG_AI_GRANTED_PERMISSIONS`，新增 `local.files.write`/`local.device`。recipe 恢复校验产物 bytes/SHA-256、running 副作用步骤返回 `RECIPE_STEP_UNCERTAIN`、工具/原语版本变化拒绝续跑；transfer 仅恢复上下文对相同内容幂等，`TARGET_EXISTS` 语义保持。
- 工具发现与规则：`files.*` 归 media 域；MCP 领域过滤始终保留 `platform.*` 与 `files.storages`，`tools/list`/`tools/call` 同源；`platform.rules` 支持有界点路径；catalog/context/describe 统一 `truncated` 标记，`platform.context` 优先加载 permissions/localFiles，再按需截断通用规则；工具状态拆分 registered/implemented/contract/runtime/locallyAuthorized/serverVerified；`rulepack.consistency_problems()` 自动检查规则引用、示例 Schema、playbook/recipe 权限覆盖与版本；`schema.py` 关键字与实际实现对齐并拦截未支持关键字。
- 修复 `research_kit` 目录工件与路径混用：`_directory_artifacts` 返回 `Path` 列表而不是已序列化字典，避免 `research.media.probe`/`research.avatar.fetch` 成功返回时 `TypeError`；新增 fileRef 成功探测回归。
- 版本/文档：`platformVersion`/`toolContractVersion` 1.4.0、`rulesVersion`/`rulepackVersion` 2026-11-10.3、48 个工具；`tools/ai_maintenance/README`、`docs/AI_PLATFORM_OPERATIONS`、`docs/AI_PLATFORM_TASKBOOK` §1.4/§4.4/§11、`docs/SECURITY`、ADR-0056 补充、HANDOVER/CAPABILITY_MAP 同批更新。
- 验证：`python -X utf8 -m unittest discover -s tools -p "test_ai_*.py"` 266 项通过（A 39 项、B 24 项、主代理跨模块 6 项）；`python tools/check_docs.py` 0 错误；`consistency_problems` 0 条。隔离合成测量（60 文件/3 层/真实 1 秒 mp4）：路径式 5 次调用/1799 字符/2 次绝对路径暴露 vs fileRef 3 次/1447 字符/0 次，两次流程均定位正确且 `media.probe` 成功；重复 probe+hash 载荷 508→546 字符（fileRef 字符串比短路径长），收益在调用次数、路径隐藏与复用，不宣称固定 token 百分比。
- 限制：未安装、未接真实 AI 客户端/MCP 客户端、未对真实媒体/8420/NAS/真机执行；stateless cursor 在 20000 条目扫描上限处只能提示收窄而不能翻完；ffprobe/ffmpeg 外部进程读取仍有检查后换链窗口；fileRef 是配置内自描述引用而非签名令牌。

## fix(tools): Windows 启动前预检 winnat 保留端口（2026-11-10 第七百二十二笔）

执行 AI：DeepSeek V4.1 Flash（子代理：Windows 启动端口预检）

- 新增 `tools/ensure-listen-port.ps1`（PowerShell 7.1+，无 5.1 回退）：启动前检查已有 IPv4/IPv6 监听与 `0.0.0.0` 绑定，识别 WSAEACCES 和 `netsh` 保留段；仅在确认无 WSL/Docker/Hyper-V 负载时临时停止 `winnat`，有负载时只打印手动命令，绝不结束进程。
- `_server-common.cmd` 三种启动模式在拉起新服务前调用预检（使用 `pwsh`，缺失时明确报错并停止）；设置 `QIMENG_LISTEN` 时保持用户值，预检按其中端口检查。启动器回归夹具同步复制预检脚本；新增合成 `netsh` 解析测试并接入 Windows CI。
- 文档：`server/README.md` 新增故障排查（保留段、`winnat`、退出码、`QIMENG_LISTEN`），`deploy/README.md`/`tools/README.md` 补入口。验证：真实 8420 占用识别（exit 3）、空闲 18420（exit 0）、合成保留段解析（pwsh 7.6.9）；未在活跃 WSL/Docker 环境验证自动停服分支。

## feat(tools): AI 工具平台 1.1.0：43 工具、执行器/设备/研究/回收站能力（2026-11-08 第七百二十一笔）

执行 AI：DeepSeek V4.1 Flash（主代理：合并/Go 权限/执行器/设备核验/集成；子代理：AI 工具平台、Android、文档）

- 工具注册表 29→43：新增 `maintenance.claim/heartbeat/step.record/artifact.record/complete`、`research.image.pipeline/search`、`research.author.probe/media.probe/avatar.fetch`、`trash.list/restore`、`asset.trash`、`asset.tags.replace`；`backup.export` 与 `device.apply.prepare/submit/status` 由 planned 转 supported。`platformVersion`/`toolContractVersion` 升 1.1.0，`rulesVersion`/`rulepackVersion` 升 `2026-11-08.2`。
- 错误目录新增 `PERMISSION_DENIED`/`TOOL_DEPRECATED`/`FORBIDDEN_SCOPE`/`LEASE_LOST`；工具权限与 Go scope 对齐（asset.write/asset.upload/asset.delete/tag.write/source.write/library.write/trash.restore/maintenance.execute），执行器工具以 `leaseToken` 作为能力凭据，只在 claim 结果出现。
- 文档：`tools/ai_maintenance/README`、`rules.json`、`docs/AI_PLATFORM_OPERATIONS`、`docs/AI_PLATFORM_API_GAPS` 与 HANDOVER §10、CAPABILITY_MAP 同批。验证：Python 164 项通过；未接真实 AI 客户端、未安装、未对真实库执行写入。

## fix(app): 设备任务全量翻页与本机模式设备令牌自助生成（2026-11-08 第七百二十笔）

执行 AI：DeepSeek V4.1 Flash（主代理：合并/Go 权限/执行器/设备核验/集成；子代理：AI 工具平台、Android、文档）

- 领取/待办列表改为沿 `nextCursor` 读到底（100 条/页，20 页硬上限 2000），修复只读第一页导致第 51 条之后任务永远轮不到；本地/远端共用同一 runner。
- 本机单机模式新增「生成本机设备令牌」：用内嵌实例管理员会话先 `GET /ai-clients` 复用/轮换，必要时创建 `android-local-<device>`（device.apply + deviceIds 绑定），明文 token 只写设置存储，不回填 UI/日志/state；远端模式不提供该入口。
- 设置页新增只读「最近设备任务」卡（20 条，设备令牌），不参与领取/应用；README 同步边界。
- 验证：`:core:data` device/token 测试 50 项通过，`:feature:manage`/`:app` `compileDebugKotlin` + `hiltJavaCompileDebug` 成功。未安装、未真机/模拟器验证；Doze/FGS/SAF 仍未验。

## feat(server): 设备结果包服务端权威状态校验（2026-11-08 第七百一十九笔）

执行 AI：DeepSeek V4.1 Flash（主代理：合并/Go 权限/执行器/设备核验/集成；子代理：AI 工具平台、Android、文档）

- manifest 新增可选 `expectedState`（assets/authors/assetAuthorRefs/tags/assetTagRefs/viewEvents，非负整数；未知键/负数 400）；回传结果时服务端读库内绝对计数逐键比较，并核对 `deviceLocalHashes` 与登记段摘要（少报/不符记 `hashVerified=false`，不改变回传状态码）。
- 结果新增 `authoritativeState`/`expectedStateMatches`/`expectedStateDiff`/`hashVerified`；migration `0026_device_apply_verification` 只增 nullable `verification_json`，`result_json` 仍是幂等重放比较基准，相同结果重放不重算、不产生 409，旧行保持 NULL。
- 文档：`docs/api/device.md`、OpenAPI/SDK 由同批提交维护。验证：deviceapply/httpapi 定向测试（临时 SQLite、合成包）。未安装、未真机、未向真实库回传。

## feat(server): 维护任务外部执行器受控通道与 worker 恢复修复（2026-11-08 第七百一十八笔）

执行 AI：DeepSeek V4.1 Flash（主代理：合并/Go 权限/执行器/设备核验/集成；子代理：AI 工具平台、Android、文档）

- 新增 `POST /api/v1/maintenance/executor/claim`（无任务 204）与 `jobs/{id}/heartbeat|steps|artifacts|complete` 五个端点，scope `maintenance.execute`；服务端每次领取生成 fencing `leaseToken`（30–1800 秒，默认 120），暂停/取消/终态/租约过期/重领后旧 token 一律 409 `LEASE_LOST`，步骤/工件在租约事务内校验后写。
- claim 返回**完整** `completedSteps`（步骤详情仍限 50 条）；worker 恢复按完整已完成/跳过步骤名跳过，修复 >50 步任务重跑已完成步骤的副作用。
- 文档：`docs/api/maintenance.md` 与 OpenAPI/SDK 同批。验证：maintenance/httpapi 定向 `go test`。未跑全量/race/云端 CI，未接真实外部执行器或 NAS。

## feat(server): AI 客户端按客户端令牌桶限流（2026-11-08 第七百一十七笔）

执行 AI：DeepSeek V4.1 Flash（主代理：合并/Go 权限/执行器/设备核验/集成；子代理：AI 工具平台、Android、文档）

- 每客户端默认 600 次/分、突发 120；`QIMENG_AI_RATE_LIMIT_PER_MINUTE`/`QIMENG_AI_RATE_LIMIT_BURST` 可调，0 关闭。超限 429 `RATE_LIMITED` + `Retry-After`；限流在 scope 检查之前，写请求照常记审计。
- 限流为进程内单实例实现（空闲桶 10 分钟回收）；重启清零，多实例共享配额未实现。
- 验证：aiauth/httpapi 定向测试；未安装、未做真实客户端压测。

## feat(server): AI 客户端 HTTP 管理、审计查询与写 scope 扩展（2026-11-08 第七百一十六笔）

执行 AI：DeepSeek V4.1 Flash（主代理：合并/Go 权限/执行器/设备核验/集成；子代理：AI 工具平台、Android、文档）

- 新增 scope `asset.write`/`asset.upload`/`asset.delete`/`tag.write`/`source.write`/`library.write`/`trash.restore`/`maintenance.execute`；method+path 映射扩展到上传、标签、来源、库、回收站与执行器端点，未映射写请求仍默认 deny。
- 新增 `GET/POST /api/v1/ai-clients`、`DELETE /api/v1/ai-clients/{id}`、`POST /{id}/rotate` 与 `GET /api/v1/ai-audit`：仅管理员会话可用，AI token 即使带 admin scope 也返回 403 `FORBIDDEN_SCOPE`；明文 token 只在创建/轮换响应出现一次，审计按客户端/动作/目标/结果/请求 ID 记录；撤销/轮换经 5 秒缓存生效。
- 文档：OpenAPI/SDK/`api/sdk.lock`、`docs/AI_PLATFORM_API_GAPS`、SECURITY 由同批提交维护。验证：aiauth/httpapi 定向 `go test`。未安装、未跑全量/CI、未接真实 AI 客户端；Web 管理 UI 未做。

## merge: 合并 PR #44 外部 AI 平台与 PR #45 作者 TXT 硬约束（2026-11-08 第七百一十五笔）

执行 AI：DeepSeek V4.1 Flash（主代理：合并/Go 权限/执行器/设备核验/集成；子代理：AI 工具平台、Android、文档）

- PR #44（外部 AI 接入平台、维护执行面、Android Worker 源码）与 PR #45（作者 TXT 作品行硬约束、研究工具包）合并入 master；唯一文档冲突 `docs/domain/migration.md` 按两代备份口径 + `authorProfileConflict=replace` 解析。
- 合并后本轮增量见第七百一十六～七百二十一笔；现装仍为 796d813f，新代码未构建安装，合并后云端 CI 待跑。

## fix(app): 设备领取本地前置与 claimed 恢复收尾、worker 测试 lint 修复（2026-11-08 第七百一十四笔）

执行 AI：DeepSeek V4.1 Flash（主代理；android-client 只读复核与实现）

- Android 设备领取复核（task-19）后修复：新增 DeviceApplyPreconditions，claim 前校验登录会话→维护根→所有文件访问，缺项只记录本机提示并结束、任务保持 pending（不再消耗成 failed 终态），授权后可「立即应用」重试；Remote 增 listByStatus，Runner 同查 pending+claimed（claimed 未过期按 claim 幂等继续/对账，终态跳过），Policy 按过期时间筛选；设置页令牌只显示已配置/替换清除，不回填明文 state；docs/api/device、android/README、docs/android-background-execution 修正未登录不领取、claimed 恢复、本机单机模式无令牌创建通道等口径；补 Preconditions/Policy JVM 测试。
- 修复 CI run 42389606438 服务端 lint 失败：worker_test.go:671 `||` 两侧相同表达式 SA4000，改为先赋值再判断，语义不变（ad5cf3da）。
- 验证：go test 定向 lint 用例通过；Android 复核/编译由子代理与 CI 复验；未跑全量、未真机/安装。

## docs(ai): 本轮收尾：9 项修复、AI 凭据 P1、Android 后台 Worker 与验证边界（2026-11-08 第七百一十三笔）

执行 AI：DeepSeek V4.1 Flash（主代理）

- 汇总并同步本轮交付：9 个 PR #44 Codex inline finding/用户报告问题的修复（工具层 5 项、Web 两代备份、Android 历史备份、Worker 租约、MCP 取消）、工具层按需工作流 `platform.playbook` 与 MCP 异步化、Go 侧 ADR-0057 P1 首批（AI 客户端凭据/scope/审计/设备级隔离/本地 CLI）、Android 后台设备结果包 Worker 源码。
- 文档：taskbook 增 §4.5（按需模板/独立部署）与 §9.6（Android 后台执行与 AI 权限适配）、§13 状态与 §14 矩阵；CAPABILITY_MAP 增 AI 凭据/设备级隔离与 Android Worker 行；ADR-0057 状态与实现补充、ADR INDEX；HANDOVER §9.1/§9.3 交付与边界；新增 [独立 AI 平台部署升级边界](AI_PLATFORM_OPERATIONS.md) 与 [Android 后台执行分析](android-background-execution.md)；tools/ai_maintenance/README、docs/SECURITY、docs/AI_PLATFORM_API_GAPS、docs/api/device、android/README 随各提交同步。
- 验证边界：本机定向通过（AI 工具 regression 12/catalog 22/MCP 11；Web vitest 24、tsc；Android JVM 34 + compileDebugKotlin、备份 28；Go build/vet + maintenance 定向；check_docs 147 文件 0 错误、check_privacy passed）；**未跑全量测试/云端 CI/真实库/真机/安装**，未合并 PR、未部署。待集中验收清单见 HANDOVER §9.2/§9.3。

## fix(server): 维护 worker 租约丢失取消在途 handler、续租错误分类、Claim 失败释放租约（2026-11-08 第七百一十二笔）

执行 AI：DeepSeek V4.1 Flash（子代理 go-worker2）

- 依据 go-worker 对 7ce3bc79 的只读复核修复残余窗口：process 为每笔任务建 jobCtx，心跳 ErrLeaseLost/连续非租约失败/租约余量过小时 cancel，handler.Execute 改用 jobCtx；Execute 返回后按 jobCtx 判定 interrupted，不写终态、不误标 failed，已提交步骤保留、重领后重跑未完成步骤（P2-1）。
- 步骤边界心跳非租约错误不再 failJob（warn+cancel+停止本槽，等 TTL 重领）；AdvanceStatus/settleStages 临时错误同策略（P2-2）。Claim 提交后 Get 失败时新增 ReleaseMaintenanceLease 尽力释放本次租约再返回错误（P3-3）。main 等待 worker 收敛加 shutdownTimeout，超时记录后继续关库（P3-5）。
- 新增测试：Start+Concurrency=2+阻塞 handler+Pause/Resume 取消、瞬时心跳错误不 failed、领取后详情失败可立即重领；go build/vet/maintenance 定向测试通过。残余：取消依赖心跳发现（最长一个心跳间隔）；立即 resume 的极短窗口仍未完全消除，需后续协调/可重试语义评估；未改 API/协议/migration。

## feat(app): Android 后台设备结果包 Worker、设备级令牌与 SAF/权限适配（2026-11-08 第七百一十一笔）

执行 AI：DeepSeek V4.1 Flash（主代理代提交；独立子代理实现与 JVM 验证）

- 复用设备结果包协议与维护账本实现后台 Worker：core/data/device 14 个文件（Runner 编排领取-校验-应用-回读-回传；Worker/Scheduler/WorkSpec 15min 周期+一次性、CONNECTED、unique work+Mutex；Remote/Policy/Authorization/SegmentVerifier/ResultPlanner/LocalBackup/Readback/SettingsRepository/Models）；feature/manage 设备领取页/VM 与维护页入口（设备标签、口令式设备令牌、后台开关、立即应用、最近摘要）。
- 设备授权：专用 AI 令牌（device.apply + device_ids）只用于设备端点，无令牌不回退会话 token；本机模式先拉起内嵌服务；维护根+所有文件访问门禁，缺失回 failed 并提示 UI 授权；先导出两代备份再 import-txt/qimeng-backup，按 expectedReadback 回读比较后回传 result，清理临时段；409 先对账终态不覆盖。
- 验证：:core:data:testDebugUnitTest device.* 34 tests 通过、feature:manage/app compileDebugKotlin 通过、check_docs 0 错误、check_privacy passed。未安装、未真机；Doze/厂商后台、后台拉起内嵌 FGS、SAF/存储授权撤回、PC→手机端到端、断网/进程被杀恢复待验证；无段级进度持久化，进程强杀可能重下整包。

## feat(server): 独立 AI 客户端凭据、scope 强制校验与写审计（2026-11-08 第七百一十笔）

执行 AI：DeepSeek V4.1 Flash（子代理 go-worker 实现与本地定向验证）

- ADR-0057 P1 首批（不改 OpenAPI/sdk.lock/三端、不加 HTTP 管理端点）：migration 0025 只增 ai_clients（token_hash 唯一、scopes_json/device_ids_json、expires/revoked/last_used）与 ai_audit（只增、无外键）；新包 server/internal/aiauth（scope 白名单/未知拒绝、token 生成+auth.TokenHash、过期/撤销 fail-closed、5s 缓存+Reload、审计、Create/List/Revoke/Rotate）。
- httpapi：管理员会话先行验证（行为不变），未命中再验 AI 凭据；AI 按 method+path 强制 scope（GET/HEAD 兜底 read，维护/备份/研究/设备各自 scope，未映射写默认 403 FORBIDDEN_SCOPE），管理员不受限；AI 写请求含被拒写审计；设备级隔离 device.apply + ai_clients.device_ids，列表/详情/领取/段/回传/取消校验 targetDevice，越权不改状态。
- 管理入口：qimeng ai-client create|list|revoke|rotate -config，直接打开同一 SQLite（先幂等 migration），明文 token 只 stdout 一次；撤销幂等、轮换旧 token 立即失效。验证：go build/vet、aiauth/httpapi/cmd 定向测试通过；未跑全量/CI/真实库。边界：协议化 scope、HTTP 管理端点、限流预算、审计查询、设备配对未实现；异地撤销约 5s 缓存生效。

## fix(server): 维护 worker 租约改为每次领取唯一 fencing token 并校验未过期租约（2026-11-08 第七百零九笔）

执行 AI：DeepSeek V4.1 Flash（子代理 go-worker2）

- 根因：Worker 实例 owner 跨领取复用，Claim/Heartbeat/Complete/Advance 只比较 owner 字符串；租约过期被重领后旧执行上下文仍可续租/覆盖步骤工件终态，同任务可并发执行。修复：实例身份 qimeng-worker-<pid>-<uuid> + 每次领取唯一 fencing token；Heartbeat/Finish/Advance SQL 增 lease_until 未过期条件；StepInput/ArtifactInput 增 LeaseOwner，在同一 _txlock=immediate 事务内校验 owner+未过期后写步骤/工件；ErrLeaseLost 只停止不误标 failed，暂停/取消进行中步骤保留 running。
- 验证：go test ./internal/maintenance/ -count=2、go vet、go build、gofmt、sqlc 重生成稳定；新增身份/token 唯一、同实例两次领取 token 不同、旧实例接管后不得覆盖、过期租约全链路围栏测试。未加 migration、未改 API/SDK/业务语义。

## fix(web): 两代备份写入中断恢复与轮换幂等（2026-11-08 第七百零八笔）

执行 AI：DeepSeek V4.1 Flash（主代理代提交；子代理 web-client 实现与定向验证）

- 根因：先整代轮换当前→上次再写新当前且无事务标记；中断后重复轮换/半成品被当上次代/正文摘要分开写导致永久摘要不符或丢失完整旧代。修复：新增 maintenance-generation.ts 两代写入原语与恢复判定；写快照前落 .两代轮换状态.json（old/next 摘要），整代轮换（相同跳过、缺摘要补）、写新当前（正文先摘要后）、最后删状态；下次写入按断点自愈，不可判定拒绝并保留现场；相同数据重复导出只刷新当前并修复过期摘要；旧 flat3 只读兼容不变。
- 验证：maintenance-folder.test.ts 24/24、tsc --noEmit、oxlint 0；未跑全量 vitest/Playwright/vite build/真实服务端。待测：真实浏览器崩溃中断自愈、真实 FS removeEntry 失败、跨浏览器差异。

## fix(app): 修复两代布局历史备份恢复读取（上次代跨目录/旧 flat3 只读回退）（2026-11-08 第七百零七笔）

执行 AI：DeepSeek V4.1 Flash（主代理代提交；子代理 android-client 实现与 JVM 验证）

- 根因：上次代在独立目录 上次/备份/影库备份.json，恢复入口仍按当前目录+影库备份（上次）.json 拼路径，历史恢复永远读不到；偏好改指当前代后旧 flat3 历史文件也不可达。修复：新增纯 JVM MaintenanceBackupSlots 按槽解析真实文件（两代优先/旧 flat3 只读回退/摘要核对）；MaintenanceFolderRepository 增 readManagedBackup+managedBackupStatus；AutoBackupRunner.readBackup 返回真实文件名+字节，BackupViewModel 以真实名进 v2 校验链并按上次/上上次分槽提示；维护/备份页三代文案改两代。
- 验证：本机 kotlinc+JUnit 28 项 JVM 通过（临时目录）；未跑 Gradle/CI/真机。待测：恢复上次成功路径、旧 flat3 回退、64MB 摘要、SAF 旧目录回退；UI 历史列表/迁移入口未接线。

## fix(tools): 修复计划实例绑定、删除前复检、合并参数、作者名回读与媒体目录筛选（2026-11-08 第七百零六笔）

执行 AI：DeepSeek V4.1 Flash（主代理代提交；子代理 ai-core 实现与定向验证）

- 对应 PR #44 Codex inline finding/用户报告 1/2/4/5/6：1) 媒体目录筛选改用 GET /assets directory（精确、不递归、空串=库根），修正 AssetSummary 无 relPath 导致的子目录恒空/根目录误选；_directory 拒绝绝对路径/盘符/反斜杠/空段/./..，media 能力声明补 directory。2) plans.verify_plan 增 instance 校验，跨实例计划抛 PRECONDITION_FAILED(instance_mismatch)，profile/cleanup/asset apply 传 ctx.target，media apply 等价校验。3) cleanup apply 的 inventory 签名扩展 profile revision/status/empty/hasXProfile+sources，执行前整体复检、delete 前逐项复检，变化拒绝并保留已执行步骤。4) cleanup merge 分支不再无条件读 authorId，按动作读取必填参数修复 KeyError。5) asset.authors apply 回读按服务端别名保守归一与 PUT 前作者索引识别按名解析/复用作者，其余额外 id 仍 READBACK_MISMATCH。
- 验证：新增 test_ai_regressions 12 项 + 受影响 24 项通过；未跑全套/CI/真实库。

## feat(tools): 按需工作流模板 platform.playbook、MCP 调用异步化与独立部署文档（2026-11-08 第七百零五笔）

执行 AI：DeepSeek V4.1 Flash（主代理）

- 用户 2026-11-07 补充要求：统一管理操作提示词/业务规范/工作流、减少对源码与完整文档依赖、MCP 调用期间处理取消、平台独立部署与版本。新增 platform.playbook（按领域列模板或按名取单个，规则节选有界，模板可选不强制路径）；rules.json 增 playbooks（author.profile/media.organize/author.cleanup/maintenance.jobs/backup.rotate）与 toolCatalog.discovery，版本升 2026-11-07.6；CLI 增 playbook 子命令，MCP initialize 指引更新。
- MCP serve_forever 改为 tools/call 独立线程 dispatch + 写锁 + 并发上限（QIMENG_AI_MCP_MAX_CONCURRENCY，默认 4）+ 有副作用工具全局串行；取消通知在调用期间可处理，只读工具立即 -32800、有副作用工具等真实结果（避免误以为未执行）；完成后清理取消标记防 request id 复用误判；新增/更新 MCP 取消与串行化测试。新增 docs/AI_PLATFORM_OPERATIONS.md（CLI/MCP 独立启动停止、配置、版本兼容、升级、NAS 边界）。
- 验证：test_ai_catalog 22、test_ai_mcp 11、test_ai_toolkit 47、test_ai_maintenance 31 通过；未跑全量与 CI。

## docs(ai): Go 业务 API 缺口与 AI 客户端权限模型方案（2026-11-07 第七百零四笔）

执行 AI：DeepSeek V4.1 Flash（主代理）

- 按用户 2026-11-07 追加长期架构要求落方案：[Go API 缺口与 AI 客户端权限模型](AI_PLATFORM_API_GAPS.md) 复核作者/媒体/文件/备份/任务既有接口（结论：基础操作完整，缺口集中在 AI 客户端身份/权限/审计），列 G1–G8 缺口（AI 客户端凭据+scope 撤销审计、业务审计归属、服务端高风险确认、外部执行器领取、批量能力、设备配对、作品/剧集模型、限流预算）与分阶段计划；新增 [ADR-0057](adr/0057-AI客户端凭据与细粒度权限模型.md) 与索引。
- 权限模型：AI 客户端凭据与用户会话分离、可轮换/撤销、scope 集合（read/research/author.write/asset.author.write/media.organize/backup.export/maintenance.read|write 与高风险 author.destructive/backup.restore/admin.*）；低风险写可在授权范围内自动执行，高风险单独 scope+动作级确认；权限由 Go 中间件与服务层强制，审计按客户端/动作/目标/结果/请求 ID 归属；拒绝任意 SQL/文件/shell；长任务复用维护账本；NAS 底座与 AI 平台独立升级。
- 本 PR 只收尾现有代码与测试，不改 Go/OpenAPI/migration；ADR-0056 补充工具目录/惰性加载/权限分离；taskbook §4.4/§5.4、CAPABILITY_MAP、HANDOVER §9.3、tools README 同步。验证：check_docs 0 错误、tools 单测 205 项本机通过（扩展性 18 项）、check_privacy passed；Go 与三端未改；云端 CI 见 HANDOVER §9.2。未安装、Go 权限实现未开始（P1+）。

## feat(tools): 工具目录按需发现、惰性模块加载与权限分离（2026-11-07 第七百零三笔）

执行 AI：DeepSeek V4.1 Flash（主代理）

- 按用户 2026-11-07 扩展性要求做最小增量（仅 tools/ai_maintenance/ 与文档，不改 Go /api/v1、OpenAPI、schema、三端）：新增 platform.catalog 精简领域目录（author/media/research/backup/device/maintenance/system，28 个工具；无 Schema，默认隐藏已废弃工具）、platform.tool.describe 按需返回单个工具 Schema/示例/规则摘录/Go API 能力依赖、platform.rules 按节/领域读取有界规则（不加载完整 rules.json）；CLI 增 catalog 与 tools list --compact/--domain，tools show 输出按需描述；MCP tools/list 仍返回完整清单并新增 _meta.domain 与 catalog 指引。
- 注册表改为惰性 module:handler 引用：目录/状态计算不导入 tools_*.py / media_naming.py，首次执行/描述才导入；实现模块缺失→unavailable/TOOL_NOT_IMPLEMENTED；新增 deprecated/replacedBy（目录默认隐藏、执行 TOOL_DEPRECATED）；rules.json 增 toolCatalog 规则节（领域/发现策略/惰性加载/权限模型/管理员 Bearer 绕过风险评估）并升 rulesVersion/rulepackVersion 2026-11-07.5。
- 权限与发现分离：execution 按 QIMENG_AI_GRANTED_PERMISSIONS 或 toolConfig.grantedPermissions 在 Schema/计划/网络前强制拒绝（PERMISSION_DENIED）；未配置默认授予全部声明权限仅为兼容旧 CLI。管理员 Bearer 绕过风险已写入 README/ADR-0056/rules：工具层隐藏 token 不能阻止模型直接调用 Go API，正式开放 AI 写入前需受限凭据或执行端隔离。
- 示例与规则摘录集中在 registry（TOOL_EXAMPLES/TOOL_RULES_REFS），describe 有界返回；目录 JSON 约为完整 tools list 的 1/11（28 工具实测），120 个合成工具仍有界；新增 tools/test_ai_catalog.py 18 项与既有回归，tools 全套 205 项本机通过、check_docs 0 错误、check_privacy passed；云端 CI 见 HANDOVER §9.2。未安装、未接真实 AI 客户端。

## feat(web/app): 维护任务中心与两代布局、双端搜索结果文件名显示（2026-11-07 第七百零二笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- Web 维护任务中心（只读）：新增 `lib/maintenance-jobs.ts`(+test)、`hooks/use-maintenance-jobs.ts`、`components/maintenance/`（任务中心/列表/详情），维护页追加任务列表（状态筛选/cursor 分页/刷新）、详情（类型/状态/进度/步骤/工件元数据/错误码）与取消（二次确认后刷新）；不展示 payload/budget 与工件正文，未写「实时」承诺。
- Web 两代维护目录：`lib/maintenance-folder.ts`(+test) 与目录卡/备份卡/hook 改为 `当前/上传归档 → 上次` 两代整代轮换，每代独立 .sha256，createWritable 原子替换，摘要不符/缺摘要/不可读拒覆盖并保留现场；旧 flat3 只读兼容并可列提示、绝不删除；恢复入口改为当前/上次，旧「上上次」改文件导入说明。
- Web 搜索：搜索结果标题取完整文件名（COS 结果亦可见作品编号/组图页码）单行省略、悬停/读屏完整名，普通浏览网格保持 cosWork 优先两行；换搜索词显示「正在搜索…」加载态而不混旧词结果，同词筛选/分区保持 keepPreviousData 不闪；详情新增完整文件名行与复制（失败提示手动复制）；消费共享 Go 搜索语义，不重写匹配。
- Android 两代维护目录：`MaintenanceFolderRepository` 改为当前/上次两代 + 上传归档，整代轮换、独立摘要、原子写、拒覆盖保留现场、旧 flat3 只读兼容；新增 `MaintenanceGenerations.kt`（纯 JVM 轮换/原子写/摘要）与 `MaintenanceLayoutMigration.kt`（旧三代只读核对+复制不删除）；旧镜像/备份偏好改指新代且保留旧文件；18 项 JVM 单测由本机 kotlinc+JUnit 运行通过（AGP/Gradle 与真机未跑，云端 CI 为编译门禁）。
- Android 搜索结果：缩略图下一行简短文件名（单行省略、保留编号/页码，`QimengMediaGrid` 可选 caption，默认不影响其他网格），详情信息弹窗完整文件名与复制（Clipboard+提示），换词提交同步清空旧结果/游标（配合既有查询代际丢弃在途旧响应），保留双指缩放 2～5 列/分页/滚动/返回，内嵌与连接模式同一 API；补 JVM 单测（短名保留编号/页码与边界、换词清空旧结果）。真机与内嵌现场未验收。
- 验证边界：Web vitest 全量 41 文件/381 用例、tsc --noEmit 与本次改动 oxlint 通过（未跑 vite build/dev、浏览器走查另按 HANDOVER）；Android 仅静态类型检查与 JVM 单测，未跑 AGP/Gradle/真机；未安装到现装 PC/手机。
- 交付后 CI 修复（同批提交 6a41bc55/d1ef39f9）：维护任务中心对合成/异常响应形状做列表/详情/取消归一（避免非协议 JSON 在渲染期抛错拖垮维护页），任务中心改用独立布局类名 `.maintenance-job-center`（避免 e2e 严格模式把第二个 `.monitor-grid` 判为多元素）；Playwright 维护/管理导航场景恢复通过。
- 验证：最终 HEAD 云端 ci [42370683863](https://github.com/Surtr42u/qimeng-media/actions/runs/42370683863) 七 job 全绿（服务端 go vet/golangci-lint/go test -race/go build、Web tsc/build/vitest/Playwright、Android assembleDebug+单测+Lint、SDK 生成链、桌面壳、Windows 原生文件操作、redocly）；文档 [42370683869](https://github.com/Surtr42u/qimeng-media/actions/runs/42370683869) 与依赖安全 [42370683860](https://github.com/Surtr42u/qimeng-media/actions/runs/42370683860) 通过。本轮交付仍**未安装**到现装 PC/手机，真实库、真机与外部 AI 客户端未验收。

## feat(server): 两代备份镜像、组图命名解析与搜索编号语义（2026-11-07 第七百零一笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- 两代备份布局（服务端）：`server/internal/authorattach/mirror.go` 在 `path` 祖先目录恰有一段 `当前`/`current` 时，按同根兄弟 `上次`/`previous` 推导上一代并校验仍在维护根内；内容变化先把当前代（正文+独立 .sha256）提交为上次代再写新当前，相同数据只刷新当前；上次目标已存在且正文不同时必须有可复算匹配的 .sha256 才覆盖，缺失/不符/不可读拒绝并告警；相对路径、无代目录段、多段歧义、盘符/共享根等不推导时回退旧 flat3 三代语义，旧配置行为不变。新增 8 组测试覆盖推导、轮换、拒覆盖、外部编辑、失败重试与 master 模式全链路。
- 迁移核对工具：新增 `tools/migrate_backup_layout.py`，默认 `--check` 只读核对（可读性/.sha256 复算/新旧对应建议/JSON 摘要），`--apply` 才复制生成当前/上次（当前=旧最新，上次=旧「（上次）」），上上次仅列可清理清单；绝不删除/移动，目标占用拒覆盖，临时文件+os.replace 原子写；12 项合成目录单测通过。
- 组图命名：新增 `server/internal/filing/workname.go` 纯函数解析作品基名/主作品编号（位宽/分隔/标记风格）/可选组内页码，仅括号序号（含上传自动改名产物）回退旧 seriesSuffix；`SuggestSeriesNames` 对组图页建议下一作品编号而非下一页码，组图与单图混合/乱序/缺页/补图/全角括号/零填充均覆盖测试；`docs/domain/files.md` 与 `docs/api/files.md` 记录命名语法、三步优先级与「自动改名不算命名成功」。
- 搜索编号语义：新增 `server/internal/store/searchtoken`（纯函数 Match + modernc 确定性标量函数 `qimeng_search_token`，init 注册失败直接 panic 防静默退化）；browse/facets 的 10 处 q 谓词统一改为该函数：数字 token 按独立数字段匹配（前后非 ASCII 数字，裸数字不命中括号页码），`(2)` 等页码 token 定位组图页，非数字 token 保持旧 ASCII 子串多字段语义；列表/计数/分面/dateCounts 同口径。修复前隔离测试复现「朱诺 1 命中 13/13、朱诺 11 命中 110/2011、朱诺 2 命中页码」，修复后 store/search/httpapi 定向测试通过；sqlc 重新生成比对无额外漂移。
- 验证边界：上述 Go 侧本机 `go build/vet/gofmt` 与 authorttach/store/search/httpapi 定向测试通过；Python 侧 12 项迁移工具测试通过；未改 OpenAPI/sdk.lock/SDK/migration；未迁移真实维护目录、未对真实库执行搜索专项或命名整理。

## feat(server): 维护任务 Worker、幂等载荷指纹冲突与组合根装配（2026-11-07 第七百笔）

执行 AI：DeepSeek V4.1 Flash（主代理复核；执行子代理）

- `server/internal/maintenance` 新增 `worker.go`：内部常驻执行器（不开放任何 HTTP 领取/续租/完成端点），仅领取本进程已注册 handler 的 kind（sqlc 查询按 kind 过滤），有界并发（默认 1）、租约 TTL+心跳、逐步骤 RecordStep（running/completed/failed/skipped + attempt/时间/detail）、状态机安全推进（queued→inventory→applying→verifying→completed/partial/failed）、暂停/取消/租约丢失后停止后续步骤并保留已提交步骤、进程退出不写终态由租约过期续跑、panic 兜底只影响单任务、已完成步骤不重复执行副作用。
- 内置 `backup_export` handler（`handler_backup.go`）：复用既有 `backup.Manager` 生成库快照并 AddArtifact 记录工件元数据（正文不入库）；无 handler 的 `asset_research`/`author_cleanup`/`custom` 保持 queued 供未来外部 AI 执行器。
- 幂等冲突保护：`Service.Create` 命中同 idempotencyKey 时比较新载荷 inputFingerprint，相同保持重放（200/created=false），不同返回 ErrStateConflict（409），httpapi 映射 STATE_CONFLICT；补并发 replay 竞争路径。
- 装配与配置：`cmd/qimeng` 以 `safego.Go` 启动 worker 并在关库前收齐；config 新增具名默认值（轮询 5s/租约 2min/并发 1，`QIMENG_MAINTENANCE_WORKER_*` 可覆盖，默认启用但只处理已注册 kind）；SQL 改动经锁定 sqlc v1.31.1 重新生成。
- 测试与文档：maintenance/config 包覆盖领取/心跳、租约过期接管、重启续跑不重复、暂停取消、无 handler kind 不被领取、同 key 同/不同载荷、backup_export 工件与失败终态；docs/api/maintenance.md 更新 worker/冲突/未开放外部领取端点的边界，ADR-0054 追加实现补充。
- 验证边界：本机 `go build ./...`、`go vet ./...`、定向 `go test ./internal/maintenance/ ./internal/config/` 通过，`gofmt -l` 干净；完整回归与 race 检测以云端 CI 为准（见 HANDOVER）。未安装到现装运行包，未对真实库创建任务。

# CHANGELOG - 变更历史

本文件只保留最近 30 笔；更早条目按 [历史索引](history/INDEX.md) 查询。历史条目正文及署名不改写，当前状态看 HANDOVER/能力地图与现行规范。

## AI 署名约定（沿用旧项目 QimengMedia 惯例）

- 每个变更条目标注实际执行该改动的 AI 模型完整名称与变体（如 GPT-6.1 Sol），以会话元数据或用户明确确认为据；Codex 仅作工具/客户端说明，不能代替模型名，具体版本也不能简写成模型系列。无法确认时注明未知，禁止猜测。
- 署名补全（2026-10-26，用户明确确认）：2026-10-20～26第五百四十八至第五百七十四笔、第五百八十九至第六百二十五笔原标未知，共64笔，补为GPT-6.1 Sol；原主代理/复核/GLM-5.3原实现分工及工具标记保留。该依据是用户本轮确认，不伪称已从运行时独立读取；本会话后续交付在此确认范围内沿用。
- 每个条目单独署名；多 AI 协作时各条目自行署名。
- 子代理执行的工作标注"（执行子代理）"，主对话直接完成的标注"（主代理）"。
- 署名自查（2026-09-05 补）：每条变更由执行会话先确认自身实际运行模型的真实名称再署名（GLM-5.3 与 GLM-5.3-Flash 是两个不同模型名），禁止沿用上一会话或上一条目的署名行；历史条目真实署名不动。
- 署名补全（2026-10-23，用户明确确认）：2026-10-22 第五百七十五至第五百八十四笔原标未知，补为 GPT-6.1 Sol；各笔执行分工和历史验证边界不变。
- 署名更正（2026-10-20，用户明确确认）：2026-10-19～20 的第五百三十八至第五百四十五笔原误署 GPT-6 或仅署 Codex，统一更正为 GPT-6.1 Sol；保留各条主代理/执行子代理及复核分工。
