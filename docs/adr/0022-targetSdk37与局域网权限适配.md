# ADR-0022：targetSdk 升 37 与局域网权限（ACCESS_LOCAL_NETWORK）适配

## 背景（Context）

- App 的 compileSdk 自 S3 基座升级批（2026-09-13）起已为 37，但 targetSdk 维持 36（memo §2「单独决策项」，任务P P4 评估批挂账 #47 台账）。
- Android 17（API 37）对 targetSdk 37 应用引入一批行为变更（官方 behavior-changes-17，2026-09-18 执行日逐条对照，材料=仓库外《待拍板-任务P-P4-targetSdk37.md》）。逐条评估结论：**唯一硬适配点 = ACCESS_LOCAL_NETWORK 运行时权限强制**（本 App 数据面——浏览/播放/上传/打点——全走局域网连 NAS，未授权即连不上服务器）；其余条目（后台音频 WIU 加固、CT 默认启用、RemoteViews 上限、大屏忽略朝向、ECH、static final 不可变等）经对照无涉或低风险（详见材料影响表）。
- 分发形态=侧载不上 Play，无商店 targetSdk 强制时限；维持 36 为零成本免费延期。升与不升均为合法选项，AI 不擅自拍板（需用户确认）。
- 用户拍板（2026-09-19）：**升**。

## 决策（Decision）

1. `:app` targetSdk 36 → **37**（单 commit，随本 ADR 落地；回退=revert 该 commit）。
2. manifest 新增 `ACCESS_LOCAL_NETWORK` 权限声明（:app，权限归并后全 App 生效）。
3. 运行时请求两个入口（判定口径单源 `core:network` 的 `ANDROID_17_SDK_INT`/`shouldRequestLocalNetworkPermission` 纯函数+单测）：
   - **登录提交门**（feature:login LoginScreen）：点登录/键盘 Done 先过权限门——未授权先请求，授权即登录，**拒绝不出网**、显示定向引导文案（login_error_local_network_denied，引导去系统设置开启）；
   - **冷启动补请求**（:app MainActivity）：覆盖「已登录老用户升级装包后不再经过登录页」的场景，首次冷启动补一次系统询问；拒绝不做动作（既有失败态兜底+下次冷启动再补，系统对永久拒绝不重复弹窗，无打扰循环）。
4. **刻意不做**（记档不顺延）：后台音频 mediaPlayback FGS（当前无退后台音频需求，Android 17 上被静音待真机 17 实测定性，有需求另立批）；本机模式（回环地址）的权限豁免（回环不走局域网无需该权限，为免 UI 内嵌地址解析逻辑，统一走门，误授无害）；usesCleartextTraffic→Network Security Config 迁移（官方仅预警未来弃用，Android 17 不强制，另记账）。

## 状态（Status）

Accepted（2026-09-19，用户拍板「升级」）

## 后果（Consequences）

- 正向：targetSdk 与 compileSdk 对齐（37/37），Android 17 适配债清零；#47 台账（AGP9 基座升级卷）就此整体关闭；不再积「落后两个大版本」的行为变更债。
- 代价/风险：①Android 17+ 设备上用户会多看到一次「本地网络」系统询问（一次授权长期有效，拒绝后有引导路径）；②已登录用户在 Android 17 上拒绝权限且不经登录页时，表现为既有「加载失败」态而非定向提示（冷启动补请求+重装/重登可恢复，记档接受）；③后台音频行为在 Android 17 真机上的实际表现待实测定性（本机现网 ROM ≤16 无感）。
- 对旧系统零影响：新检查逻辑不存在于 Android 16 及以下系统，targetSdk 数字变化不改变其上的任何权限与行为（任务P P4 试装对照实测：API 35 模拟器上 37 包与 36 包全链等价）。
- 联动文件（供 INDEX 反查）：`android/app/build.gradle.kts`（targetSdk 行）、`android/app/src/main/AndroidManifest.xml`、`android/app/src/main/java/media/qimeng/app/MainActivity.kt`、`android/feature/login/`（LoginScreen.kt、strings.xml）、`android/core/network/`（LocalNetworkAccessPolicy.kt 及其单测）、`docs/CHANGELOG.md`（第三百三十五笔）、仓库外《待拍板-任务P-P4-targetSdk37.md》。
