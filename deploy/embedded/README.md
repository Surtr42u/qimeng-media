# 内嵌形态（ADR-0015 形态 B）三件套装配

> 任务T T6 / 任务U11 批次D。App 内嵌服务端的 jniLibs 供应与装配口径。
> **jniLibs 目录不入 git**（约 57MB 二进制）——CI/普通构建缺目录不受影响（App 仍是
> 正常 NAS 客户端，本机模式点开时 Service 会以「未随包打包」通知明确告知）；
> 出内嵌终包前必须先跑 `make app-embedded-*`。

Release 云端出包的 Debug 单测、core:model 测试及 Release Lint 现在阻断候选 APK 上传；仍须通过同 SHA 完整门禁及审查才能部署。资格与执行边界见 [部署入口](../README.md#android-候选包与发布资格)，不以旧装机记录外推新版结果。

## 三件套

| jniLibs 文件 | 来源 | 说明 |
|---|---|---|
| `arm64-v8a/libqimeng.so` | `make server-android-arm64`（仓库自构建，纯 Go modernc） | Go 服务端改名投放，W^X 合法位 |
| `arm64-v8a/libffmpeg_cli.so` | hzw1199/Android-FFmpeg-Prebuilt **锁 commit** | FFmpeg 9.0 arm64 CLI，LGPL-2.1 |
| `arm64-v8a/libffprobe_cli.so` | 同上 | ffprobe 同 commit 同源 |
| `x86_64/libqimeng.so` | `make server-android-amd64`（需 NDK） | 模拟器验壳专用（Go arm64 经 ndk_translation 必崩，m6-poc 三次 SIGSEGV 实证） |
| `x86_64/libffmpeg_cli.so` / `libffprobe_cli.so` | **复用 arm64 成品副本** | CLI 型 arm64 二进制可经系统 binfmt 翻译执行（模拟器 adb shell 域实证 `-version`/`-encoders` 正常；App 域 exec 验壳时实证） |

改名 `.so` 是 AGP jniLibs 打包约定；文件本体是标准 ELF，服务端经
`QIMENG_THUMBNAIL_FFMPEG_PATH` 显式绝对路径 exec，不依赖扩展名语义。

## 供应链锁定（hzw1199）

- 仓库：<https://github.com/hzw1199/Android-FFmpeg-Prebuilt>（无 Release，二进制在
  main 分支目录——**必须锁 commit hash 下载**）
- 锁定 commit：`90231cc0105aef4f76926b911535f5eb73511b86`（2026-08-04 「FFmpeg 9.0」）
- 下载路径（该 commit 下）：`ffmpeg-9.0/bin/ffmpeg`、`ffmpeg-9.0/bin/ffprobe`
- SHA-256（与 m6-poc 实测通过的本地副本逐字节一致）：
  - ffmpeg `9085507b0dc32643b4d6d084a7e7d3469ef17907a7ba15c22d3997ed09c932aa`
  - ffprobe `d8e929fcb2b3b5a1a7c8dc234f6d98834eaf8ff2ce408a85060a470b243e0bb5`
- 许可：FFmpeg 9.0 `--disable-gpl --disable-nonfree`（LGPL-2.1 兼容）+ NDK r28
  minSdk 28；16KB 页对齐已实测（LOAD Align=0x4000，m6-poc）。
- **双宿主 fetch 脚本（同锁孪生）**：本机 Windows = `fetch-ffmpeg-arm64.ps1`，
  CI/Linux = `fetch-ffmpeg-arm64.sh`（commit/哈希逐字节同源；2026-10-02 CI 产物
  完整性门禁随批新增）。升级 FFmpeg = 四处同步：`.ps1` 哈希表、`.sh` 常量区、
  本 README 哈希行、发布说明。

## libwebp 事实（U11 批次D 定案）

该成品**不含 libwebp 编码器**（配置串无 `--enable-libwebp`、二进制内 `libwebp`
字符串 0 次、真机 `ffmpeg -encoders | grep webp` 空——三源实证；FFmpeg 无原生
WebP 编码器，唯一来源是外部库）。服务端已按此自适应：启动探测缺 libwebp 时静图
缩略图降级 **mjpeg** 输出（`server/internal/thumbnail/stillformat.go`，webp 可用
则维持原行为——NAS/桌面部署零变化）。含 libwebp 且可内嵌分发的合规 arm64 CLI
现货经调研不存在（ffmpeg-kit 是 LGPL-3 .so API 套件非 CLI；Termux pkg 是 GPL-3）。
若未来出现合规现货或自编译（ffmpeg-android-maker 另立批），换二进制即可，
服务端探测自动回到 webp。

## 装配命令

```makefile
make app-embedded-arm64   # arm64 三件套（release 终包）
make app-embedded-x86_64  # x86_64 验壳件（模拟器）
make app-embedded         # 全 ABI（本地验壳+出包一步到位）
```

装配后正常走 `gradlew assembleRelease` / `assembleDebug`（ABI 分层见
`android/app/build.gradle.kts` buildTypes 注释）。

## 当前设备边界（2026-10-25）

arm64 内嵌 Release 经云端出包后，已验证三件套/锁定哈希、提交身份、同签名保留数据覆盖安装、安装 APK 字节一致、首次安装记录保留、冷启动与健康/就绪；最新版本、现场范围及云端门禁证据统一见 [HANDOVER](../../docs/HANDOVER.md)。内嵌同样运行 Go 扫描调度，机制和成本边界见 [服务端说明](../../server/README.md#媒体库后台扫描)。qimeng_api35 此前的 x86_64 App 域启动在 SQLite 初始化触发 SIGSYS（syscall 6），尚未修复；本次未重验 x86_64，arm64 出包成功或 shell 域历史记录不代表该 App 域已验收。模拟器保持关闭，未改变 Android 安全策略；不把该失败套用为 arm64 手机结论。

## 红线索引

- **W^X**：只 exec `nativeLibraryDir` 成品；严禁解压/落盘 `filesDir` 后 exec
  （targetSdk≥29 SELinux）。`useLegacyPackaging=true`（安装期解出到 nativeLibraryDir）。
- **端口单值互指**：`ServerAddress.LOCAL_MODE_PORT`（18430）⇄ `EmbeddedServerConfig.LISTEN_ADDRESS`
  ⇄ `deploy/termux/*` 三脚本 ⇄ 本 README。改任何一处须同步全部。
- **监听回环**：`QIMENG_LISTEN=127.0.0.1:18430`，不暴露局域网。
- **LGPL-2.1 合规**：版权与独立可替换分发说明见
  `android/app/src/main/assets/THIRD_PARTY_NOTICES.txt`（随 APK 分发，用户可解包读取；
  U11 批次E reviewer P2-5 勘误：暂无 App 内关于页入口展示，后续 UI 批可补）；
  可替换性=按上表换投新成品即可（fetch 脚本哈希锁同步更新）。
- 真机 18430 端口若被 Termux 形态 A 占用，Service 子进程秒退并通知
  （详见 `EmbeddedServerService` KDoc「重启语义」）。


## 候选上传门禁的回归边界

APK候选仍须单测/Release Lint成功后才上传，之后还需同SHA完整CI/安全/文档与审查资格。`tools/test_release_gate.py`在云端docs检查中对固定模板锁定顺序及失败阻断，并拒绝四类受控错误配置；隔离真实运行40174082810已确认Release构建/三件套成功后，唯一注入测试失败会跳过上传且无artifact（详见HANDOVER）。它不替代正常产物的同SHA门禁、内嵌三件套、SDK锁和版本/签名核验。

正常5eaa181c运行40174932988已验证Release构建/三件套/单测/Lint后成功上传；与失败运行形成正反对照。此工件未部署，后续源码变化必须另取同SHA候选，不能沿用该ZIP摘要冒充新版本。
