package media.qimeng.app.feature.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.IOException
import kotlinx.coroutines.launch
import media.qimeng.app.core.model.AppearanceMode
import media.qimeng.app.core.model.TabBarMaterial
import media.qimeng.app.core.ui.component.QimengSegPill
import media.qimeng.app.core.ui.glass.TabDockDefaults
import media.qimeng.app.core.ui.theme.QimengDimens

/** 我的页入口行文案（GUIDE_UI §我的页 + M4-2 既有入口 + M4-6 上传入口；
 *  F 批 2026-09-09：删除「作者管理」行；X5 批 2026-09-12：作者总览由内嵌卡改回
 *  收藏同款外部入口行（用户问题8），点击经壳层 onOpenAuthors 进全部作者页；
 *  U10-4：原「服务器地址」卡与「本机模式」行合并为「服务器」单入口行，子页承载；
 *  U10-6：原「上传文件」行升级为「数据管理」合并入口行（上传/注册媒体目录/库管理
 *  三件事进 feature:manage 的 hub 二级页），常量与副文案本文件单源；
 *  2026-09-28 上传归档文件夹功能：原「下载收件箱」入口行迁入数据管理 hub
 *  （用户拍板「下载箱移到数据管理中，不需要在外面单独一个显示」），常量随之退役 */
private const val ROW_AUTHORS = "作者总览"
private const val ROW_FAVORITE = "收藏"
private const val ROW_HISTORY = "浏览历史"
private const val ROW_DATA_MANAGE = "数据管理"

/** 主体色彩入口行（2026-10-03 液态感强化批：外观模式+底栏材质两张选择卡合并为单入口，
 *  点进子页承载——主列表减两卡、外观相关收敛一处） */
private const val ROW_THEME = "主体色彩"
private const val SUBTITLE_THEME = "外观模式与底栏材质"

/** 推荐偏好行文案：入口行与 PrefsBottomSheet 标题同串单源（Sheet 在 SettingsCards.kt，
 *  Kotlin 文件级 private 跨文件不可见，故本条 internal——模块外不可见） */
internal const val ROW_PREFS = "推荐偏好"
private const val ROW_SERVER = "服务器"

/**
 * 入口行副文案（I4 两行化，实录 mine.txt 逐字：收藏/浏览历史/主题色彩/推荐偏好；
 * 推荐偏好行当前预设名不再展示在行上——当前项高亮已在 BottomSheet 内，GUIDE_UI L255；
 * 原作者管理行副文案随行同批删除，F 批 2026-09-09。X5 批新增作者总览行副文案：
 * 入口行不预取数据，无「N 位作者」动态口径，用固定说明文字。U10-4 新增服务器行副文案：
 * 概括子页三件事——地址/本机模式/换址需重新登录。U10-6 新增数据管理行副文案：
 * 概括 hub 合并的三件事——上传/注册媒体目录/库管理）。
 */
private const val SUBTITLE_AUTHORS = "查看全部作者与作品"
private const val SUBTITLE_FAVORITE = "查看收藏的图片和视频"
private const val SUBTITLE_HISTORY = "查看最近打开过的图片和视频"
private const val SUBTITLE_PREFS = "调整首页推荐算法的权重偏好"
// 副文案单行节奏 ≤15 字（2026-09-15 用户反馈「服务器的介绍太长了导致没和其他的视觉对齐」
// ——原「查看服务器地址、本机模式；换址需重新登录」折行致行高破 72dp 节奏；换址提示细节
// 由子页承载）
private const val SUBTITLE_SERVER = "服务器地址、本机模式与换址说明"
private const val SUBTITLE_DATA_MANAGE = "上传文件、注册媒体目录、库管理"

private const val VERSION_UNKNOWN = "未知"

// ---------- 选择器卡（2026-10-03 悬浮玻璃坞批：外观模式/底栏材质共用） ----------

/** 12dp：选择器卡内文字区上下留白（对齐 EntryRow 行内垂直呼吸档） */
private val SelectorCardVerticalPadding = 12.dp

/** 8dp：标题/副标题块与选项胶囊行的间距（QimengDimens.SpaceM 同档，本文件独立命名防跨页牵连） */
private val SelectorCardTextToOptionsSpacing = 8.dp

/** 6dp：选项胶囊间距（QimengDimens.SpaceS 同档） */
private val SelectorCardOptionSpacing = 6.dp

// ---------- 我的页视觉复刻旧版尺寸（2026-09-13 用户反馈「我的界面的 ui 也要和旧版一致」；
// 逐段实录旧仓库运行时代码规格，口径见各常量注释；可复用档位一律引用 QimengDimens 既有
// token（同值多源共用一档），仅旧版独有尺寸在此登记；卡片私有尺寸随卡迁 SettingsCards.kt） ----------

/** 20dp：页面内容左右内边距（旧版我的页运行时内容 padding=20dp；区别于 all_files 页 16dp 档） */
private val ScreenContentPadding = 20.dp

/** 24dp：页标题「我的」与数量卡间距（旧版运行时标题下 24dp 处排卡） */
private val TitleToCardsSpacing = 24.dp

/** 16dp：首个入口行上距（旧版运行时行区首行 marginTop 16dp；行下距用 QimengDimens.SpaceL 12dp） */
private val FirstRowTopSpacing = 16.dp

/** 入口行卡投影（2026-10-03 用户拍板「回到旧版布局、只做立体感」：旧版逐行白卡结构
 *  原样保留，加 2dp 投影把卡从背景上抬起来） */
private val EntryRowShadowElevation = 2.dp

/**
 * 「我的」Tab（M4-6 完整版，单页滚动列表，GUIDE_UI §我的页结构 + I4 复刻清偿；
 * 2026-09-13 视觉复刻批：整页对齐旧版运行时——数量卡 96dp/20dp 圆角纯白 surface、
 * 入口行 QimengProfileRow 规格（72dp 高/18dp 水平 padding/16dp 圆角纯白卡/单块两行
 * 15sp 主文字色）、推荐偏好 Sheet 旧版规格（28dp 圆角/描边选中态/四预设恒渲染 +
 * 加载失败重试态）；大卡区块在 SettingsCards.kt，U10-4 起列表项按头/行/尾三组拆为
 * LazyListScope 扩展（纯代码移动，行序与规格逐字不变，主函数收回 100 行内））：
 * 标题 → 页首数量卡（I4：图片/视频两卡）→ 入口行族（服务器（U10-4 合并入口 →
 * ServerSettingsScreen 子页）→ 作者总览 → 收藏/浏览历史 → 数据管理（U10-6 合并入口 →
 * feature:manage hub 子页）→ 外观模式/底栏材质选择卡（2026-10-03）→
 * 推荐偏好（BottomSheet 四预设整行应用/当前项高亮））→
 * 版本信息（服务端版本，C6）→ 退出登录。
 * （浏览数据同步卡 2026-09-15 批迁往 feature:manage BackupScreen；缓存区「LRU 档位 +
 * 清空」2026-09-16 用户反馈迁往数据管理→缩略图缓存页，与缩略图生成进度合并展示。）
 */
@Composable
fun SettingsScreen(
    onOpenFavorite: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenAuthors: () -> Unit = {},
    // U10-6：原 onOpenUpload（上传文件行）退役——上传入口并入「数据管理」hub 二级页
    onOpenDataManage: () -> Unit = {},
    // 2026-09-28 归档文件夹批：onOpenInbox（下载收件箱行）退役——入口迁数据管理 hub，
    // 路由 Routes.INBOX 不变，接线改在 QimengNavHost 的 DataManageScreen 组合处
    // U10-4：服务器子页入口（地址修改/本机模式/换址说明合并单入口）
    onOpenServerDetail: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 2026-10-03 悬浮玻璃坞批：外观/材质状态（外观偏好端口直读，与壳层 Theme/坞同源）
    val appearanceMode by viewModel.appearanceMode.collectAsStateWithLifecycle()
    val tabBarMaterial by viewModel.tabBarMaterial.collectAsStateWithLifecycle()
    // 主体色彩子页（2026-10-03 液态感强化批）：外观模式+底栏材质两张选择卡合并为单入口行，
    // 点进行内子页承载；rememberSaveable 跨旋转/进程重建，BackHandler 承接系统返回
    var showThemePage by rememberSaveable { mutableStateOf(false) }
    if (showThemePage) {
        BackHandler(onBack = { showThemePage = false })
        ThemeColorPage(
            appearanceMode = appearanceMode,
            onAppearanceModeSelect = viewModel::setAppearanceMode,
            tabBarMaterial = tabBarMaterial,
            onTabMaterialSelect = viewModel::setTabBarMaterial,
        )
    } else {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            // 旧版页面底色 = qm_bg（background 槽，浅 #FAFAFA / 夜 #1A1A1A）；壳层已涂底，
            // 显式声明防宿主容器换底后页面漏色
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = ScreenContentPadding),
        // 2026-10-03 悬浮玻璃坞批：底栏改悬浮层后内容从坞身后滚过，滚动区底部让位到
        // 坞体上方（core:ui 单源常量，含导航栏 inset）
        contentPadding = PaddingValues(bottom = TabDockDefaults.bottomClearance()),
    ) {
        settingsHeaderItems(state = state, viewModel = viewModel)
        settingsEntryRowItems(
            onOpenServerDetail = onOpenServerDetail,
            onOpenAuthors = onOpenAuthors,
            onOpenFavorite = onOpenFavorite,
            onOpenHistory = onOpenHistory,
            onOpenDataManage = onOpenDataManage,
            onOpenTheme = { showThemePage = true },
            onOpenPrefs = viewModel::openPrefsSheet,
        )
        settingsFooterItems(state = state, viewModel = viewModel)
    }
    }

    // Sheet 开关只看 prefsSheetOpen——预设四行本身不依赖网络，prefsValues 加载失败
    // 也在 Sheet 内给「加载失败/重试」态，绝不因数据未就绪而点行无响应
    if (state.prefsSheetOpen) {
        PrefsBottomSheet(
            appliedPreset = state.appliedPreset,
            applying = state.prefsApplying,
            loading = state.prefsLoading,
            loadFailed = state.prefsLoadFailed,
            onRetryLoad = viewModel::retryLoadPrefs,
            onApply = viewModel::applyPreset,
            onDismiss = viewModel::closePrefsSheet,
        )
    }
}

/**
 * 主体色彩子页（2026-10-03 液态感强化批）：原主列表的外观模式三选 + 底栏材质四选两张
 * 选择卡迁入本页承载——主列表以单入口行「主体色彩」点进，系统返回/无障碍回退都回主列表。
 * 布局与主列表同语言（20dp 横 padding/28sp Bold 标题/底部悬浮坞让位）。
 */
@Composable
private fun ThemeColorPage(
    appearanceMode: AppearanceMode,
    onAppearanceModeSelect: (AppearanceMode) -> Unit,
    tabBarMaterial: TabBarMaterial,
    onTabMaterialSelect: (TabBarMaterial) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = ScreenContentPadding)
            .verticalScroll(rememberScrollState())
            // 底部让位悬浮坞（内容从坞身后滚过）
            .padding(bottom = TabDockDefaults.bottomClearance()),
    ) {
        Text(
            text = ROW_THEME,
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(top = TitleToCardsSpacing, bottom = FirstRowTopSpacing),
        )
        SettingsSelectorCard(
            title = stringResource(R.string.settings_appearance_title),
            subtitle = stringResource(R.string.settings_appearance_subtitle),
            options = listOf(
                stringResource(R.string.settings_appearance_system),
                stringResource(R.string.settings_appearance_light),
                stringResource(R.string.settings_appearance_dark),
            ),
            selectedIndex = AppearanceMode.entries.indexOf(appearanceMode).coerceAtLeast(0),
            onSelect = { index -> onAppearanceModeSelect(AppearanceMode.entries[index]) },
            modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
        )
        SettingsSelectorCard(
            title = stringResource(R.string.settings_tab_material_title),
            subtitle = stringResource(R.string.settings_tab_material_subtitle),
            options = listOf(
                stringResource(R.string.settings_tab_material_liquid),
                stringResource(R.string.settings_tab_material_frosted),
                stringResource(R.string.settings_tab_material_solid),
                stringResource(R.string.settings_tab_material_classic),
            ),
            selectedIndex = TabBarMaterial.entries.indexOf(tabBarMaterial).coerceAtLeast(0),
            onSelect = { index -> onTabMaterialSelect(TabBarMaterial.entries[index]) },
            modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
        )
    }
}

/**
 * 列表头三组（U10-4 拆分：自 SettingsScreen 逐字迁移）：页标题 → 写失败横幅（P2-3）→
 * 页首数量卡（I4：旧版页首即数量卡，实录 mine.txt 两卡「图片 N」「视频 N」；
 * 视觉复刻批：96dp 高/20dp 圆角/纯白 surface 底/两行「图片\nN」16sp Bold 居中，
 * 数据源 GET /stats/overview imageCount/videoCount，失败显「—」）。
 */
private fun LazyListScope.settingsHeaderItems(state: MineUiState, viewModel: SettingsViewModel) {
    // Z2 批（2026-09-12 我的页字体色彩对齐旧版）：页标题 28sp Bold（旧 profile.xml）
    item {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold),
            // 旧版标题下 24dp 处排数量卡
            modifier = Modifier.padding(bottom = TitleToCardsSpacing),
        )
    }

    // 写操作失败反馈（P2-3）：横幅常驻直至点按消除，避免静默失败
    state.writeError?.let { message ->
        item {
            WriteErrorBanner(
                message = message,
                onDismiss = viewModel::dismissWriteError,
                modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
            )
        }
    }

    item {
        Row(horizontalArrangement = Arrangement.spacedBy(QimengDimens.SpaceM)) {
            CountCard(
                title = COUNT_CARD_IMAGE,
                count = state.imageCount,
                modifier = Modifier.weight(1f),
            )
            CountCard(
                title = COUNT_CARD_VIDEO,
                count = state.videoCount,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 入口行族（U10-4 拆分：自 SettingsScreen 逐字迁移；F 批 2026-09-09 起行序：
 * 服务器 → 作者总览 → 收藏/浏览历史 → 数据管理 → 外观模式/底栏材质（2026-10-03 悬浮
 * 玻璃坞批：原「主题色彩」不可点占位行升级为外观模式三选 + 新增底栏材质四选）→ 推荐偏好；
 * 原「作者管理」行按用户拍板删除，作者管理页由作者总览行承担入口；
 * U10-6：原「上传文件」行原位升级为「数据管理」合并入口行）。
 */
private fun LazyListScope.settingsEntryRowItems(
    onOpenServerDetail: () -> Unit,
    onOpenAuthors: () -> Unit,
    onOpenFavorite: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDataManage: () -> Unit,
    onOpenTheme: () -> Unit,
    onOpenPrefs: () -> Unit,
) {
    // 2026-10-03 用户拍板：我的页回到旧版逐行白卡布局（v2 分组列表/v3 图标分组两方案
    // 均撤回），仅做立体感——行卡加 2dp 投影（EntryRow 内统一处理）
    item {
        EntryRow(
            label = ROW_SERVER,
            subtitle = SUBTITLE_SERVER,
            onClick = onOpenServerDetail,
            // 行区首行上距 16dp（旧版运行时规格，原资料卡/本机模式行同位）
            modifier = Modifier.padding(top = FirstRowTopSpacing, bottom = QimengDimens.SpaceL),
        )
    }
    item {
        EntryRow(
            label = ROW_AUTHORS,
            subtitle = SUBTITLE_AUTHORS,
            onClick = onOpenAuthors,
            modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
        )
    }
    item {
        EntryRow(
            label = ROW_FAVORITE,
            subtitle = SUBTITLE_FAVORITE,
            onClick = onOpenFavorite,
            modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
        )
    }
    item {
        EntryRow(
            label = ROW_HISTORY,
            subtitle = SUBTITLE_HISTORY,
            onClick = onOpenHistory,
            modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
        )
    }
    item {
        EntryRow(
            label = ROW_DATA_MANAGE,
            subtitle = SUBTITLE_DATA_MANAGE,
            onClick = onOpenDataManage,
            modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
        )
    }
    item {
        EntryRow(
            label = ROW_THEME,
            subtitle = SUBTITLE_THEME,
            onClick = onOpenTheme,
            modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
        )
    }
    item {
        EntryRow(
            label = ROW_PREFS,
            subtitle = SUBTITLE_PREFS,
            onClick = onOpenPrefs,
            modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
        )
    }
}

/**
 * 列表尾两组（U10-4 拆分：自 SettingsScreen 逐字迁移）：版本信息（C6）→ 退出登录。
 * （原首组「浏览数据同步」卡 2026-09-15 批迁往数据管理→备份导入导出页，用户拍板
 * 「外部的浏览数据移植到数据管理中合并到导入备份那个」；原「缓存区（C5）」组——
 * SectionTitle + 缩略图上限档位卡——2026-09-16 用户反馈迁往数据管理→缩略图缓存页，
 * 与缩略图生成进度合并为单页，QuotaCard 随迁 feature:manage。）
 */
private fun LazyListScope.settingsFooterItems(state: MineUiState, viewModel: SettingsViewModel) {
    // 版本信息（C6：服务端版本）
    item {
        EntryRow(
            label = "版本信息",
            detail = "服务端 ${state.serverVersion ?: VERSION_UNKNOWN}",
            onClick = null,
            modifier = Modifier.padding(bottom = QimengDimens.SpaceL),
        )
    }

    item {
        Button(
            onClick = viewModel::logout,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.settings_logout))
        }
    }
}

/**
 * 入口行（视觉复刻批对齐旧版 QimengProfileRow 运行时规格：高 72dp、水平 padding 18dp
 * 垂直居中、背景=16dp 圆角纯白 surface 卡、无图标无分隔线；行文字=单块两行文本
 * 「标题\n副标题」15sp 主文字色——旧版副标题与标题同字号同色；subtitle=null 保持单行；
 * detail=右侧灰字（版本行等无副文案的旧形态行保留用）；
 * onClick=null 为纯展示行（版本信息行等；原「主题色彩」占位行 2026-10-03 升级为选择卡）。
 * 高度用 min 而非定值：长副文案行（如服务器行）定值 72dp 会截断三行以上文本，
 * 其余短文案行渲染高度与旧版 72dp 完全一致。
 */
@Composable
private fun EntryRow(
    label: String,
    subtitle: String? = null,
    detail: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)?,
) {
    // 2026-10-03 用户拍板回旧版逐行白卡布局：结构/几何逐字保留（16dp 圆角/72dp 节奏/
    // 单块两行文本），仅加 2dp 投影做立体感（卡与背景的分离由光影承担）
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(QimengDimens.CardCornerRadius),
        shadowElevation = EntryRowShadowElevation,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = QimengDimens.ProfileRowHeight)
                .padding(horizontal = QimengDimens.ProfileRowHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 单块两行文本（旧版单个 TextView：标题+副标题同字号同色，非两块 Text）
            Text(
                text = if (subtitle != null) "$label\n$subtitle" else label,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 选择器卡（2026-10-03 悬浮玻璃坞批）：标题/副标题（单块两行文本，对齐 [EntryRow] 语言）
 * + 一行 [QimengSegPill] 分段选项。外观模式三选/底栏材质四选共用此形态。
 * 视觉 token 只走 MaterialTheme 与 QimengDimens（红线：feature 层禁 import glass 包颜色）。
 *
 * @param options 选项文案（顺序 = 枚举 entries 序）
 * @param selectedIndex 当前选中下标（越界钳到首项）
 * @param onSelect 点选回调（传下标）
 */
@Composable
private fun SettingsSelectorCard(
    title: String,
    subtitle: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(QimengDimens.CardCornerRadius),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = QimengDimens.ProfileRowHorizontalPadding,
                vertical = SelectorCardVerticalPadding,
            ),
        ) {
            Text(
                text = if (subtitle.isNotEmpty()) "$title\n$subtitle" else title,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(SelectorCardTextToOptionsSpacing))
            Row(horizontalArrangement = Arrangement.spacedBy(SelectorCardOptionSpacing)) {
                options.forEachIndexed { index, option ->
                    QimengSegPill(
                        text = option,
                        selected = index == selectedIndex.coerceAtLeast(0),
                        onClick = { onSelect(index) },
                    )
                }
            }
        }
    }
}
