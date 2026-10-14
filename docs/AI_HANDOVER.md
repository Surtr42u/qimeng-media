# AI 交接报告：可靠性加固分支

> 最后更新：2026-10-15  
> 用途：让下一位 AI 在不重读整个聊天记录的情况下，快速恢复项目上下文、理解已做事项和未完成风险。  
> **这是工作交接文档，不代表所有事项已验收。请以当前分支代码和最新 CI 为准。**

## 0. 接手时必须遵守的约束

1. **禁止合并。** 仓库 `Surtr42u/qimeng-media` 的工作分支是 `codex/reliability-hardening-ui-2026-10`，PR #1 是 Draft。用户明确说“不得合并”，用户会自行拉取测试后决定。不要 merge PR、不要把分支合并进 `master`，不要将 PR 从 Draft 改为 Ready。
2. **只在该功能分支继续工作。** 每次写入前先核对 PR head SHA 和分支 head；若 head 已变化，不要用旧 SHA 覆盖。正常提交用 fast-forward 更新，不要 force push、squash、rebase 或重写历史。
3. **不要操作运行中的 NAS / 部署实例 / 数据库 / 媒体文件。** 真实双卷测试只可使用隔离的合成目录或 CI runner；不可为了验证而在生产存储上制造冲突、断电、磁盘满或删除文件。
4. **不许把未验证事项写成已完成。** 只有最新 HEAD 对应的 CI 全部完成且成功，才可称 CI 全绿。SQL 字符串合约测试不是 SQLite 真实迁移测试；单测不是生产 NAS E2E。
5. **用户希望执行实际工作，不是只给计划或进度播报。** 先查最新 CI 和代码，修复具体问题，补测试与文档，然后核验 PR 仍为 Draft 且未合并。
6. 仓库纪律：生成物不手改（协议改动应走 `make sdk`）；数据库迁移只增文件、不改/删旧迁移；重大架构决策先写 ADR 并更新索引；每项改动同步测试与文档。
7. 不要随意修改已收官里程碑 M0–M6 状态，也不要把 M7+ 储备项标为已完成，除非用户明确批准并且验收证据齐备。

## 1. 仓库、分支与 PR

- 仓库：`Surtr42u/qimeng-media`
- 工作分支：`codex/reliability-hardening-ui-2026-10`
- PR：[#1 — 可靠性加固（保持 Draft）](https://github.com/Surtr42u/qimeng-media/pull/1)
- 基线：`master`
- 本交接文档创建前核对到的 HEAD：`9ac2e7b79664346173235cbe9427e77df71e6129`
- 对应提交说明：`docs(android): 明确标准文件移动的并发竞态边界`
- 该 HEAD 的父提交：`950a94eea180b0450ef61d6a5131bde636f0c007`
- PR 状态核对结果：`state=open`、`draft=true`、`merged=false`。这只是核对时的快照；接手后请重新查询。
- 当前 PR 显示变更文件数为 25。分支提交历史较长，不要仅凭提交数判断变更量；优先检查 PR diff、当前 head 和工作流。

### 接手第一步：重新确认远端状态

先查询 PR #1 的 head/state/draft/merged，再查询该 head 的 GitHub Actions。不要直接假定上述 SHA 仍是最新的。当前已知工作流：
- CI run #90 / run ID `38625420329`
- 对应 HEAD：`9ac2e7b79664346173235cbe9427e77df71e6129`
- 最近一次查询时状态：`in_progress`
- 更早 run #89（`38624606838`）与 #88（`38624329484`）因同 PR 新提交触发 concurrency 规则而取消；取消旧 run 是预期行为，不代表代码失败。
- run #87（`38623080755`，HEAD `c7e57b3a1d51a7e2e11c2a47dc34d89f2d040132`）成功，但它不是当前 HEAD，不能拿来证明最新代码通过。
- CI 工作流在 `.github/workflows/ci.yml`，配置了七个 job，并对同一 PR 的过时运行执行 cancel-in-progress。

## 2. 这一批改动的目标和范围

本分支围绕可靠性加固，不是新功能大改，重点是：
- Android 归档文件操作安全与编辑页的多步骤保存一致性；
- Web 确认弹窗、编辑离开保护、回收站错误恢复与防重复提交；
- Go 文件移动/回收站清理时保护既有目标；
- Windows 原生 Go 文件操作 CI；
- Android Room 迁移测试的现有覆盖与缺口说明；
- 让安全文档、架构文档、项目计划、变更日志与实现保持一致。

此前的主要代码已在 PR 中。不要无理由重做已有改动；先读当前实现和测试，再补最小、可验证的修正。

## 3. 已实现的主要内容

### 3.1 Android 文件归档
关键文件：
- `android/core/data/src/main/java/media/qimeng/app/core/data/repository/InboxFileStore.kt`
- `android/core/data/src/test/java/media/qimeng/app/core/data/repository/InboxFileStoreTest.kt`

已做：
- 流式读取/比较处理短读、零读与长度不匹配；有对应测试。
- 对外部 ContentProvider/SAF 提供的 displayName 做文件名安全校验；拒绝路径分隔符、控制字符、Windows 非法字符、尾随空格/点及点目录。
- 校验归档库目录规范化路径，拒绝根外路径、错误的直接子目录和符号链接别名。
- 对已存在且内容相同的归档目标保守处理；尤其对通用 `content://` URI，不假设 URI 与目标一定是不同物理文件，不随意删除源 URI。
- 跨挂载点复制使用唯一临时文件，再发布正式目标；只清理本次创建的临时文件。
- 本轮最终复核发现 `uploaded/` 归档与归档重命名也使用过 `File.renameTo`，因此补上了这些路径的目标冲突保护，并增加既有目标冲突测试。

**重要的未消除竞态：** Java NIO `Files.move(source, target)` 不指定 `REPLACE_EXISTING`，对“调用时已存在且被检测到”的目标会失败；但不能把它当作相对于所有其他进程的原子 no-replace 原语。目标可能在检查与移动之间被并发创建。当前代码和文档已明确该边界，不得将其宣传为彻底消除 TOCTOU。若要真正关闭窗口，需要平台/文件系统提供原子 no-replace 机制，或所有写入方共用协调机制。

### 3.2 Android 资产编辑页
关键文件：
- `android/feature/detail/src/main/java/media/qimeng/app/feature/detail/AssetEditViewModel.kt`
- `android/feature/detail/src/main/java/media/qimeng/app/feature/detail/AssetEditScreen.kt`
- `android/feature/detail/src/test/java/media/qimeng/app/feature/detail/AssetEditViewModelTest.kt`

已做：
- dirty state / `canSave`、未保存退出确认；保存中不允许危险导航或继续编辑。
- 来源基线尚未加载或加载失败时禁止修改；提供失败重试，防止空数据误覆盖服务端关联。
- 多步骤保存按成功步骤记账：已成功的重命名/关联/来源步骤不会在后续重试中无条件重复提交。
- 过滤 COS 派生作者（`isCos` 或 `cos_` id），避免常规作者关联 PUT 携带只读 COS id。
- 修复 `rememberSaveable` 导入路径。
- 对初始异步加载和测试草稿编辑之间的竞态，测试中先驱动测试调度器 idle；其中保存中来源请求测试须先加作者，以确保真正触发作者关联写入门禁。
- CI 过去出现过 Android 单测并发/时序失败，当前 HEAD 的 Android job 结果仍需以 run #90 为准；如果失败，请下载失败 job logs，依据实际断言修，不要盲目加 delay。

### 3.3 Web 交互可靠性
重点文件包括：
- `web/src/components/ui/confirm-dialog.tsx`
- `web/src/components/manage/BackupCard.tsx`
- `web/src/lib/batch.ts`
- `web/src/pages/AssetEditPage.tsx`
- `web/src/components/AuthorSuggestField.tsx`
- `web/src/components/FileOpsDialogs.tsx`
- `web/src/pages/TrashPage.tsx`

已做：
- 确认弹窗支持 busy 状态；成功后才关闭，失败保留上下文以便重试。
- 防止备份导入重复提交。
- 错误提取支持 Error/string/object 的 message/status/code，避免显示 `[object Object]`。
- 编辑页增加 SPA 路由拦截和 `beforeunload` 未保存提示；来源基线加载失败时不允许编辑。
- 多步骤保存只重试失败步骤，保存中禁用表单操作。
- 回收站恢复/永久删除/清空操作保留失败上下文，防重复或冲突操作。
- 当前 HEAD 的 Web job 在 run #90 中需再次确认。

### 3.4 Go 文件移动与回收站
重点文件：
- `server/internal/filing/move.go`
- `server/internal/filing/move_test.go`
- `server/internal/httpapi/trash.go`
- 平台文件（例如 `move_windows.go`）和对应 CI。

已做：
- 用错误哨兵区分目标已存在与本次未创建目标，保留底层错误，供 `errors.Is` 判断。
- 复制回退通过 `O_CREATE|O_EXCL` 独占创建，避免截断已有目标。
- 回收站清理遇到目标冲突时跳过危险清理，避免误删不是本次操作创建的目标。
- 增加 Windows 原生 runner 对 Go filing 包的测试，覆盖 Linux runner 不会编译的 Windows build-tag 路径。

**已知竞态：** Go 的 `Lstat` 预检查与标准 `os.Rename` 之间仍有跨进程 TOCTOU 窗口。跨卷复制的独占创建保护更强，但不能把所有平台、同卷 rename 的竞争都说成已解决。真实双卷部署行为尚待隔离环境端到端验收。

### 3.5 CI 与工具链
文件：`.github/workflows/ci.yml`

CI 包括七个 job：
1. 协议宪法校验（redocly）
2. 服务端（Go vet + test + build）
3. Go 文件操作（Windows 原生测试）
4. Web（tsc + build + tests）
5. SDK 生成链（`make sdk` 可重建和 sdk lock）
6. Android 客户端（assembleDebug + unit tests + lint）
7. 桌面壳（Tauri 2 cargo test/build）

同 PR concurrency 会取消旧 head 的 CI。Go CI 固定到 `1.27.0`，以匹配锁定的 golangci-lint v2.13.1，避免 runner 补丁漂移导致静态检查器无法解析标准库导出数据。

不要只看 run 总状态；检查七个 job 的每个结论，确保 Android job 也已实际运行。旧 head 的绿色不代表最新 HEAD 绿色。

## 4. 仍未完成 / 不可虚报的事项

### P0：最新 CI 验证
- 首先刷新 run #90 当前状态；若结束，检查七个 job 的结论。
- 若失败，获取失败 job logs，定位具体 step 与错误；修复后等待新 head 的完整 CI。
- 同 PR 新提交会自动取消旧 run，这是工作流预期。
- 不要把 run #87 的 success 当作当前 HEAD 的验证。

### P1：Room 真实 SQLite 迁移验证（明确缺口）
文件：
- `android/core/data/src/test/java/media/qimeng/app/core/data/events/EventDbMigrationsTest.kt`
- 相关数据库定义/迁移：`android/core/data/src/main/java/media/qimeng/app/core/data/events/` 下的 DB 与 migration 文件（接手后先查实际路径）。

目前 `EventDbMigrationsTest.kt` 是 JVM SQL 合约测试：通过代理记录迁移执行的 SQL，检查 1→2、2→3 的 ALTER TABLE 语句及迁移链连续性。**它没有创建旧版本 SQLite 数据库，也没有验证真实 SQLite 执行迁移或历史事件数据保留。**

下一步应先检查现有 Gradle 版本目录、Room/SQLite 依赖和测试规范，选择最合适的方案：
- 首选真实 SQLite migration test（例如 Android instrumentation + Room `MigrationTestHelper`，或项目可接受且与生产 SQLite 行为足够一致的测试方案）。
- 如果需要 emulator instrumentation，明确加入所需依赖与 CI 执行步骤；不要只加测试源文件却不让 CI 执行。
- 用旧 schema 创建数据库，插入有代表性的历史 pending event，执行真实 1→2→3 迁移，验证 schema/version、旧行仍在、旧字段默认值正确、新字段可读写；最好覆盖迁移链与关键约束。
- 不要通过删库、清数据或改旧迁移来让测试通过。
- 若当前 CI 不能可靠启动 emulator，记录实际限制并给出可执行的 CI/测试方案，不能把 SQL 合约测试改名冒充真实迁移测试。

### P1：真实跨卷文件移动验收矩阵
先读 `docs/SECURITY.md` 当前的「客户端归档与服务端文件移动」及 TOCTOU 边界，再整理/补足可重复的隔离测试。至少区分：

| 场景 | 自动化应验证 | 真实部署/文件系统验收 |
|---|---|---|
| 同卷正常移动 | 内容、源消失、目标存在 | 视需要抽测 |
| 跨卷正常复制 | 内容 hash 一致，成功后源删除 | 必须用两个真实挂载点确认 |
| 目标在操作前已存在 | 目标和源内容不变、操作失败 | 可补不同 FS 行为检查 |
| 目标在检查后被并发创建 | 明确记录目前标准 API 的竞态限制；不要声称已原子解决 | 仅在隔离合成目录测试，不碰生产文件 |
| 复制中断/磁盘错误 | 源保留、既有目标不损坏、仅清理本次临时文件 | 受控故障注入，禁止生产 NAS |
| 发布成功但删源失败 | 返回语义与日志清楚，完整目标和源都保留 | 文件系统权限/锁定场景 |
| 临时文件清理失败 | 不误删目标，错误可观察 | 可在隔离环境验证 |
| Windows 与 POSIX 差异 | 对应 build-tag 单测 | 两平台文件系统语义分别验收 |

将自动化可证明的性质与生产部署尚需确认的行为分开记录。尤其要区分「目标原本存在」和「目标在检查与移动之间并发创建」。

### P2：最终跨端回归与文档漂移
- Android：归档冲突、短读、路径边界、编辑 dirty/back、来源加载失败重试、部分保存重试、COS 作者过滤。
- Web：确认弹窗失败重试、离开未保存保护、多步骤保存、回收站冲突/失败。
- Go：目标冲突、copy fallback、错误哨兵、回收站补偿清理、Windows 原生 filing 测试。
- 核对 `docs/ARCHITECTURE.md`、`docs/PROJECT_PLAN.md`、`docs/SECURITY.md`、`docs/CHANGELOG.md`、`README.md`；发现漂移要修正文档或明确记待办。
- 不要将已收官里程碑重新打开/关闭，也不要擅自把 M7+ 项目转成承诺。

## 5. 最新一轮补充修正

在最终复核中，发现 Android 的 `uploaded/` 归档和归档文件重命名也存在使用 `File.renameTo` 的目标覆盖风险，因此在上一轮代码中：
- `archiveToUploaded` 与 `renameArchivedFile` 改为调用 `moveFileNoReplace`；
- 常规归档改为 `moveOrCopyNoReplace`：同 FileStore 先尝试 `Files.move`，不同 FileStore 或移动失败时进入受控 copy fallback；
- 增加 uploaded 同名冲突与归档重命名同名冲突测试；
- 更新 `docs/SECURITY.md`、`docs/CHANGELOG.md`，明确标准文件 API 的 TOCTOU 边界。

**接手后请重点复核这个新增路径的正确性和 CI 结果**，尤其关注：
- `Files.getFileStore(source.toPath())` 与 `Files.getFileStore(parent.toPath())` 的行为和异常处理；
- 跨卷 fallback 是否只清理本次创建的临时文件；
- 同卷 move 失败后进入 fallback 的条件是否会意外掩盖权限/IO 错误；
- Android minSdk/API 与测试运行时兼容性；
- 对 `content://` 路径与普通 File 路径的处理有没有语义混淆。
如果发现缺陷，优先补测试再修代码，并同步更新本文和 CHANGELOG。不要为了让 CI 绿而弱化断言。

## 6. 相关文档

- `docs/CHANGELOG.md`：详细历史记录与当前批次变更。
- `docs/SECURITY.md`：文件操作安全边界与已知 TOCTOU 风险。
- `docs/PROJECT_PLAN.md`：里程碑与每轮 AI 常驻自检纪律。
- `docs/ARCHITECTURE.md`：模块边界和总体结构。
- `.github/workflows/ci.yml`：CI job 和各门禁执行命令。
- `android/core/data/src/test/java/media/qimeng/app/core/data/events/EventDbMigrationsTest.kt`：目前只是 SQL 合约测试，不是真实 SQLite 迁移测试。
- `android/core/data/src/test/java/media/qimeng/app/core/data/repository/InboxFileStoreTest.kt`：Android 文件归档单测。
- `android/feature/detail/src/test/java/media/qimeng/app/feature/detail/AssetEditViewModelTest.kt`：编辑状态/保存逻辑单测。
- `server/internal/filing/move_test.go` 与 `server/internal/httpapi/trash.go`：Go 文件移动与回收站补偿。

## 7. 完成定义与交接回报格式

不要只说“已处理”。每个后续任务至少交代：
1. 改了哪些文件、解决什么具体问题；
2. 新增/更新了哪些测试；
3. 实际运行的本地/CI 检查及其 head SHA；
4. 未覆盖的风险、需要用户在设备/部署环境测试的事项；
5. 分支 head 与 PR 状态；
6. 明确声明没有合并。

当前任务总体只有在以下条件满足后，才可以向用户报告“这轮已验证完成”：
- 最新 HEAD 的七个 CI job 全绿；
- Room 真实 SQLite 迁移测试缺口已关闭，或用户明确决定接受并将其保留为待办（必须诚实说明）；
- 跨卷自动化覆盖与真实部署验收边界明确；
- 文档和代码一致；
- PR 仍为 Draft / Open / 未合并。

再次强调：**不管以上任务进度如何，都不得合并 PR #1 或把分支并入 master。最终决定由用户做。**
