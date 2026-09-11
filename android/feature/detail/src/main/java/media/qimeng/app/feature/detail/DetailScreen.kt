package media.qimeng.app.feature.detail

import android.widget.Toast
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
 * 单击显隐（图片态单击舞台切 chrome+系统栏，L271-276）；媒体层下方保留信息内容区
 * （标题/meta/只读标签三段——任务W W3 下滑区裁剪终态：作者卡内容移植进 DetailAuthorSheet、
 * 「接下来播放」推荐栏整段退役；chrome 挂在舞台盒内随第一屏滚动，只覆盖第一屏）。
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
 * @param assetId 路由参数（ViewModel 经 SavedStateHandle 同键读取；此处显式保留供预览/测试）
 * @param onBack 返回（壳层 popBackStack）
 * @param onOpenAsset 跳资产（壳层导航 push 叠栈）：兄弟资产滑动换件走此回调（批次清单
 *   就是当前清单，不换批）——push 叠栈 = 浏览历史语义
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
    // 沉浸模式 chrome 显隐（媒体单击切换，默认可见；每屏独立——兄弟 push 是新路由实例）
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    // 视频播放器活动态镜像（VideoStage 上报）：活动期 chrome 让位播放器控制器
    var playerActive by remember { mutableStateOf(false) }
    val chromeEffective = chromeVisible && !playerActive
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
                .apply { if (!target.isVideo) size(Size.ORIGINAL) }
                .build()
            disposables[target.assetId] = SingletonImageLoader.get(context).enqueue(request)
        }
        onDispose { disposables.values.forEach { it.dispose() } }
    }

    // 兄弟资产切换回调单源（拍板③：目标解析失败（越界/无批次/缺参）静默不动；批次清单
    // 就是当前清单，不换批；push 叠栈 = 浏览历史语义）
    val onSiblingNavigate: (Int) -> Unit = { delta ->
        viewModel.moveBy(delta)?.let { targetId -> onOpenAsset(targetId) }
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
        // DetailMediaStage.backdrop 逐层下发，子层禁止自带底色
        val stageBackdrop = stageBackdropColor(
            chromeVisible = chromeVisible,
            playerActive = playerActive,
            themeBackground = MaterialTheme.colorScheme.background,
        )
        // 第一屏舞台高度 = 壳层内容区可视高度（BoxWithConstraints.maxHeight：状态栏/导航栏
        // insets 已由壳层 Scaffold 扣除）。I7 初稿用 LocalConfiguration.screenHeightDp（整屏），
        // 但舞台盒顶从壳层 inset 线起算 → 盒底越过视口下缘，底部 chrome（BottomCenter 对齐）
        // 落到屏幕外不可见（模拟器走查实证：a11y 树无底部四钮）——改为按可视高度取值，
        // chrome 随第一屏滚动的设计不变
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
            val stageViewportHeight = with(density) { stageViewportHeightPx.toDp() }
            // 底部背板色填充条（W2，对称 D1 顶条）：沉浸冻结后舞台盒固定在可见态高度，
            // 栏隐藏期内容区底部多出的条带（[盒底, 屏底]）由本条以 stageBackdrop 补足
            //（沉浸=纯黑延伸到 y=2400；chrome 显态=主题底与壳底同色无感）。高度=屏高-
            // 盒高-实时 status inset（盒底随内容顶移动，剩余缺口全部落在底部）；offset
            // 按实时 nav inset 越界画到内容区外（BoxWithConstraints 不裁剪越界绘制）
            val stageBottomBandHeightPx =
                (with(density) { maxHeight.roundToPx() } + liveStatusBarTopPx + liveNavBarBottomPx) -
                    stageViewportHeightPx - liveStatusBarTopPx
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
            // !playerActive，上方 L~100 现成组合态，与沉浸系统栏/chrome 显隐同源）——海报态
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
                        onToggleChrome = { chromeVisible = !chromeVisible },
                        onPlayerActiveChanged = { playerActive = it },
                        onExitToChromeBrowse = { chromeVisible = true },
                        // 图片态解码失败覆盖层「返回」（RES #27）——与顶行返回同链（popBackStack）
                        onExitDetail = onBack,
                        // 舞台动作具名下发（3d 解冻拓扑保持）
                        onPlaybackStarted = viewModel::onPlaybackStarted,
                        onPositionChanged = viewModel::onPositionChanged,
                        onAddTimelineTag = viewModel::addTimelineTag,
                        onDeleteTimelineTag = viewModel::deleteTimelineTag,
                    )
                    // 顶部渐变 chrome（L171：返回/当前序号 n/N/信息钮）——alpha 显隐（L175）。
                    // 显式全限定：外层 Column 的 ColumnScope.AnimatedVisibility 扩展在此上下文
                    // （BoxScope 内）不可隐式调用，须取顶层函数
                    androidx.compose.animation.AnimatedVisibility(
                        visible = chromeEffective,
                        modifier = Modifier.align(Alignment.TopCenter),
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        DetailTopChrome(
                            batchIndex = state.batchIndex,
                            batchSize = state.batchSize,
                            onBack = onBack,
                            onOpenInfo = { infoSheetVisible = true },
                        )
                    }
                    // 底部渐变操作层（W3 四胶囊：点赞N/收藏/标签/作者——「整理」退役）——
                    // 全限定同上
                    androidx.compose.animation.AnimatedVisibility(
                        visible = chromeEffective,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        DetailBottomChrome(
                            asset = asset,
                            likePending = state.likePending,
                            favoritePending = state.favoritePending,
                            onToggleLike = viewModel::toggleLike,
                            onToggleFavorite = viewModel::toggleFavorite,
                            onOpenTagSheet = viewModel::openTagSheet,
                            onOpenAuthorSheet = { authorSheetVisible = true },
                        )
                    }
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
        // 信息弹窗（I7，L169/L171：文件名/出处/尺寸/时长；无完成按钮）
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
 * 加载成功后的信息内容区（媒体层下方，下滑查看；拍板⑧超规格件保留融入沉浸结构）：
 * 标题 → meta 行 → 标签行（只读）+ 底部呼吸空间——任务W W3 下滑区裁剪终态（三段）：
 * 作者卡内容移植 DetailAuthorSheet、「接下来播放」推荐栏整段退役。舞台动作不在本节（归媒体
 * 舞台浮层）。V2 删 pager 行（用户拍板：导航只留横滑，i/N 由顶部 chrome 承担）。
 * 任务V V3 重排：互动行（DetailInteractionRow）整行退役——点赞/收藏上移首屏四胶囊
 * （DetailBottomChrome）、标签行只读化（编辑走首屏「标签」胶囊）。
 */
@Composable
private fun DetailContentSections(
    state: DetailUiState,
    onDismissError: () -> Unit,
) {
    val asset = requireNotNull(state.asset)
    Column(modifier = Modifier.fillMaxSize()) {
        // 错误横幅（互动失败不退场，home 的 ErrorBanner 模式可点关）：置内容区首位
        //（媒体舞台下方——沉浸第一屏不被横幅推挤）
        state.errorMessage?.let { message ->
            DetailErrorBanner(message = message, onDismiss = onDismissError)
        }
        DetailTitle(title = asset.title)
        DetailMetaRow(asset = asset)
        DetailTagRow(tags = asset.tags)
        // 底部呼吸空间（避免末节贴系统导航栏；轻量档）——I7 起恒显（chrome 不再占用本节）
        Box(modifier = Modifier.padding(bottom = DETAIL_BOTTOM_SPACER))
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
 */
@Composable
private fun SystemBarsImmersiveEffect(chromeVisible: Boolean) {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    val controller = remember(activity, view) {
        activity?.window?.let { window -> WindowCompat.getInsetsController(window, view) }
    }
    LaunchedEffect(controller, chromeVisible) {
        controller?.let { insets ->
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            val bars = WindowInsetsCompat.Type.systemBars()
            if (chromeVisible) insets.show(bars) else insets.hide(bars)
        }
    }
    DisposableEffect(controller) {
        onDispose {
            // 离开详情页（返回/推入下一资产）恢复系统栏，不留沉浸态给其他页面
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** 页面级底部预留（轻量档；与网格页 180dp 防遮挡档语义不同。标签弹窗底部留白同档复用） */
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
