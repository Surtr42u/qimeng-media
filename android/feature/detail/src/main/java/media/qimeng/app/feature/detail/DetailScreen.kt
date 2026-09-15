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
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.size.Size
import media.qimeng.app.core.ui.theme.QimengDimens

/**
 * 详情页（任务I I7 沉浸复刻改版；任务K K1 黑底污染清偿改舞台底色口径）：按 GUIDE_UI
 * §详情页 L158-160 沉浸 4 层组织回归——第一屏媒体舞台 edge-to-edge 全出血（整屏高，底色
 * 随沉浸切换：chrome 显=主题底/沉浸或播放中=黑，见 [stageBackdropColor]；图片态
 * ZoomImageView 链 / 视频态 BiliPlayerView 链）+ 上下渐变 chrome 浮层（顶：返回/n/N/信息；
 * 底：点赞N/收藏/标签/作者四胶囊——任务W W3「整理」退役换作者，[DetailChromeBars]）+
 * 单击显隐（图片态单击舞台切 chrome+系统栏，L271-276）；媒体层下方仅剩错误横幅
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
 * 图片态缩放沉浸（2026-09-13 用户实测反馈驱动，非旧版对齐——旧版单击无条件切 chrome）：
 * 图片放大跨过 1.05x（ZoomImageView.emitZoomImmersive 上报）即并入 chromeEffective——
 * 系统栏/上下渐变 chrome 隐藏、舞台底转黑、滚动锁死（同沉浸口径，消除「放大时上下白色
 * 渐变压在图上」观感）；缩回落回收束点恢复。放大态单击舞台无操作（防 chrome 显隐奇偶
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
 */
@Composable
fun DetailScreen(
    assetId: String,
    onBack: () -> Unit,
    onOpenAsset: (assetId: String) -> Unit,
    onOpenAuthor: (authorId: String, displayName: String) -> Unit = { _, _ -> },
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
    // 图片态缩放沉浸镜像（ZoomImageView 上报，2026-09-13 用户反馈「放大时上下白色渐变
    // 压在图上观感不适」）：跨过 1.05x 即时 true、回落收束点 false（非对称滞回在搬运件）。
    // remember 而非 rememberSaveable：放大态属 ZoomImageView 实例态，进程重建后 View 重建、
    // 图片回基态（resetZoom 上报 false）——saveable 会造成「UI 沉浸而图未放大」的假沉浸，
    // 故不持久化（chromeVisible 仍 saveable：chrome 开关是用户显式选择，语义不同）
    var zoomImmersive by remember { mutableStateOf(false) }
    val chromeEffective = chromeVisible && !playerActive && !zoomImmersive
    // 信息/快速转跳/作者 BottomSheet 开关（纯 UI 弹层无数据请求，页面局部状态；进程重建后
    // 关闭态恢复——与 chromeVisible 同 rememberSaveable 语义）。任务V V3：「快速转跳」
    // 不进首屏四胶囊（主代理保守裁决待用户确认），入口随旧图标行消失——jumpSheetVisible
    // 与 DetailJumpSheet 挂载保留（组件保留裁决），当前无触发点、恒为关闭态。任务W W3：
    // 作者胶囊补 authorSheetVisible 入口（点 DetailAuthorSheet）。
    var infoSheetVisible by rememberSaveable { mutableStateOf(false) }
    var jumpSheetVisible by rememberSaveable { mutableStateOf(false) }
    var authorSheetVisible by rememberSaveable { mutableStateOf(false) }
    // I7 沉浸：chrome 显隐驱动系统栏（chrome 隐藏=黑底沉浸+系统栏隐藏，L273-274）
    SystemBarsImmersiveEffect(chromeVisible = chromeEffective)

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
            // 加载/错误态保留朴素顶行（沉浸 chrome 随舞台挂载，加载期无舞台可浮）
            DetailTopRow(onBack = onBack)
            DetailLoadingState()
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
                        onPlayerActiveChanged = { playerActive = it },
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
 * 系统栏沉浸效果（3b，旧版语义；I7 起 chrome 源 = chromeEffective）：chrome 可见=显示
 * statusBars+navigationBars，隐藏=隐藏（下滑临时呼出=BEHAVIOR_DEFAULT 平台默认）。
 * **只控显隐不触发布局重排**——图片不因系统栏切换重新居中；decorFitsSystemWindows(false)
 * 恒成立由 MainActivity.enableEdgeToEdge 全局保证（等价于
 * WindowCompat.setDecorFitsSystemWindows(window,false)，此处不重复设置、也不在离开时恢复
 * true，避免整窗重排）。退出沉浸 = 再次单击（LaunchedEffect 翻转）或返回/兄弟 push 换屏
 * （onDispose 恢复系统栏；LEGACY_REQUIREMENTS E：controller 判空 + 生命周期清理）。
 *
 * 任务X X7（2026-09-12 状态栏发白修复②）：状态栏图标明暗随沉浸态同步——enableEdgeToEdge
 * 仅 onCreate 按当时系统明暗设一次图标，详情沉浸态（黑底）系统自呼出状态栏时图标仍是
 * 暗色 → 暗底暗图标不可辨/发白观感。修法：沉浸（chromeVisible=false，舞台底恒纯黑）
 * 图标一律浅色（isAppearanceLightStatusBars=false）；chrome 可见态舞台底=主题背景，
 * 图标按系统明暗回设（日=暗图标/夜=浅图标，与 enableEdgeToEdge 的 auto 语义一致）。
 * 仅动图标明暗，不碰窗口透明背景（edge-to-edge 语义不变）。键面=controller +
 * chromeVisible + darkTheme：日夜切换（uiMode 原地换肤，不 recreate）时 effect 重跑，
 * show/hide 分支按 chromeVisible 幂等，不会误显沉浸期系统栏。onDispose 恢复走
 * rememberUpdatedState 取最新明暗（DisposableEffect 不以 darkTheme 为键——重启会误
 * show 系统栏破坏沉浸态）。
 *
 * 任务S S2（2026-09-13）：onDispose 的 show() 增加 handoff-ack 门控（W7 第二百一十笔
 * 留档的白条根修，见 SiblingSwipeImmersionRequest）——滑切 push 详情→详情时跳过 show()
 * 避免栏闪现；导航栏图标明暗与状态栏同口径设定/回设（X8 第二百一十八笔记档清偿）。
 */
@Composable
private fun SystemBarsImmersiveEffect(chromeVisible: Boolean) {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    val darkTheme = isSystemInDarkTheme()
    val controller = remember(activity, view) {
        activity?.window?.let { window -> WindowCompat.getInsetsController(window, view) }
    }
    // 显隐应用单点（U11 批次B 抽取）：键控重跑与 ON_RESUME 重挂共用同一语义，
    // 两处各自手写一份必然渐行渐远（图标明暗/BEHAVIOR 漏一处就是新 bug）。
    fun applyBars(visible: Boolean) {
        controller?.let { insets ->
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            // 任务X X7 图标明暗（见上 KDoc）：沉浸=浅色图标（黑底）；chrome 显=随系统明暗
            insets.isAppearanceLightStatusBars = visible && !darkTheme
            // X8 第二百一十八笔记档顺手清偿（任务S S2）：导航栏图标明暗与状态栏同口径
            // 同步——此前只设状态栏，沉浸切换时导航栏图标明暗停留在旧值
            insets.isAppearanceLightNavigationBars = visible && !darkTheme
            val bars = WindowInsetsCompat.Type.systemBars()
            if (visible) insets.show(bars) else insets.hide(bars)
        }
    }
    LaunchedEffect(controller, chromeVisible, darkTheme) {
        applyBars(chromeVisible)
    }
    val latestDarkTheme by rememberUpdatedState(darkTheme)
    // U11 批次B：chromeVisible 的最新值供 ON_RESUME 重挂读取（observer 注册期不重组）
    val latestChromeVisible by rememberUpdatedState(chromeVisible)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(controller, lifecycleOwner) {
        // U11 批次B（沉浸静置自动退残余候选加固）：ON_RESUME 重申显隐——息屏/解锁
        // 或 OEM ROM 在后台自行恢复系统栏后，本效果原有键控重跑不触发（键未变），
        // 沉浸态观感被破坏且无人纠正（真机症状候选C4；AOSP 实测无此路径=EXP2 零
        // 复现，此为对 ROM 行为的幂等防御：按最新 chromeVisible 重申，不改任何
        // 正常路径行为——ON_RESUME 时键控分支本来就是这个值）。
        val resumeObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                applyBars(latestChromeVisible)
            }
        }
        lifecycleOwner.lifecycle.addObserver(resumeObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(resumeObserver)
            // 离开详情页（返回/推入下一资产）恢复系统栏，不留沉浸态给其他页面；
            // 图标明暗同步回系统明暗（X7：此前只恢复显隐，明暗停留在最后一次设定值）
            // S2 handoff-ack（W7 第二百一十笔留档 → 本批落地）：滑切 push 详情→详情时，
            // 本 onDispose（applyChanges 阶段同步执行）先于新屏 LaunchedEffect 的
            // hide()（协程后调度），此处 show() 会闪现 1-2 帧亮色白条；新屏组合期
            // consume() 已置「交接在途」标记且次序先于本 onDispose，命中则跳过 show()
            // 让栏保持隐藏，与新屏 hide() 幂等汇合。读后即清：非滑切离场（详情→作者页 /
            // pop 回列表）标记必为 false，照常恢复 show()，列表页不丢栏
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

/** 加载态：居中「加载中…」（Web 首屏 grid-empty 同文案） */
@Composable
private fun DetailLoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.detail_loading),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
