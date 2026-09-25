package media.qimeng.app.feature.detail

import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.BitmapImage
import coil3.Image
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size
import coil3.target.Target
import media.qimeng.app.core.model.MediaKind
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 详情页（任务I I7 沉浸复刻改版；任务K K1 黑底污染清偿改舞台底色口径）：按 GUIDE_UI
 * §详情页 L158-160 沉浸 4 层组织回归——第一屏媒体舞台 edge-to-edge 全出血（整屏高，底色
 * 随沉浸切换：chrome 显=主题底/沉浸或播放中=黑，见 [stageBackdropColor]；图片态
 * ZoomImageView 链 / 视频态 BiliPlayerView 链）+ 上下渐变 chrome 浮层（顶：返回/n/N/信息；
 * 底：点赞N/收藏/标签/作者四胶囊——任务W W3「整理」退役换作者，[DetailChromeBars]）+
 * 单击显隐（图片态单击舞台切 chrome，L271-276；S9 起图片常态系统栏不随切——恒透明
 * 显示，播放态/放大态系统栏随控制条显隐，见 [SystemBarsImmersiveEffect]）；媒体层下方仅剩错误横幅
 * （任务X X2 下滑区裁剪终态：标题/meta/标签行整段退役，页面主体=舞台+四胶囊；
 * chrome 挂在舞台盒内随第一屏滚动，只覆盖第一屏）。
 *
 * 沿革：3a 骨架排版（Web AssetDetailPage 移植）→ 3b/3c/3d 沉浸/播放器/全量接线 →
 * G1a/G1b Web 排版页 → I7 基准切回 GUIDE_UI 沉浸复刻（台账 #33 用户拍板「1 a」，
 * 推翻 09-05 Web 排版基准）。D1 图片全屏覆盖层退役（裁决记档见 [ImageStage] KDoc）；
 * D2 曾立视频两级全屏制（拍板⑦，09-07），已被任务K 推翻——2026-09-09 用户拍板 #23 改
 * 单级横屏全屏（NONE⇄LANDSCAPE，见 VideoFullscreenStateMachine）。chrome 组件见
 * DetailChromeBars.kt（渐变顶/底操作层）。
 *
 * 视频态 chrome 语义：播放器活动期（播放/暂停/ENDED）chrome 让位播放器自有控制器
 * （VideoStage 上报 onPlayerActiveChanged，chromeEffective=false）；播放中按返回先退
 * chrome 浏览模式（暂停+海报态+chrome 显示，L168/L279——BackHandler 拦截在 VideoStage）。
 * 海报态单击=起播（L163 旧版语义优先，与 L271 单击切 chrome 的冲突记档：海报态不接
 * chrome 切换，▶ 随 chrome 恒显）。
 *
 * 视频播放态 chrome（任务S S2，2026-09-19 用户拍板「单击变成显示 ui 就是视频的 ui 和
 * 上方的 ui；现在视频播放不显示手机状态栏；双击才是暂停」=解冻令，推翻 GUIDE_UI L185-186
 * 旧口径「竖屏单击播停/横屏单击显隐/竖屏双击无功能」——冲突优先级用户最新要求 > 规格书）：
 * 播放期单击切「播放器控制器（视频的 ui）」显隐——控制器显隐由桥接件 showController
 * 单点上报（onControllerVisibilityChanged）经 VideoStage/DetailMediaStage 转发为
 * [playbackChromeVisible] 镜像（G9 自动隐藏/拖拽隐/长按隐/ENDED 强制显同拍同源，无
 * 第二事实源）；双击=播停。**详情顶栏播放态恒不显示**（任务S S9，2026-09-19 用户再次
 * 拍板纠正批S2/S7 拼接偏差：「应该是显示播放时的 ui，就是下方进度条，这时候依旧会显示
 * 上方的手机状态栏，不是详情页 ui，就是 b 站手机竖屏的那种」——S2 的「chromeEffective
 * || 播放态镜像」顶栏追加显隐废止，顶栏只在非播放态按 chromeEffective 显隐）；
 * **系统栏随控制条联动**（S9 定稿两态：显示态=控制条+状态栏透明浅图标、沉浸态=控制条+
 * 状态栏全隐纯视频，单击两态切换；S7「播放期透明恒显」与 S2「顶栏随镜像显隐」两截半
 * 成品由本拍板合流，三分支判定记档见 [SystemBarsImmersiveEffect]；S11 起隐藏侧改瞬时
 * ——controlWindowInsetsAnimation 零时长消灭平台 hide 动画拖尾，播放器控制条同批复位
 * 瞬时显隐=「都瞬时」定稿，见 InstantSystemBars.kt KDoc）；底色纯黑/锁滚/
 * 底部四胶囊隐等 chromeEffective 口径全部不动（U6 逐帧动画机制不动）。
 *
 * 状态栏图标随背景反色（任务S 批S10 件1，2026-09-19 用户拍板「状态栏和背景一个色了，
 * 应该是白色上面状态栏就是反色这种……就是手机相册那种」）：图标明暗收敛本页单值
 * statusBarDark——视频态恒浅（黑底）、chrome 显态随系统明暗（主题背景）、chrome 隐藏态
 * 按原图顶部 1/3 亮度采样自适应（48px allowHardware(false) 小图，纯函数判定
 * StatusBarLuminance.kt，失败兜底浅）；同批修复 apply 处「取反后 apply」双重否定
 * （S7 引入，净输出恒与判定相反=用户反馈根因，记档见 SystemBarsImmersiveEffect KDoc）。
 *
 * 图片态缩放沉浸（2026-09-13 用户实测反馈驱动，非旧版对齐——旧版单击无条件切 chrome）：
 * 图片放大跨过 1.05x（ZoomImageView.emitZoomImmersive 上报）即并入 chromeEffective——
 * 上下渐变 chrome 隐藏、舞台底转黑、滚动锁死（同沉浸口径，消除「放大时上下白色
 * 渐变压在图上」观感；系统栏 S9 起随放大沉浸隐藏——批C「状态栏隐藏」口径在放大态
 * 恢复，图片常态仍 S7 恒透明显示，见 [SystemBarsImmersiveEffect]）；缩回落回收束点恢复。放大态单击舞台无操作（防 chrome 显隐奇偶
 * 漂移）。链路：ZoomImageView → ZoomableOriginalImage 桥 → DetailMediaStage → 本页
 * zoomImmersive 单点并入 chromeEffective。
 *
 * @param assetId 路由参数（ViewModel 经 SavedStateHandle 同键读取；此处显式保留供预览/测试）
 * @param onBack 返回（壳层 popBackStack）
 * @param onOpenAsset 跳资产（壳层导航 push 叠栈）：兄弟资产滑动换件走此回调（批次清单
 *   就是当前清单，不换批）——push 叠栈 = 浏览历史语义。任务W W7：沉浸态滑切经
 *   [SiblingSwipeImmersionRequest] 交接单让目标屏 chromeVisible 以 false 起步
 *   （对标旧版「媒体层恒全屏」；海报态滑切目标仍落排版态）
 * @param onOpenAuthor 跳作者集合页（作者 Sheet「进入作者主页」，壳层导航 push 叠栈）
 * @param onEditAsset 跳资产编辑页（作者 Sheet「编辑作者与来源」，2026-09-25 上传挂靠
 *   退役批：作者关联与来源维护收口编辑页；壳层导航 push 叠栈）
 */
@Composable
fun DetailScreen(
    assetId: String,
    onBack: () -> Unit,
    onOpenAsset: (assetId: String) -> Unit,
    onOpenAuthor: (authorId: String, displayName: String) -> Unit = { _, _ -> },
    onEditAsset: (assetId: String) -> Unit = { _ -> },
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 沉浸模式 chrome 显隐（媒体单击切换；默认可见=排版态，网格入口基线不变）。
    // 任务W W7（#20 对标旧版「媒体层恒全屏」）：沉浸态滑切是 push 叠栈（新路由实例），
    // 源屏置位 SiblingSwipeImmersionRequest 后，本 initializer 首次组合时消费命中 →
    // 目标屏以 chromeVisible=false（沉浸）起步，滑切目标保持全屏语义；海报态滑切不置位，
    // 目标落排版态不变。只有新组合实例才执行 initializer（返回 pop/进程重建走
    // rememberSaveable 恢复，不读交接单），栈内各屏互不干扰
    var chromeVisible by rememberSaveable { mutableStateOf(!SiblingSwipeImmersionRequest.consume()) }
    // 视频播放器活动态镜像（VideoStage 上报）：活动期 chrome 让位播放器控制器
    var playerActive by remember { mutableStateOf(false) }
    // S2 播放态镜像（2026-09-19 拍板引入，S9 同日定语义，见类 KDoc）：桥接件控制器显隐
    // 单点上报——播放期驱动系统状态栏显隐（B 站竖屏两态：控制条显=状态栏透明显示、控制
    // 条隐=状态栏隐藏）；详情顶栏不再消费本镜像（S9：播放态顶栏恒不显示）。remember 非
    // saveable：播放会话内 ephemeral 态（进程重建即回海报态，同 stageMode 口径），退出
    // 播放态随 onPlayerActiveChanged(false) 复位，不带入浏览态
    var playbackChromeVisible by remember { mutableStateOf(false) }
    // 图片态缩放沉浸镜像（ZoomImageView 上报，2026-09-13 用户反馈「放大时上下白色渐变
    // 压在图上观感不适」）：跨过 1.05x 即时 true、回落收束点 false（非对称滞回在搬运件）。
    // remember 而非 rememberSaveable：放大态属 ZoomImageView 实例态，进程重建后 View 重建、
    // 图片回基态（resetZoom 上报 false）——saveable 会造成「UI 沉浸而图未放大」的假沉浸，
    // 故不持久化（chromeVisible 仍 saveable：chrome 开关是用户显式选择，语义不同）
    var zoomImmersive by remember { mutableStateOf(false) }
    val chromeEffective = chromeVisible && !playerActive && !zoomImmersive
    // S10 件1（2026-09-19 用户拍板「白色上面状态栏就是反色这种……就是手机相册那种」）：
    // 图片资产原图顶部 1/3 亮度采样值（null=未加载/失败/视频资产）——chrome 隐藏后状态栏
    // 浮在图片内容上，图标明暗按本值自适应反色（相册语义）；null 兜底浅色图标（黑底语义，
    // 沉浸舞台底=黑）。采样链见下方 DisposableEffect（48px allowHardware(false) 小图），
    // 判定纯函数见 [statusBarIconsDarkForLuminance]（StatusBarLuminance.kt，单测锁定）。
    // remember 非 saveable：采样值是图片内容派生态，进程重建随采样重跑自愈，不持久化
    var imageTopLuminance by remember { mutableStateOf<Double?>(null) }
    // 系统状态栏图标明暗单值（S10 收敛进本页单一状态，SystemBarsImmersiveEffect 只消费）：
    // - 视频播放态（playerActive）→ 恒 false 浅色图标（舞台底=黑，U4 拍板，与控制条显隐
    //   无关——显示态控制条也是黑底渐变，B 站同款浅图标）；
    // - chrome 有效显示（排版/浏览态）→ 沿用 chrome 显态判定（浮在主题背景上：浅色主题=
    //   深色图标，随系统明暗，X7 链）；
    // - 其余（图片态 chrome 隐藏/放大沉浸/视频海报态沉浸）→ 浮在图片内容上，按采样亮度
    //   自适应；null 兜底浅色图标（黑底语义）。放大态状态栏虽隐藏（systemBarsShouldShow
    //   false），明暗值仍随本判定收敛、回显时生效——放大后浮的是图片内容，同相册语义。
    val darkTheme = isSystemInDarkTheme()
    val statusBarDark = when {
        playerActive -> false
        chromeEffective -> statusBarIconsDark(chromeVisible = true, isSystemDarkTheme = darkTheme)
        else -> imageTopLuminance?.let { statusBarIconsDarkForLuminance(it) } ?: false
    }
    // 信息/快速转跳/作者 BottomSheet 开关（纯 UI 弹层无数据请求，页面局部状态；进程重建后
    // 关闭态恢复——与 chromeVisible 同 rememberSaveable 语义）。任务V V3：「快速转跳」
    // 不进首屏四胶囊（主代理保守裁决待用户确认），入口随旧图标行消失——jumpSheetVisible
    // 与 DetailJumpSheet 挂载保留（组件保留裁决），当前无触发点、恒为关闭态。任务W W3：
    // 作者胶囊补 authorSheetVisible 入口（点 DetailAuthorSheet）。
    var infoSheetVisible by rememberSaveable { mutableStateOf(false) }
    var jumpSheetVisible by rememberSaveable { mutableStateOf(false) }
    var authorSheetVisible by rememberSaveable { mutableStateOf(false) }
    // S9 系统栏三分支编排（2026-09-19 拍板纠正批S2/S7）：图片常态恒显示透明（S7 保留）；
    // 视频播放态随 [playbackChromeVisible] 显隐、图片放大态恒隐（B 站竖屏两态语义）。
    // S10 起显隐与图标明暗两值均在上方收敛成单值（[systemBarsShouldShow] 纯函数 +
    // [statusBarDark] when），效果只消费（KDoc 见 [SystemBarsImmersiveEffect]）
    SystemBarsImmersiveEffect(
        systemBarsVisible = systemBarsShouldShow(
            zoomImmersive = zoomImmersive,
            playerActive = playerActive,
            playbackChromeVisible = playbackChromeVisible,
        ),
        statusBarDark = statusBarDark,
    )

    // 3d 生命周期接线：onPause → dwell 当前段兜底 flush + 进度 force 补报；onResume → dwell
    // 开新段（分段累加口径，见 DwellSessionTracker）；onDispose（组合离场，先于 VM onCleared
    // 且 viewModelScope 存活，是补报的可靠送达路径）→ dwell leave flush + 进度 force 补报
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onScreenPaused()
                Lifecycle.Event.ON_RESUME -> viewModel.onScreenResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenDisposed()
        }
    }

    // 3b 预载链消费（拍板③）：VM 只发目标清单，Coil 预取入队/取消在 UI 层——图片按原图
    // 尺寸（口径②：.size(Size.ORIGINAL) 不降采样），视频海报帧是小缩略图按默认档；
    // memoryCachePolicy 显式 ENABLED 确保预取产物写内存缓存（邻位切换首帧即出图）。
    // key=目标清单：清单变化（新邻位 url 就绪）或离场时，先 dispose 全部旧 Disposable
    // 再入新（对照旧版 preloadDisposables 按 key 取消语义；Map<id,Disposable> 兼防
    // 清单内重复 id 重复入队；LEGACY_REQUIREMENTS E 生命周期清理）
    DisposableEffect(state.preloadTargets) {
        val disposables = mutableMapOf<String, Disposable>()
        state.preloadTargets.forEach { target ->
            if (target.assetId in disposables) return@forEach // 防御：重复 id 不重复入队
            val request = ImageRequest.Builder(context)
                .data(target.url)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .apply {
                    if (!target.isVideo) {
                        size(Size.ORIGINAL)
                        // 任务U10-5（2026-09-14 用户拍板）：原图即看即取**不落盘**——
                        // 图片原件体积大，落盘缓存会让磁盘缓存无谓膨胀（用户实测 2.9GB），
                        // 且反复写盘损耗存储；会话内回看由内存缓存兜底，离场即弃。
                        // 视频海报帧是小缩略图，保持磁盘缓存（减少流量）。
                        diskCachePolicy(CachePolicy.DISABLED)
                    }
                }
                .build()
            disposables[target.assetId] = SingletonImageLoader.get(context).enqueue(request)
        }
        onDispose { disposables.values.forEach { it.dispose() } }
    }

    // 兄弟资产切换回调单源（拍板③：目标解析失败（越界/无批次/缺参）静默不动；批次清单
    // 就是当前清单，不换批；push 叠栈 = 浏览历史语义）。任务W W7（#20 对标旧版「媒体层
    // 恒全屏」）：源屏沉浸态（chromeVisible=false）且目标解析成功时，push 前置位沉浸
    // 交接单 → 目标屏 chromeVisible 以 false 起步，滑切目标保持全屏；置位放在 moveBy
    // 成功分支内 = 只有真实发生的导航才携带语义，解析失败不留孤儿标志误染后续入口
    val onSiblingNavigate: (Int) -> Unit = { delta ->
        viewModel.moveBy(delta)?.let { targetId ->
            if (!chromeVisible) SiblingSwipeImmersionRequest.request()
            onOpenAsset(targetId)
        }
    }

    if (state.isLoading) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 加载/错误态保留朴素顶行（沉浸 chrome 随舞台挂载，加载期无舞台可浮）。
            // 不再显示「加载中」文案（2026-09-18 用户反馈去除）：详情 JSON 走回环
            // 毫秒级，整屏文案一闪反而显慢；海报慢链路已由 md 先行承接（VideoStage
            // 分层）。加载期仅留返回键顶行、正文留白，数据到位整屏换入。
            DetailTopRow(onBack = onBack)
        }
    } else if (state.asset == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            DetailTopRow(onBack = onBack)
            DetailErrorState(
                message = state.errorMessage,
                onRetry = viewModel::retry,
            )
        }
    } else {
        val asset = requireNotNull(state.asset)
        // S10 件1：图片资产原图顶部亮度采样（与原图加载同时发起，chrome 隐藏反色判定面
        // 就绪在先——2026-09-19 用户拍板「白色上面状态栏就是反色这种……手机相册那种」）。
        // 48px 小图请求（allowHardware(false)=软件位图，硬件位图禁 getPixels；size 参与
        // Coil 内存缓存 key，与口径②原图请求互不命中、互不干扰）。缓存口径：内存 ENABLED
        // （会话内回看秒回）；磁盘 DISABLED——守 U10-5 拍板「原件不落盘防磁盘缓存膨胀」，
        // 采样请求同样不得把原件写进磁盘缓存。仅图片资产采样：视频资产无「原图」语义
        // （origUrl 是视频原件），海报态沉浸按 null 兜底浅色图标。onError 不回调即保持
        // null → 兜底浅图标（黑底语义）。纯函数判定见 StatusBarLuminance.kt（单测锁定）
        val sampleOrigUrl = asset.origUrl
        DisposableEffect(asset.id, sampleOrigUrl) {
            imageTopLuminance = null // 切资产先回兜底值，旧资产采样值不串新资产
            if (asset.mediaType == MediaKind.VIDEO || sampleOrigUrl.isNullOrEmpty()) {
                onDispose { }
            } else {
                val request = ImageRequest.Builder(context)
                    .data(sampleOrigUrl)
                    .size(STATUS_BAR_SAMPLE_SIZE_PX)
                    .allowHardware(false)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .target(
                        object : Target {
                            override fun onSuccess(result: Image) {
                                val bitmap = (result as? BitmapImage)?.bitmap ?: return
                                val pixels = IntArray(bitmap.width * bitmap.height)
                                bitmap.getPixels(
                                    pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height,
                                )
                                imageTopLuminance = topRegionAverageLuminance(
                                    pixels = pixels,
                                    cols = bitmap.width,
                                    rows = bitmap.height,
                                )
                            }
                        },
                    )
                    .build()
                val disposable = SingletonImageLoader.get(context).enqueue(request)
                onDispose { disposable.dispose() }
            }
        }
        // 舞台底色单源裁决（任务K K1 黑底污染清偿，对齐旧版 MediaDetailFragment 颜色口径）：
        // chrome 有效显示=主题背景（≈旧 qmColorBg，日 #FAFAFA/夜 #1A1A1A，非纯黑——媒体
        // contain letterbox 与状态栏后区域同色，negate-inset 平移出的顶部区无黑条/主题色条
        // 错位）；沉浸（chrome 隐藏）或播放器活动期=纯黑。全卷唯一裁决点在此，经
        // DetailMediaStage.backdrop 逐层下发，子层禁止自带底色。2026-09-13 缩放沉浸：传参
        // 由 chromeVisible 改为 chromeEffective（并入 zoomImmersive），放大态 letterbox 随
        // 沉浸转黑（与「沉浸=纯黑」同口径）；与函数内 playerActive 判据幂等叠加不冲突
        //（图片态 playerActive 恒 false），视频态传参值逐位不变（zoomImmersive 恒 false）。
        // 用户 2026-09-13 晚终裁：单击进沉浸的「全屏渐变变黑」过渡取消，点击视觉对齐旧版
        // （旧版 setChromeVisible(false) 显式 BLACK 瞬切同款）——底色直切无动画。
        // 沿革：U3 曾按「瞬时切换观感生硬」反馈改 animateColorAsState 平滑过渡，当晚用户
        // 否决（渐变观感不适），回退为纯函数直读。StageBackdropTest 单测不受影响（纯函数
        // 签名与行为零变化）；chrome 条显隐口径 U6 起 = 底色瞬切 + 条内容 250ms 淡入淡出
        //（对齐旧版 setChromeVisible 逐帧语义，档位见 DetailChromeBars CHROME_FADE_MS）
        val stageBackdrop = stageBackdropColor(
            chromeVisible = chromeEffective,
            playerActive = playerActive,
            themeBackground = MaterialTheme.colorScheme.background,
        )
        // 第一屏舞台高度 = 壳层内容区高度（BoxWithConstraints.maxHeight）。X1 壳层改造
        // （2026-09-12 任务X）后 detail 路由不再吃壳层 innerPadding，内容区=全屏铺开且
        // 恒定（系统栏显隐不触发布局，GUIDE_UI L162 口径）；I7 初稿用
        // LocalConfiguration.screenHeightDp（整屏）曾因壳层钉位导致盒底越出视口、底部
        // chrome 落屏外——该前提已随 X1 失效，现 maxHeight 恒等于整屏，两口径合流
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            // 舞台全出血（K3c D1 重做，2026-09-10）：壳层 Scaffold 把 NavHost 内容钉在
            // [状态栏线, 导航栏线]，GUIDE_UI L272 要求详情页 edge-to-edge。任务I B案以
            // 「舞台盒 +comp 高度、绘制平移 -comp」实现，但走查实证（k2-10b 条带 + 本批
            // logcat 诊断：API 35 上 hide 派发后实时 inset 恒 128 不归零）平移后的舞台被
            // verticalScroll 视口裁剪在内容原点处——沉浸黑底态顶部露出一条壳层主题底色带
            // （D1），chrome 显态因舞台底色=主题底同色而从未显形。重做为**无平移**：
            // 舞台盒=内容区高（视觉几何与现网交付态逐像素一致，走查 1~5 项零位移），
            // 补偿值转用作**顶部背板色填充条**高度，画在滚动裁剪区之外（BoxWithConstraints
            // 不裁剪子项越界绘制）：沉浸态纯黑延伸到 y=0；chrome 显态=主题底与壳底同色
            // 无感。若设备 hide 后实时 inset 归零（本机不发生），记忆回退仍保垫条高度正确
            // （stageEdgeToEdgeCompensationPx 按态裁决，单测锁定）。
            // X1 改造后（2026-09-12 任务X）钉位前提失效：舞台盒顶=屏幕顶 y=0、自带
            // backdrop 全屏打底，本垫条 [-comp, 0] 恒画在屏幕外不可见——机制保留不大拆
            // （精密几何件，清偿交 X8 终审裁量），仅记档退化口径
            val density = LocalDensity.current
            val liveStatusBarTopPx = WindowInsets.statusBars.getTop(density)
            val liveNavBarBottomPx = WindowInsets.navigationBars.getBottom(density)
            // 记忆最近一次可见态 inset：实时值 >0 才刷新（零值不覆盖，沉浸期记忆值得以
            // 存续）；SideEffect 写回避免组合期反向写状态。首组初值=实时值（进页时系统栏
            // 恒可见），进程重建后同口径自愈
            val rememberedStatusBarTopPx = remember { mutableIntStateOf(liveStatusBarTopPx) }
            SideEffect {
                if (liveStatusBarTopPx > 0) rememberedStatusBarTopPx.intValue = liveStatusBarTopPx
            }
            val statusBarTopPx = stageEdgeToEdgeCompensationPx(
                liveInsetPx = liveStatusBarTopPx,
                rememberedVisibleInsetPx = rememberedStatusBarTopPx.intValue,
                barsVisible = chromeEffective,
            )
            val stageTopBandHeight = with(density) { statusBarTopPx.toFloat().toDp() }
            // 舞台盒高 = 可见态稳定高度（任务W W2 沉浸几何冻结，2026-09-11）：此前直接取
            // BoxWithConstraints.maxHeight（壳层内容区高），系统栏显隐会经壳层 Scaffold
            // innerPadding 改变内容区高——chrome 切换期舞台盒随 insets 动画逐帧变高变矮，
            // ZoomImageView 的 UP 松手 clampTranslation 按「当时盒高」垂直再居中（搬运件
            // 手势语义），若松手落在动画中间态（如 status 已回 nav 未回的 2272 高），随后
            // 盒高落回 2209 时 preserveScreenPosition 只按窗口位移补偿、不感知纯尺寸收缩
            // ——图片停驻在按瞬态盒高算出的位置，实测（emulator-5562）一个隐/显周期后
            // 持久偏移 +31.5px（=nav inset 63 之半），且下一次单击松手时再「跳回」。
            // 旧版不跳的根因 = 容器层几何恒定（fragment_media_detail.xml 单层 match_parent，
            // GUIDE_UI L162「系统栏显隐不触发布局变化，避免图片重新居中」）。本式以
            // screenPx（恒等式：内容高+两 inset，逐帧不变）减去 max(实时, 见过最大) 的两
            // inset 稳定值，把舞台盒高钉死在可见态稳定值——chrome 显隐全程与动画中间态
            // 盒高恒 2209 级，图片居中基准不再漂移；纯函数 stageImmersiveViewportHeightPx
            // 单测锁定（见 StageViewportHeightTest）
            val maxSeenStatusBarTopPx = remember { mutableIntStateOf(liveStatusBarTopPx) }
            val maxSeenNavBarBottomPx = remember { mutableIntStateOf(liveNavBarBottomPx) }
            SideEffect {
                // 单调上探：栏显隐动画的中间值（如 13/6）不回写，只有更大的稳定值
                //（字号/分屏等真实 inset 变化）才采纳；回缩类变化进程内保持旧值（记档）
                if (liveStatusBarTopPx > maxSeenStatusBarTopPx.intValue) {
                    maxSeenStatusBarTopPx.intValue = liveStatusBarTopPx
                }
                if (liveNavBarBottomPx > maxSeenNavBarBottomPx.intValue) {
                    maxSeenNavBarBottomPx.intValue = liveNavBarBottomPx
                }
            }
            val stageViewportHeightPx = stageImmersiveViewportHeightPx(
                liveContentHeightPx = with(density) { maxHeight.roundToPx() },
                liveStatusBarTopPx = liveStatusBarTopPx,
                liveNavBarBottomPx = liveNavBarBottomPx,
                maxSeenStatusBarTopPx = maxSeenStatusBarTopPx.intValue,
                maxSeenNavBarBottomPx = maxSeenNavBarBottomPx.intValue,
            )
            // X1 改造钳制（2026-09-12 任务X）：壳层解除 detail 钉位后内容区=全屏且恒定
            // （系统栏显隐不再触发布局，GUIDE_UI L162 口径达成），W2 冻结式依赖的恒等式
            // screenPx=内容高+两 inset 随之失效——沉浸期 nav inset 归零会令冻结式低估盒高
            // （1080x2400、status 滞留 128 例：2400+128+0-128-63=2337，盒缩 63px→图片中心
            // 位移 31.5px，正是 W2 当年清偿的位移类缺陷复活）。以内容区高兜底钳制：舞台
            // 盒=全屏恒高，图片 fit-center 恒屏幕居中（letterbox 上下对称）、进出沉浸零
            // 位移；maxSeen 上探机制原样保留——真实 inset 增长（字号/分屏）时冻结式先行
            // 响应，钳制不 bite；隐藏期冻结式只会低估不会高估（live≤maxSeen 恒成立），
            // 钳制方向安全
            val stageBoxHeightPx = maxOf(stageViewportHeightPx, with(density) { maxHeight.roundToPx() })
            val stageViewportHeight = with(density) { stageBoxHeightPx.toDp() }
            // 底部背板色填充条（W2，对称 D1 顶条）：沉浸冻结后舞台盒固定在可见态高度，
            // 栏隐藏期内容区底部多出的条带（[盒底, 屏底]）由本条以 stageBackdrop 补足
            //（沉浸=纯黑延伸到 y=2400；chrome 显态=主题底与壳底同色无感）。高度=屏高-
            // 盒高-实时 status inset（盒底随内容顶移动，剩余缺口全部落在底部）；offset
            // 按实时 nav inset 越界画到内容区外（BoxWithConstraints 不裁剪越界绘制）。
            // X1 改造后退化记档：舞台盒=全屏恒高（钳制见上），缺口归零、垫条恒越界画在
            // 屏幕外不可见；沉浸露出「舞台盒正下方标题条」的旧疾改由盒高钳制根治，垫条
            // 机制保留不大拆（清偿交 X8 终审裁量）；沉浸态 z 序在滚动列之下、被舞台盒
            // backdrop 覆盖，无视觉贡献
            val stageBottomBandHeightPx =
                (with(density) { maxHeight.roundToPx() } + liveStatusBarTopPx + liveNavBarBottomPx) -
                    stageBoxHeightPx - liveStatusBarTopPx
            val stageBottomBandHeight = with(density) { stageBottomBandHeightPx.toDp() }
            val liveNavBarBottomHeight = with(density) { liveNavBarBottomPx.toDp() }
            // 顶部背板色填充条（D1 单源）：[-comp, 0] 越界绘制区，随 stageBackdrop 切色
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(stageTopBandHeight)
                    .offset(y = -stageTopBandHeight)
                    .background(stageBackdrop),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(stageBottomBandHeight)
                    .offset(y = liveNavBarBottomHeight)
                    .background(stageBackdrop),
            )
            // 任务W W4 交互态锁滚动：verticalScroll 门控在 chromeEffective（=chromeVisible &&
            // !playerActive && !zoomImmersive，上方现成组合态，与沉浸系统栏/chrome 显隐/
            // 舞台底色同源；2026-09-13 缩放沉浸并入——放大态同沉浸锁滚，父层滚动不与双指
            // 缩放手势竞争）——海报态
            // （初始，chrome 显）保留可小幅下滑（「详情页时可以下滑一下」）；单击图片进沉浸
            // 全屏态、视频播放器活动期（播放/暂停/ENDED）一律锁死滚动。语义对齐旧版「媒体态
            // 不可滚、下滑只在最初详情页」：GUIDE_UI 口径媒体层恒 edge-to-edge 单层容器无
            // 滚动语义，新版下滑区是拍板⑧超规格件，故仅在海报态放行；沉浸态放任滚动还会
            // 让父层 touch slop 劫持 ZoomImageView 未放大态手势、整页带动舞台盒（Bug A 观感
            // 根因之一）。横屏全屏=独立 Dialog 窗口（VideoStage），本就摸不到背后滚动，无须
            // 处理。ScrollState 具名 remember：enabled=false 只停手势不清状态，退回海报态
            // 解锁后滚动位置原样保留（旧版「位置保留」语义）。设备实锤复测（2026-09-12）：
            // 沉浸态纵滑 scrollY 全程 0=锁滚生效；残余的整列 63px 位移系系统栏 inset 收缩
            // （contentH 2209→2272）经壳层 innerPadding 引发的布局平移，归 W2 域沉浸几何，
            // 修法涉壳层 padding（任务书停手点），记档待用户拍板，与本门控无关
            val detailScrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(detailScrollState, enabled = chromeEffective),
            ) {
                // 第一屏：媒体舞台（内容区整屏盒，底色随沉浸切换——K1 单源口径见上注；
                // 顶部越界带由上方背板填充条补足，见 D1 重做注）+ 渐变 chrome 浮层（chrome
                // 挂舞台盒内随第一屏滚动——只覆盖第一屏，下滑看内容不被遮）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(stageViewportHeight),
                ) {
                    DetailMediaStage(
                        asset = asset,
                        watched = state.videoWatched,
                        startPositionMs = state.videoStartPositionMs,
                        timelineTags = state.timelineTags,
                        backdrop = stageBackdrop,
                        modifier = Modifier.fillMaxSize(),
                        onSiblingNavigate = onSiblingNavigate,
                        // 放大态单击=无操作（2026-09-13 拍板口径）：放大期 chromeEffective 已被
                        // zoomImmersive 压 false，单击若照常翻转 chromeVisible 会在缩回后以
                        // 反转态恢复（奇偶漂移），故门控掉
                        onToggleChrome = { if (!zoomImmersive) chromeVisible = !chromeVisible },
                        // S2：退播放态顺带复位播放态 chrome 镜像（进播放态由桥接件
                        // startPlayback→showController(true) 上报置位，此处不预置）
                        onPlayerActiveChanged = {
                            playerActive = it
                            if (!it) playbackChromeVisible = false
                        },
                        // S2 播放态 chrome 上报（桥接件控制器显隐单点，见类 KDoc）
                        onPlaybackChromeChanged = { playbackChromeVisible = it },
                        // 图片态缩放沉浸上报（链路见类 KDoc；视频分支在 DetailMediaStage 不消费）
                        onZoomImmersiveChanged = { zoomImmersive = it },
                        onExitToChromeBrowse = { chromeVisible = true },
                        // 图片态解码失败覆盖层「返回」（RES #27）——与顶行返回同链（popBackStack）
                        onExitDetail = onBack,
                        // 舞台动作具名下发（3d 解冻拓扑保持）
                        onPlaybackStarted = viewModel::onPlaybackStarted,
                        onPositionChanged = viewModel::onPositionChanged,
                        onAddTimelineTag = viewModel::addTimelineTag,
                        onDeleteTimelineTag = viewModel::deleteTimelineTag,
                    )
                    // 顶部渐变 chrome（L171：返回/当前序号 n/N/信息钮）——任务U6 对齐旧版
                    // setChromeVisible 逐帧语义：条容器（底色/避让/内边距）恒组合不参与动画，
                    // 底色瞬时切换、仅条内内容 250ms 淡入淡出（档位与沿革见 DetailChromeBars
                    // [CHROME_FADE_MS]）；此前整条 AnimatedVisibility 一起 fadeIn/fadeOut，
                    // 背景跟着渐隐导致实测消失拖尾 325-400ms，已清偿
                    DetailTopChrome(
                        // S9 播放态顶栏退场（2026-09-19 拍板纠正批S2）：播放期「不是详情页
                        // ui」——顶栏只在非播放态按 chromeEffective 显隐，S2 的「播放态镜像
                        // 追加显隐」分支废止；playbackChromeVisible 镜像改驱动系统栏（见类
                        // KDoc 与 SystemBarsImmersiveEffect）
                        contentVisible = chromeEffective,
                        batchIndex = state.batchIndex,
                        batchSize = state.batchSize,
                        onBack = onBack,
                        onOpenInfo = { infoSheetVisible = true },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    // 底部渐变操作层（W3 四胶囊：点赞N/收藏/标签/作者——「整理」退役）——
                    // 拆分口径同上（U6：底色瞬切、仅四胶囊内容参与淡入淡出）
                    DetailBottomChrome(
                        contentVisible = chromeEffective,
                        asset = asset,
                        likePending = state.likePending,
                        favoritePending = state.favoritePending,
                        onToggleLike = viewModel::toggleLike,
                        onToggleFavorite = viewModel::toggleFavorite,
                        onOpenTagSheet = viewModel::openTagSheet,
                        onOpenAuthorSheet = { authorSheetVisible = true },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                DetailContentSections(
                    state = state,
                    onDismissError = viewModel::clearError,
                )
            }
        }

        // ---- 弹窗族（挂滚动主体之外；ModalBottomSheet 自带遮罩层） ----

        if (state.tagSheetOpen) {
            DetailTagManageSheet(
                pool = state.tagPool,
                selectedTagIds = state.selectedTagIds,
                savingTags = state.savingTags,
                onToggleTag = viewModel::toggleTagSelection,
                onUnbindTag = viewModel::unbindTag,
                onCreateTag = viewModel::createAndSelectTag,
                onSave = viewModel::saveTags,
                onDismiss = viewModel::dismissTagSheet,
            )
        }
        // 详细信息 BottomSheet（I7 → 任务X X3 旧版移植扩行：文件名/作品/出处/日期/大小/
        // 类型/尺寸/时长/目录/路径；无完成按钮，下滑手势/点外部关闭）
        if (infoSheetVisible) {
            DetailInfoSheet(asset = asset, onDismiss = { infoSheetVisible = false })
        }
        // 快速转跳弹窗（I7，L172：关联作者列表 → 作者集合页既有路由）。V3 起无入口
        // （「快速转跳」不进四胶囊，组件保留裁决），恒不挂载——见 jumpSheetVisible 注释
        if (jumpSheetVisible) {
            DetailJumpSheet(
                authors = asset.authors,
                onOpenAuthor = onOpenAuthor,
                onDismiss = { jumpSheetVisible = false },
            )
        }
        // 作者弹窗（W3）：作者胶囊入口，原作者卡内容移植（关注闭环 + 进入作者主页）
        if (authorSheetVisible) {
            DetailAuthorSheet(
                authors = asset.authors,
                followPending = state.followPendingAuthorId != null,
                onToggleFollow = viewModel::toggleFollow,
                onOpenAuthor = onOpenAuthor,
                onEdit = { onEditAsset(assetId) },
                onDismiss = { authorSheetVisible = false },
            )
        }
    }

    // 文件操作弹窗（任务G G1b）：成功 toast 挂系统层（android.widget.Toast，离开本页仍可见
    // ——Web 根 Toaster 的 Android 等价物；App 无 snackbar 基建，Toast 是平台标准件）。
    // 删除成功后 onBack() 离开已删资产（Web navigate(-1) 同收尾）；列表数据刷新依赖
    // 返回后的自然重取（Compose 列表无 TanStack 缓存，返回即重拉——VM 无须通知列表页）
    val fileOpsAsset = state.asset
    if (state.moveSheetOpen && fileOpsAsset != null) {
        // Toast 文案组合期取值（stringResource 随 Configuration 变化重组；LocalContext.getString
        // 不会，lint LocalContextGetResourceValueCall）。结果分支（moved/renamed）与目标目录属
        // 运行时数据取不到组合值，回调内只按模板 String.format 拼参——占位符均为 %s、实参均
        // 为 String，格式化输出与 getString(resId, args) 逐字节等价
        val toastBoth = stringResource(R.string.detail_move_toast_both)
        val toastMoved = stringResource(R.string.detail_move_toast_moved)
        val toastRenamed = stringResource(R.string.detail_move_toast_renamed)
        val rootDirLabel = stringResource(R.string.detail_move_root_dir)
        DetailMoveDialog(
            assetId = fileOpsAsset.id,
            currentDir = fileOpsAsset.directory.orEmpty(),
            currentName = fileOpsAsset.fileName,
            pending = state.fileOpsPending,
            errorMessage = state.moveError,
            onSubmit = { targetDir, newName ->
                viewModel.moveAsset(targetDir, newName) { moved, renamed ->
                    val dirLabel = targetDir.ifEmpty { rootDirLabel }
                    val message = when {
                        moved && renamed -> String.format(toastBoth, dirLabel, newName.orEmpty())
                        moved -> String.format(toastMoved, dirLabel)
                        else -> String.format(toastRenamed, newName.orEmpty())
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            },
            // 「删除归整理」（任务V V3 拍板）→ 任务W W3 推翻（台账 #44）：整理胶囊随 W3
            // 退役（四胶囊换作者），整理/删除/改名/移动入口从详情页消失（后果记档待拍板
            // 台账）——弹窗族与 VM 文件操作链按 DetailJumpSheet 同款保守口径保留（挂载点
            // 保留、无触发点恒不挂载），moveSheetOpen 无入口恒为关闭态
            onDeleteClick = {
                viewModel.dismissMoveSheet()
                viewModel.openDeleteConfirm()
            },
            onDismiss = viewModel::dismissMoveSheet,
        )
    }
    if (state.deleteConfirmOpen && fileOpsAsset != null) {
        // 同上：文案组合期格式化（fileName 组合期已知，stringResource 直传实参），
        // 回调内只弹系统 Toast（捕捉的组合期快照与原 getString 取值逐字节一致）
        val deleteToast = stringResource(R.string.detail_delete_toast, fileOpsAsset.fileName)
        DetailDeleteConfirmDialog(
            fileName = fileOpsAsset.fileName,
            pending = state.fileOpsPending,
            onConfirm = {
                viewModel.deleteAsset {
                    Toast.makeText(context, deleteToast, Toast.LENGTH_SHORT).show()
                    onBack()
                }
            },
            onDismiss = viewModel::dismissDeleteConfirm,
        )
    }
}

/**
 * 加载成功后的信息内容区（媒体层下方；任务X X2 下滑区裁剪终态，2026-09-12 用户拍板
 * 「删除下方的文件名字等等，只保留四个胶囊」）：标题/meta/标签行三段整段退役（组件随
 * 调用一并删除、孤儿清偿见 DetailSections.kt），本节仅剩错误横幅（互动失败唯一提示面，
 * 条件渲染；errorMessage null 时整节为空、高度归零）。沿革记档：任务Y Y1（2026-09-12
 * 真机反馈第 3 条「详情页残留上下拖动」根修）删除原「底部呼吸空间」Box——X2 裁剪后
 * 滚动列内容=舞台盒（全屏高）+此留白，内容高超出视口恰余 DETAIL_BOTTOM_SPACER 高的
 * 可拖余量；删除后常态内容高=舞台盒高=视口高，门控（chromeEffective）之外再无可滚量。
 * 更早沿革：W3 作者卡移植 DetailAuthorSheet、「接下来播放」推荐栏整段退役；V2 删
 * pager 行（i/N 由顶部 chrome 承担）；V3 互动行退役（点赞/收藏上移首屏四胶囊）。
 * 舞台动作不在本节（归媒体舞台浮层）。
 */
@Composable
private fun DetailContentSections(
    state: DetailUiState,
    onDismissError: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 错误横幅（互动失败不退场，home 的 ErrorBanner 模式可点关）：置内容区首位
        //（媒体舞台下方——沉浸第一屏不被横幅推挤）。Y1：末尾呼吸空间已删（真机残留
        // 拖动根因），常态本节空、滚动列内容止于舞台盒底
        state.errorMessage?.let { message ->
            DetailErrorBanner(message = message, onDismiss = onDismissError)
        }
    }
}

/**
 * 系统栏显隐/图标明暗编排（任务S S9 显隐三分支定稿 + S10 图标明暗收敛为单值消费，
 * 2026-09-19 用户拍板）。本效果不再持有裁决：显隐值=[systemBarsShouldShow] 纯函数在
 * DetailScreen 现算（zoomImmersive→隐；playerActive→随 playbackChromeVisible——B 站
 * 竖屏两态；其余→恒显示透明），图标明暗值=[statusBarDark] when 收敛（视频态恒浅/chrome
 * 显随系统明暗/图片态按采样亮度自适应，见 DetailScreen 内注释），两值均随调用参数进入，
 * 键控幂等重跑。
 *
 * 栏底透明由 MainActivity.enableEdgeToEdge 全局保证（androidx activity 1.10.1
 * EdgeToEdgeApi26-30：auto 档状态栏 scrim 恒 Color.TRANSPARENT；API 35+ 且
 * targetSdk 35+ 平台强制 edge-to-edge，弃用的 statusBarColor 参数被忽略），内容
 * edge-to-edge 延伸到栏后；显隐用 WindowInsetsController systemBars——S11（2026-09-19
 * 用户拍板「我要的是状态栏也瞬时，你搞反了」，纠正批S10 件3 方向）起 hide 改瞬时通道
 * [hideSystemBarsInstantly]（controlWindowInsetsAnimation 零时长，消灭平台 ~300ms hide
 * 动画拖尾=「状态栏消失比进度条慢」观感根源；show 保持普通 show()，显出带系统动画是
 * 正常观感），只控显隐不触发布局重排（decorFitsSystemWindows(false) 恒成立，不重复
 * 设置也不恢复 true，避免整窗重排）。键面=controller + 裁决值：两态切换幂等收敛，
 * 不依赖历史状态。
 *
 * 图标明暗应用单点（S10 件1 反色修复）：`isAppearanceLightStatusBars = statusBarDark`
 * **直接赋值不取反**——S10 前调用方按「图标暗→取反 apply」写（S7 记档「取反后 apply」），
 * 实为双重否定：平台语义「true=浅底配深图标」（isAppearanceLight=true 即系统画深图标）
 * 下，取反使净输出恒与判定相反——日间 chrome 显态（判定深图标）实际输出浅图标压白底、
 * 黑底态（判定浅图标）实际输出深图标压黑底，两态图标均与背景同色不可辨，即用户真机
 * 反馈「状态栏和背景一个色了」的根因（2026-09-19 批S10 修复记档）。导航栏
 * isAppearanceLightNavigationBars 同口径同步。JVM 单测：显隐三分支见
 * SystemBarIconAppearanceTest、采样阈值见 StatusBarLuminanceTest。
 *
 * onDispose（LEGACY_REQUIREMENTS E：controller 判空 + 生命周期清理）：离页兜底
 * show(systemBars)——S9 后 App 内 hide 路径恢复（播放沉浸/放大沉浸），本兜底重新成为
 * 有效防线。S2 handoff-ack 门控语义重估（S9 记档）：该门控只服务「滑切 push 详情→详情」
 * 的栏显隐竞态，而播放态无滑切路径（播放器手势接管触摸，海报态横滑只导航不起隐栏）且
 * 播放镜像为 remember 非持久态——滑切交接时新旧屏均在图片常态（显）分支 show 幂等汇合，
 * 门控不影响播放态显隐，机制原样保留。图标明暗回设随系统明暗（X7，rememberUpdatedState
 * 取最新值——DisposableEffect 不以 darkTheme 为键；回设是「浅底深图标」直写式
 * `!darkTheme`，语义本就正确，不随本次取反修复变化）。
 */
@Composable
private fun SystemBarsImmersiveEffect(
    systemBarsVisible: Boolean,
    statusBarDark: Boolean,
) {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    val darkTheme = isSystemInDarkTheme()
    val controller = remember(activity, view) {
        activity?.window?.let { window -> WindowCompat.getInsetsController(window, view) }
    }
    // 图标明暗应用单点：键控重跑（明暗值变化）与离页回设共用同一入参，两处各自手写一份
    // 必然渐行渐远。S10 修复：直接赋值（平台语义 true=浅底配深图标，见 KDoc 记档）
    fun applyIconAppearance(iconsDark: Boolean) {
        controller?.let { insets ->
            insets.isAppearanceLightStatusBars = iconsDark
            // X8 第二百一十八笔记档顺手清偿（任务S S2）：导航栏图标明暗与状态栏同口径
            insets.isAppearanceLightNavigationBars = iconsDark
        }
    }
    LaunchedEffect(controller, statusBarDark) {
        applyIconAppearance(statusBarDark)
    }
    // 显隐链（S9 恢复，S10 改消费现成单值，S11 hide 改瞬时通道）：键控幂等重跑；show 保持
    // 普通 show()（显出带系统动画是正常观感），hide 走 controlWindowInsetsAnimation 零时长
    // （消灭平台 ~300ms hide 动画拖尾=批S11 用户拍板「状态栏也瞬时」，compat 档位核查与
    // 兜底语义见 [hideSystemBarsInstantly] / InstantSystemBars.kt KDoc）
    LaunchedEffect(controller, systemBarsVisible) {
        val bars = WindowInsetsCompat.Type.systemBars()
        if (systemBarsVisible) {
            controller?.show(bars)
        } else {
            controller?.let { hideSystemBarsInstantly(it, bars) }
        }
    }
    val latestDarkTheme by rememberUpdatedState(darkTheme)
    DisposableEffect(controller) {
        onDispose {
            // 离页兜底 show（S9 后 hide 路径恢复，本兜底重新有效）+ 图标明暗回设随系统
            // 明暗（X7）。S2 handoff-ack 门控原样保留（S9 重估不影响播放态，记档见 KDoc）
            if (!SiblingSwipeImmersionRequest.consumeHandoffAndClear()) {
                controller?.show(WindowInsetsCompat.Type.systemBars())
            }
            controller?.isAppearanceLightStatusBars = !latestDarkTheme
            // X8 第二百一十八笔记档顺手清偿（任务S S2）：导航栏图标明暗与状态栏同口径
            // 回设——此前只回设状态栏，导航栏停留在沉浸期最后一次设定值
            controller?.isAppearanceLightNavigationBars = !latestDarkTheme
        }
    }
}

/**
 * chrome 显态系统栏图标暗色判定单源（任务S S7 抽取；S10 起只服务 chrome 有效显示分支——
 * 本页 [statusBarDark] when 的 chromeEffective 分支，采样/黑底分支见
 * [statusBarIconsDarkForLuminance]）：chrome 有效显示且系统日间 → 暗色图标（chrome 显态
 * 状态栏浮在主题背景上，浅色主题配深图标=相册语义）；系统夜间 → 浅色图标。
 * isAppearanceLightStatusBars 的平台语义是「true=浅底配深图标」——S10 修复前调用方误按
 * 「取反后 apply」接入，净输出与本判定恒相反（用户真机反馈图标与背景同色不可辨的根因，
 * 修复记档见 SystemBarsImmersiveEffect KDoc）；S10 起判定值直赋平台 API，本函数返回值
 * 语义=「图标应否深色」原样生效。
 */
internal fun statusBarIconsDark(chromeVisible: Boolean, isSystemDarkTheme: Boolean): Boolean =
    chromeVisible && !isSystemDarkTheme

/**
 * 系统栏显隐三分支裁决单源（任务S S9 抽取，2026-09-19 用户拍板 B 站竖屏两态语义；
 * JVM 单测锁定见 SystemBarIconAppearanceTest）：
 * - zoomImmersive（图片放大沉浸，批C 链路）→ 隐藏（S7「恒显」在本分支回收）；
 * - playerActive（视频播放态）→ 跟随 playbackChromeVisible（桥接件控制条显隐单点镜像，
 *   G9 自动隐藏/拖拽隐/长按隐/ENDED 强制显全路径同源）——控制条显=状态栏透明显示、
 *   控制条隐=状态栏隐藏（「再点击就是沉浸的视频浏览」）；
 * - 其余（图片常态）→ 恒显示（S7 恒透明显示口径保留，排版/浏览两态均显）。
 * 入参有意不含 chromeVisible：图片态系统栏不随 chrome 单击显隐（S7 定稿），只有播放
 * 镜像与放大沉浸能改变系统栏。
 */
internal fun systemBarsShouldShow(
    zoomImmersive: Boolean,
    playerActive: Boolean,
    playbackChromeVisible: Boolean,
): Boolean = when {
    zoomImmersive -> false
    playerActive -> playbackChromeVisible
    else -> true
}

// chrome 显隐动画档与逐帧语义单源已迁 DetailChromeBars.kt（任务U6：AnimatedVisibility
// 只包条内内容，条容器恒组合，常量随组件走）

/**
 * 底部留白常量（轻量档；与网格页 180dp 防遮挡档语义不同）。任务Y Y1 起页面级使用已删
 * （DetailContentSections 呼吸空间=真机残留上下拖动根因），仅弹窗族底部留白继续复用
 * （DetailTagSheet/DetailSheets，单源在本文件）。
 */
internal val DETAIL_BOTTOM_SPACER = 24.dp

/** 错误横幅纵向留白（沿用 3a 排版档；横幅在沉浸结构中挂内容区首位） */
private val ERROR_BANNER_VERTICAL_PADDING = 4.dp

/*
 * 加载/错误态顶行：复用 DetailSections.kt 既有 [DetailTopRow]（单源；I7 重写稿曾在本文件
 * 重复定义引发 overload 冲突，收口回单源）。
 */

/** 错误态（无内容可恢复：加载失败/路由缺参）：错误文案 + 重试 */
@Composable
private fun DetailErrorState(message: String?, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message ?: stringResource(R.string.detail_error_generic),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = QimengDimens.SpaceM)) {
                Text(text = stringResource(R.string.detail_retry))
            }
        }
    }
}

/** 错误横幅（home 的 ErrorBanner 同款：errorContainer 底 + 点击关闭；仅已加载内容态出现） */
@Composable
private fun DetailErrorBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = QimengDimens.ScreenPaddingHorizontal, vertical = ERROR_BANNER_VERTICAL_PADDING)
            .clickable(onClick = onDismiss),
    ) {
        Box(modifier = Modifier.padding(QimengDimens.SpaceM)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
