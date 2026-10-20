# CHANGELOG 分期历史：第五百二十三至五百三十笔

原条目完整保留；现状以当前专题为准。

## fix(app): 备份目录选择器显式指定起始位置——避开系统受限目录死路 + 授权失败不再静默（2026-10-11 第五百三十笔）

执行 AI：DeepSeek-V4.1-Flash（主代理）

- **背景（用户实测报障）**：备份页点「选择目录」后卡死在系统选择器里——界面显示「无法使用此文件夹 / 为保护您的隐私，请选择其他文件夹」，底部「使用此文件夹」置灰、列表显示「无任何文件」（该目录磁盘上实有 30+ 条目），用户在界面里无路可走；去系统设置查权限也查不出原因（照片/视频权限被拒属上传页相册选择器，与备份目录无关）。
- **根因（平台限制 × 我们没给起始位置；※20:2x 修正见文末「结论修正」条，此处保留首轮叙述以存真）**：Android 11+ 起 `ACTION_OPEN_DOCUMENT_TREE` **不允许**授权受限目录（内部存储根目录 / Download / Android/data / Android/obb），且这是**选择器层面**的判定，与调用方持有什么权限无关——App 的「所有文件访问」（MANAGE_EXTERNAL_STORAGE，ADR-0015 用）已开也不放行。而 `BackupScreen` 一直以 `launch(null)` 拉起选择器，起始位置完全交给系统选择器自己的记忆：它一旦记在受限目录上就复现该死路（此前几周的授权都落在子目录，所以从没撞上）。
- **只读取证（真机 adb，未动前台）**：① `dumpsys activity activities`——`com.android.documentsui/.picker.PickActivity` 在 App 任务 t57388 内，`Intent{act=android.intent.action.OPEN_DOCUMENT_TREE}`（**无起始 URI**）、`resultTo=MainActivity`、`state=STOPPED finishing=false`＝结果从未回传；② `dumpsys activity permissions`——`media.qimeng.app` 的持久化 tree 授权共 4 条，最新一条 `primary:1/HHH/6  备份` 时间 10-11 02:05:38，19:37 那次**零新增**；③ 既有目录当天 18:41 仍成功写入 5.1 MB 备份（授权链本身没坏）；④ 该提示语在本仓库 `grep` 0 命中＝系统 priv-app DocumentsUI 所发（`/system/priv-app/DocumentsUI/DocumentsUI.apk`）；⑤ 该 ROM 封了 shell 读日志（`logcat -g` 四个缓冲全 `0 B readable`，`ro.debuggable=0`），故取证走 dumpsys 而非 logcat。
- **修复 1（根因）**：新增私有 `backupDirPickerDocumentUri(storedDirUri)`——起始位置取当前备份目录（树 URI → 该树根文档 URI），未设/不可解析时退回内部存储 `/Documents`（标准公共目录且非受限）；`onPickDir` 由 `launch(null)` 改 `launch(pickerStartUri)`。依据＝androidx activity-1.13.0 **字节码实证**：`ActivityResultContracts$OpenDocumentTree.createIntent` 把入参 Uri 写进 `android.provider.extra.INITIAL_URI`（＝`DocumentsContract.EXTRA_INITIAL_URI`），即契约入参本身就是起始位置（本项目从前只传 null＝等于没给）。
- **修复 2（连带清偿，前次诊断发现）**：`takePersistableUriPermission` 的 `runCatching` 从前只有 `onSuccess`——失败静默吞，目录不落盘却零反馈，与「没权限」无从区分；新增 `BackupViewModel.onAutoBackupDirGrantFailed(cause)`（文案带异常类名，AuthorTxtImportViewModel 同款）+ 屏幕层补 `onFailure` 分支。
- **修复 3（可见性）**：`AutoBackupCard` 目录行下加一行小字「受系统隐私限制：只能选子目录，不能选内部存储根目录 / Download」——该平台约束此前在 App 内无处可见，用户只能撞在选择器里。
- **顺带（依赖口径）**：`feature:manage` 显式声明 `libs.androidx.core.ktx`——`String.toUri` 来自 androidx.core.net，此前本模块只有 :app 声明该库、自己走传递依赖拿到 1.18.0，与版本目录的 1.19.0 不一致（本库本就在全工程运行时图内，非新增依赖）。
- **自测**：`./gradlew assembleDebug assembleRelease testDebugUnitTest :core:model:test lintDebug`（CI 同款门禁，JAVA_HOME=Android Studio JBR）BUILD SUCCESSFUL；110 个测试类 1106 例 0 失败 0 错误（含 BackupViewModelTest 17 例）；`lintDebug` 全模块 0 error，本次新引入的 `UseKtx` warning（`Uri.parse` → `String.toUri`）已按 KTX 口径清偿，manage 模块仅余既有 `ComposableNaming` 1 条（ThumbnailCacheScreen:144，非本批引入）。产物核验：release APK 与手机在装版**同一签名**（SHA-256 `8f62ba55…`）、同 versionCode=1，可原地覆盖；APK 的 classes.dex 内已确认含本批新增文案。
- **装机（用户拍板后执行）**：`adb install -r app-release.apk` → `Success`（2026-10-11 20:11:38，同签名原地覆盖，versionCode 1 不变、登录与已授权备份目录均保留）；真机行为确认（选择器起始落点）由用户在备份页点一次「选择目录」目视核对，本次不驱动真机前台。回滚包留存于仓库外：`D:\build-cache\apk-backup\app-release-before-fix-20261011-1803.apk`（即用户 18:10 所装那版）。
- **结论修正（2026-10-11 20:2x 用户重启后回填，诚实记档）**：本笔首轮把用户报障整体归因于「平台受限目录规则」，**该归因不完整、对最关键的一次是错的**。装机后用户再试（20:13 截图）连**子目录** `NX721J/1/HHH/6  备份`（非受限目录，且当天 02:05 刚授权成功过）也被判「无法使用此文件夹」且列表空白；**用户重启手机后自愈**。重启后只读复核（uptime 263s）：①「所有文件访问」**仍为 allow**，② 用户在 **20:21:34 成功重新授权同一目录**（授权表新增 `primary:1/HHH/6  备份` 记录）——即 19:37~20:13 的失败是**系统侧选择器/存储提供者状态损坏**，与 App 代码、与「所有文件访问」均无关（该权限开着时选择器工作正常，我此前"All-files access 导致选择器拒绝"的猜测被此条直接证伪）。受限目录规则本身依然真实（内部存储根目录 / Download 确实不可选），故修复 1/2/3 三项照旧保留、继续装机；`backupDirPickerDocumentUri` 的注释也同步改为按事实叙述（**注释-only 改动，行为零变化，故未重装**）。
- **端到端确认（重启后）**：授权表新增 `2026-10-11 20:21:34 → primary:1/HHH/6  备份` 一条持久化 tree 授权＝「选择器拉起 → 确认 → takePersistableUriPermission → 落盘」全链路成功；装机版（20:11:38）即含本笔改动，用户在真机上完成确认。
- **待观察（损坏触发点未定位）**：该 ROM 封了 shell 读日志（`logcat -g` 全 `0 B readable`，`ro.debuggable=0`），重启后进程/崩溃证据随之消失；`dumpsys activity processes` 未显示 provider 异常。若再现：**先别重启**，立刻抓 `dumpsys activity providers`、`dumpsys activity processes`、`dumpsys dropbox --print` 留证，再定位。
- **文档**：CHANGELOG.md（本笔）。

## fix(server+docs): 本机同步归档区同名只留最新（用户定规，ADR-0030 修订1）+ 作者 126 来源区存量收敛收口（2026-10-11 第五百二十九笔）

执行 AI：DeepSeek-V4.1-Flash（主代理）

- **背景（用户实测反馈）**：本机同步全链路测试中同一份《作者总表.txt》先后投放 5 次，`.synced/` 按原「同名加序号绝不覆盖」堆出 `作者总表 (2)~(5).txt` 四份副本。用户拍板：**归档区只留最新即可**（原设计作废）。
- **行为变更**：`processLocalSyncTxt` 归档目标恒为 `<同步根>/.synced/<原文件名>`——同名重导先 `os.Remove` 旧副本再 `filing.MoveFile` 落位（清理失败与移动失败同走 failed 备注、源文件留在同步根下轮幂等重导 + 重归档自愈，导入事务不受影响）。**媒体归档语义不动**（`ResolveConflict` 同名改名永不 409——媒体双份是不同文件，不可覆盖）。存量：`.synced/` 5 份副本收敛为 1 份（保留最新一次投放内容、改名回原名；旧 4 份暂存本机临时目录备查，随系统清理自然消失）。
- **作者 126 来源区存量收敛收口（第五百二十七笔待办执行完毕）**：线上片段 / 桌面镜像 / 手机文件三方逐字节一致（126 块单「来源」标记行，全库 127 块无相邻双标记）；服务端以修复后二进制重建重启后，经 `PUT /authors/hornyherring/sources`（`mode=replace`、词表原样回传 `["hanime1","小红车"]`）走正规端点写入——幂等零净变化（前后逐字节 diff 一致）、镜像刷新对齐。
- **过程备注（诚实记档）**：首次 PUT 的中文请求体经 Git Bash→Windows 命令行边界被按本地代码页（GBK）转码送出，非法字节被 json 解码替换为 U+FFFD 照单入库（「小红车」→乱码词），随即以 UTF-8 净体重写修复、净变化归零；顺带暴露「来源词写入无 UTF-8 有效性门禁」边角（正常 Web/App 客户端不受影响），已记入 HANDOVER 待办（触发：实际出现乱码词条）。
- **顺带清理**：删除 2026-10-06 遗留的空测试库「AI测试素材（可删）」（根目录已不存在、0 资产，此前每轮扫描/系统快照刷告警）。
- **自测**：`go build ./...` 通过；新增 `TestLocalSyncTxtArchiveKeepsLatest`（同名 TXT 重导两次 → 归档区恰 1 份、内容为最新一次导入；锁定新语义防「(2)」回归）；`internal/httpapi` 本机同步用例全绿。
- **文档**：ADR-0030（决策 4 修订指针 + 文末修订记录）、HANDOVER.md（§4/§6 口径 + 待办一条）、CHANGELOG.md（本笔）。

## fix(ci): Go 锁补丁版本 1.27.0——runner 漂到 1.27.2 使 golangci-lint v2.13.1 类型检查崩（标准库导入即报 typecheck），lint 失败连带跳过远端 Go 测试（2026-10-11 第五百二十八笔）

执行 AI：DeepSeek-V4.1-Flash（主代理）

- **背景（CI 连续红，非代码问题）**：10-10 起 master 的 ci 一直红，失败点固定在「服务端」job 的 golangci-lint 步——报 `server/migrations/embed.go:8:8: could not import embed`（**标准库导入**）+ `typecheck: 1`；因该步先失败，后续 `go vet ./...` / gofmt 门禁 / `go test ./... -race` / `go build ./...` **全部被跳过**——远端等于从未跑过 Go 测试，只有本机绿。
- **根因（工具链版本错配）**：`actions/setup-go` 写 `go-version: "1.27.x"` 会随 runner 上的新补丁漂移（本次实测 runner 已是 **1.27.2**），而 CI 锁的 **golangci-lint v2.13.1**（2026-08-20 发布）是 **go1.27.0** 构建的——其内置 type-checker 读不了 1.27.2 的导出数据格式（10-10 那次报的是 `internal/goarch`，同一病根）。**反证**：本地同版本 golangci-lint（v2.13.1，`built with go1.27.0`）+ Go 1.27.0 对同一份代码实测 **0 issues**，`go vet ./...` 0、`gofmt -l .` 空、`go test ./... -count=1` 19 包全绿。
- **修复**：`.github/workflows/ci.yml` 三处 `go-version: "1.27.x"` → **`"1.27.0"`**（服务端 / SDK 生成链 / 桌面壳三个 job 一并锁补丁版本），并在服务端 job 的 setup-go 上方加注说明锁定理由与升级纪律（升 Go 补丁须与 golangci-lint 三处同步：Makefile 顶部 GOLANGCI_LINT 注释 / 本 workflow / 开发者本机——对齐既有「版本与本机一致」口径）。
- **备选未采纳**：升 golangci-lint 到 v2.14.0（2026-09-24 最新）——其 go.mod 仍声明 `go 1.26.0`，无法从版本元数据证明其发布二进制用 Go ≥1.27.2 构建；且升级会引入新 lint 规则面、并要求 Makefile/ci.yml/本机三处同步。锁 Go 补丁版本是零风险最小改动，故选 A 方案（用户拍板）。
- **自测**：本地 golangci-lint（v2.13.1 + Go 1.27.0）0 issues、`go vet` 0、`gofmt -l .` 空、`go test ./... -count=1` 19 包全绿；推送后以 ci 运行结果复核（要求 lint 步转绿且后续 vet/gofmt/-race/build 真正执行）。
- **文档**：CHANGELOG（本笔）。

## feat(api+server+app+web): 编辑页按名新建作者——关联端点扩展 newAuthorNames + 保存自愈补行 + 编辑写入登记（2026-10-11 第五百二十三笔）

执行 AI：DeepSeek-V4.1-Flash（主代理；服务端/App/Web 三路执行子代理，同模型）

- **背景（用户事故）**：编辑页给资产挂一位**尚不存在**的作者（`hornyherring`）只能报错「未找到该作者：编辑页仅能关联已有作者，请从联想中选择」——原口径「新建唯一通道=TXT 导入」与「新建作者的自然时点=挂人的当下」相悖；用户拍板：**不新开接口**，扩展现有 `PUT /assets/{assetId}/authors`；不做简单止血（不做"只改文案"），全链路修。
- **协议（ADR-0024 修订 4）**：`AssetAuthorsReplaceRequest` 增可选 `newAuthorNames: []string`（items maxLength 200、maxItems 20）；语义 = 作者全集 authorIds ∪ 按名解析（规范身份命中复用、未命中新建）；`make sdk` 三端重生成 + `api/sdk.lock` 同批更新（253 项）。
- **服务端**：`authorattach.ResolveOrCreateAuthors`（规范身份归一复用/新建，同批重名短路、COS 撞名防御）+ `MergeAuthorRefs`（两来源去重合并）；`ReplaceAssetAuthors` 去「已关联跳过」——对作者全集**逐位幂等补写作品行**（自愈：关联在而文本缺行保存即愈，行已存在零写入），并把本次实际新追加的作品行**登记进 kv `imported_txt_upload_entries`**（覆盖式导入不得静默冲掉：手动通道 409 二选一 keep 并回、本机同步恒 keep 并回）——被 lint 行数警戒线拆分出两个 helper；`httpapi/author_edit.go` 前置校验（逐名 400 带具体原因、超 20 项 400 带协议双写同步注释）；`authoring.NewAuthorNameRejectReason` 校验原因单一实现（`ValidNewAuthorName` 改其布尔投影）。
- **App**：`MediaRepository.replaceAssetAuthors` 增可选 `newAuthorNames`（批量关联/自动挂靠调用点零改动）；编辑页 `commitAddAuthor` 未命中→待新建（含 200 字投影封顶/忽略大小写去重），待新建以「新建：\<name\> ✕」胶囊可移除，`save` 随请求带上、成功清空失败保留；共享组件 `QimengAuthorSuggestSection` 加 `placeholder` 参数（编辑页「回车新建」为真；上传页/相册页显式传「输入作者名（联想选择）」，批次规则不变）；**保存返回详情页即时刷新**——导航返回结果信号（savedStateHandle）触发既有 `retry()`，不整页重建（避免 open 打点重复/滚动位置丢失）；删除失效文案 `asset_edit_only_existing_authors`、新增 `asset_edit_author_name_too_long`。
- **Web**：新增 `lib/asset-authors.ts` 纯逻辑（身份键/码点长度/去重/20 项上限/载荷组装，26 例单测）；`AuthorSuggestField` 增 `allowCreate`（未命中给「新建：\<name\>」可点行，回车同款）、`useReplaceAssetAuthors` 载荷经 lib 组装、编辑页待新建清单 + 保存带名 + 响应回填草稿；删「本页只能关联已有作者」toast（TXT 导入降为次要说明）。
- **自测**：server `go build ./...`/`go test ./...`（19 包全绿，新 4 单测 + 6 集成测试）+ `golangci-lint` 0 issues；App `:feature:detail`/`:core:data` 单测全绿（227/270）+ `:app:assembleDebug` 通过（新文案与 newAuthorNames 已核在包内）；Web `build`+`lint`（零新增警告）+ `vitest` 320 通过（新增 26）。
- **对抗审查与返工（2026-10-11，reviewer 子代理 + 返工）**：总评「不通过」两项已返工——① App 待新建补 20 项上限拦截（对齐协议 maxItems）、服务端 400 原因经既有错误体解析透出（原先全归「保存失败：请重试」，输入非法名时无法自助）；② 第五百二十四笔的授权确认为用户拍板（保留）。P3 已并入本笔：身份口径三处文档校正（「忽略大小写/空白差异」→ 实际口径=大写转小写/半角空格转下划线/仅首别名定身份，空格差异不折叠、第二别名按新身份处理）、非法名枚举补「规范化后无有效别名（如 `[3D]`）」、`ReplaceAssetAuthors` 登记/修剪注释论证修正（顺序颠倒不会误剪，仅流程对齐约定）。**P3 遗留（下一笔）**：恰 20 项/恰 200 rune 边界测试、`MergeAuthorRefs` 同 id 去重测试、自愈幂等不变量（`authors.id == GenerateAuthorID(displayNameAliases(display_name)[0])`，审查实测 132/132 常规作者满足）入测与记档、COS 撞名返回 500 的错误码记档（防御性不可达路径）。
- **文档**：openapi.yaml、sdk.lock、ADR-0024（修订 4）、DOMAIN_RULES §6、GUIDE_API.md、CHANGELOG（本笔）。

## chore(repo): 接通本机同步通道启动脚本（local-sync 启用）+ 修历史 gofmt 违例（2026-10-11 第五百二十四笔）

执行 AI：DeepSeek-V4.1-Flash（主代理）

- **`_server-common.cmd` 增 `QIMENG_LOCAL_SYNC_ROOT=%~dp0local-sync`**：用户拍板启用本机文件夹自动同步通道（ADR-0030）——同步根=仓库内 `local-sync/`（用户名无关、机器中立，铁律 14），子文件夹名=库名自动入库、根级 TXT=作者片段导入；`.gitignore` 增 `local-sync/`（放入即入库、源文件会被移走，严禁入库）。隔离实例合成数据全链路实测通过（媒体入库+移走、TXT 导入+归档 .synced、重导收敛），真后端 8420 通道状态 enabled=true 无错误。
- **`server/internal/httpapi/host_check_test.go` gofmt 对齐**：历史提交 8a9a76f1 带入的注释对齐违例（`gofmt -l .` 非空 → `make lint` 的 gofmt 门禁必失败），本次一并格式修（纯空白，零行为变化）。

## fix(server+web): 跨盘回收站/本机同步搬移在 Windows 双盘布局下全失败——跨挂载点判定改 errno 主判（含 Windows 文本）；镜像挂点补齐；Web 错误提示不再落 [object Object]（2026-10-11 第五百二十五笔）

执行 AI：DeepSeek-V4.1-Flash（主代理）

- **背景（用户实测事故）**：桌面端删除报「移入回收站失败：[object Object]」。服务端日志实锤根因：`rename C:\...\1  3D\x.mp4 D:\...\qimeng-data\trash\...: The system cannot move the file to a different disk drive.`——`filing.MoveFile` 的跨挂载点回落只认 Linux 错误文本 `invalid cross-device link`（2026-09-22 初版理由：双平台 syscall 包对 EXDEV 暴露不一致），Windows 双盘布局（媒体库 C:、DataDir/回收站 D:）下漏判 → **删除（移入回收站）与本机同步通道的搬移全部 rename 失败且不走复制回落**；本机日常形态必现，直击铁律 4 与 ADR-0030。
- **修复**：跨挂载点判定改 **errno 主判 + 文本兜底**——新增 `filing/move_windows.go`（自备 `ERROR_NOT_SAME_DEVICE`=17，stdlib syscall 未导出）与 `filing/move_unix.go`（`syscall.EXDEV`）分平台 errno，文本兜底兼容两种 OS 文本（大小写不敏感）；`move_test.go` 新增 `TestIsCrossDeviceErr` 锁定事故原样文本。真机验证：两次删除（天使 13 (2)、玛莲妮娅 3 (2)）HTTP 200，文件+meta.json 落 D 盘回收站、库行清除、回收站列表含到期时间正常。
- **镜像挂点补齐**：备份导入的 TXT 片段段（`import.go`）与本机同步通道 TXT 导入（`localsync_runner.go`）此前写入片段后不刷镜像 → 桌面镜像长期落后于片段真相（用户此前多次反馈「桌面文件没改动」的根因之一）；两处补 `mirror.Refresh`（尽力而为同口径），挂点清单三处文档同步（GUIDE_API / DOMAIN_RULES §6 / SECURITY「作者总表镜像」）。
- **Web 错误提示**：8 处 `err instanceof Error ? err.message : String(err)` 在生成 SDK 抛错误体对象（非 Error 实例）时落成「[object Object]」——统一改走既有 `batchFailureReason` 提取器（`lib/batch.ts`，多卡片先例）；涉及 FileOpsDialogs / MoveDialog / AssetTagRow / CreateDirDialog / BackupCard。桌面壳复用 Web 界面自动生效。自测：vitest 320 全绿、oxlint 0 errors、vite build 通过、`go test ./...` 全绿。
- **文档**：CHANGELOG（本笔）、GUIDE_API.md、DOMAIN_RULES.md §6、SECURITY.md。

## feat(api+server+app): 跨库浏览记录兜底重映射——浏览事件增可选文件身份（fileName/relPath），同步通道按文件名并入本库统计（2026-10-11 第五百二十六笔）

执行 AI：DeepSeek-V4.1-Flash（主代理；协议/服务端/App 执行子代理，同模型）

- **背景（用户实测事故）**：App「同步浏览数据」把本地优先队列的浏览事件按**原 assetId** 直发当前连接的服务器；用户「本机模式→NAS/PC」切换后同步时，事件的 assetId 属于源库、目标库不存在 → 服务端旧行为=事件入库 `view_events`（真相源无外键）、物化表 `asset_daily_stats` 因外键拒绝跳过、202 返回（engagement.go「已删资产迟到事件」分支）→ 所有 JOIN assets 的口径（趋势/榜单/作者标签/App 统计页）都不显示，只有 overview.totalViews（直接数事件流）计入——表现为「同步记录不显示」+ 桌面端计数虚高（同一批数据两个口径劈叉）。
- **实查证据**：PC 库实查 273 条此类孤儿事件（`view_events` 有行、`assets` 无对应 assetId）。
- **协议（ADR-0037）**：`ViewEventReport` 增可选 `fileName`/`relPath` 文件身份；新增 `EventsViewAccepted`（`outcome`: ok/remapped/unmatched）作为 `/events/view` 202 响应体（字段缺省/无法解析按 ok 处理，客户端不得据此判定失败）；端点 description 补兜底重映射语义。`make sdk` 三端重生成 + `api/sdk.lock` 同批更新（255 项）。
- **服务端**：`PostApiV1EventsView` 增只读解析（`resolveViewEventTarget`）——assetId 命中本库 → 原路径（ok，带 fileName 也不重映射）；未命中且带 fileName → 按文件名匹配本库（**唯一命中**，或经 relPath **精确等值**消歧后唯一）→ 事件/物化/会话去重全走重映射后目标 id（`outcome=remapped`，跨库合并）；零命中/仍歧义/未携带 → 维持「已删资产迟到事件」口径（事件入库、统计跳过、`outcome=unmatched`）。**歧义不猜、宁少勿错**——与 §10 导入通道「同名多命中取最早」刻意不同（实时通道从严）。查询侧新增 `AssetExists`/`ListAssetIDsByFileName`（sqlc 生成物入库）；不加索引、不加 migration（全表扫描只在 assetId 未命中的罕见路径发生，函数注释说明）。所有 202 出口统一带响应体。
- **App**：事件队列加 `fileName`/`relPath` 列（Room 2→3 迁移）、浏览时携带文件身份、同步完成如实提示「N 条已按文件名并入 / M 条未匹配未计入统计」。（App 端实现由执行子代理完成，主代理与对抗审查复核后返工收口，见下。）
- **兼容性与部署顺序**：旧客户端+新服务端——旧格式请求 fileName/relPath 缺省即不参与重映射，行为同旧（响应体被忽略）；新 App+旧服务端——服务端 JSON 解码不拒未知字段，fileName/relPath 被忽略、行为同旧。**部署顺序建议：先重部署 NAS 服务端，再装新 APK**（否则跨库并入不生效，App 端提示按旧口径）。
- **自测**：server `gofmt -l .` 空、`go build ./...` 通过、`go test ./...` 全绿（新增 `engagement_remap_test.go` 6 用例：唯一命中重映射+幂等重发不双计、同名歧义 unmatched、relPath 消歧、assetId 命中不重映射、无 fileName unmatched、重映射后会话去重按目标生效；既有 engagement 用例全数保持通过）；`make sdk` 成功且 `api/sdk.lock` 更新（255 项）；Android `:core:data`/`:feature:detail`/`:feature:manage` 单测全绿 + `:app:assembleDebug` 通过；Web `tsc -b` 通过（生成物变更无手写消费面）。
- **对抗审查与返工**：reviewer 子代理独立复跑（go 三连 + Android `--rerun-tasks` 重跑 + sdk.lock 逐条 sha256 验真）总评「可交付」；P2 返工收口——等身份期间 VM 销毁（详情未加载完即退出）改 `NonCancellable` 补入队：取消语义原样穿透、open 事件不因等待窗口比旧实现更早丢（新增单测「等身份期间 VM 销毁仍按无身份补入队」）；P3 并入：`postViewEvent`/`postDwellEvent` 收口复用 `postViewReport`（同一 POST 骨架禁第二处手抄）、一处行折叠格式清偿。审查登记两条固有盲区：Room 2→3 无 MigrationTestHelper 真机升级实测（3.json 与生成物 identityHash 逐字一致 + 1→2 同款先例，风险低）、`SdkViewEventSender` outcome 解析无 JVM 单测（旧服务端空体→null 由生成 SDK `ApiClient.responseBody` 空体分支代码级成立）。
- **文档**：openapi.yaml、sdk.lock、ADR-0037（新）、ADR INDEX、DOMAIN_RULES §5（含与 §10 差异）、GUIDE_API、CHANGELOG（本笔）、HANDOVER。

## fix(server): 作者来源区形态归一——单个「来源」标记行 + 出处并排（作者 126「两个来源」事故）（2026-10-11 第五百二十七笔）

执行 AI：DeepSeek-V4.1-Flash（主代理）

- **背景（用户实测事故）**：用户核对桌面镜像《作者总表.txt》发现作者 126 `hornyherring` 的来源区写成两行标记（`来源  hanime1` / `来源  小红车`），而全库其余块都是手写形态「一个「来源」标记行 + 多个出处词两空格并排」（如 `来源  kemono  小红车  老王论坛`）。实查证据：线上片段 127 个作者块中，来源标记行 >1 的**仅此 1 块**（adb 真机侧同名 TXT 亦同）。
- **根因（写入端按词补标记行）**：`authoring.ReplaceSources`（PUT /authors/{id}/sources 的 `mode=replace`，编辑页语义）对每个来源词各写一行 `来源  <词>`；`AppendAuthorBlock`（程序化新建块）同款逐词循环。两条路径都会造出多个「来源」标记行，与手写清单形态不一致；解析端 `platformName` 又把标记行同行内容整体当一个词，使「并排多出处」在词级读不回（来源词预填统计对并排形态失效）。
- **修复（写入端归一 + 解析端词级拆分）**：
  - 新增 `authoring.replaceSourceRegion`（**唯一实现**，三条写入路径共用）：来源区整段重排为单标记行 + 出处两空格并排；`words` 空则来源区整段移除（标记行不残留）；落点=既有来源区原位（首个被删区域行）/无来源区则「作品」标记行前/裸块则编号行后。
  - `ReplaceSources` 复用该实现；`AppendSources` 改**词级**并入后整段重排（既有词在前、新词并排在后，无新词零写入幂等——ADR-0023「并入去重、永不覆盖既有来源」的词集语义不变）；`AppendAuthorBlock` 新建块只写一行标记。
  - `ParseAuthorBlocks` 的来源标记行改按两空格拆分（与编号行别名同分隔规范）→ `来源  a  b` 读回 `["a","b"]`，保证「写出 → 读回」**词级恒等**，并让词表预填的「每个词被多少不同作者引用」统计对并排形态生效；标记行独占一行时后续裸行仍逐行收词（旧形态兼容读入）。
  - 顺带收敛：来源区夹在两个「作品」标记之间的病态布局，append 旧行为会在首个「作品」标记前**另开第二个来源区**，现改为整段重排为单区。
- **自测**：`go build ./...` 通过；`internal/authoring` 全绿——新增 `TestSourceRegionCanonicalForm`（4 子例：126 事故原文替换后收敛为单标记行、并入新词单行且词级去重、新建块多词仅一行标记、既有并排形态无新词零写入）与 `TestParseAuthorBlocksSourcesWords`（6 例：并排拆词/裸行收词/`出处` 同义/历史双标记行兼容读入/含单空格出处词不拆/无来源区）；6 个锁定旧形态的既有用例同步更新（`TestAppendSources` 5 例 + `TestAppendSourcesSandwichedSourcesRegion` 1 例，后者加「来源标记行数 == 1」断言）。`go test ./...` 仅 `TestLibraryRevisionBumpOnScanFinish` 失败一次 = `t.TempDir` 清理与缩略图后台任务竞态（该单测复跑 3 次全过，与本次改动无关）。
- **文档**：DOMAIN_RULES §6（新增「来源区形态」规范 + 文头记档）、CHANGELOG（本笔）。
- **存量数据待办（用户拍板后执行）**：线上片段作者 126 仍是两行标记——需待服务端以本次改动重启后，经 `PUT /authors/{id}/sources`（`mode=replace`，词表原样回传）走正规端点收敛，并触发作者总表镜像刷新把桌面文件对齐（**不改运行中的库、不手改 kv_settings**）。
