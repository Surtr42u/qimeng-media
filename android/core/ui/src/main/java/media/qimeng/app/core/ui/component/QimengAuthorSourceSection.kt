package media.qimeng.app.core.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import media.qimeng.app.core.model.AuthorSuggestion

/**
 * 作者联想段（无状态；2026-09-25 上传挂靠退役批自 UploadScreen 抽出，上传页与资产
 * 编辑页共用）：未确定 = 胶囊输入框 + 浮层联想菜单；已确定 = 选中胶囊（点按清除）。
 * 状态与业务规则（防抖/互斥/归并）全在调用方 ViewModel，本组件零逻辑（ADR-0008 铁律 7）。
 *
 * 联想弹层（2026-09-29 用户拍板「搜索那种」）：输入非空时建议以浮层展示在输入框
 * 正下方——原内联 Card 会把整张长表单往下顶（每敲一字全表单重排，用户实测「提取
 * 太卡」且观感是个胶囊框），浮层零布局位移、与搜索补全同范式。同日第二笔：浮层载体
 * 从 material3 DropdownMenu 换裸 Popup + 自锚定位（alpha28 的 DropdownMenu 在
 * verticalScroll 内锚定 y 丢失，真机取证浮层弹到窗口顶部，详见实现处注释）。
 * 同日第三笔（视觉定稿）：夸克浏览器式「点击扩大一体化」——浮层自输入框中线覆盖
 * 下半 + 两侧弧形补块拼成单一背景色大圆角容器，详见 [AuthorSuggestionMenu] 注释。
 *
 * @param committedName 已确定作者显示名（点选既有 or 待新建）；null = 未确定（渲染输入态）
 * @param committedIsExisting true = 点选既有作者（副文案「已选作者」）；false = 待新建
 * @param seeds 空输入种子列表（调用方从全量常规作者拉取；2026-09-29 起上传页不传，
 *   资产编辑页保留空输入全显）：输入为空且非空时默认全显
 */
@Composable
fun QimengAuthorSuggestSection(
    title: String,
    query: String,
    committedName: String?,
    committedIsExisting: Boolean,
    suggestions: List<AuthorSuggestion>,
    seeds: List<AuthorSuggestion> = emptyList(),
    onQueryChange: (String) -> Unit,
    onPickSuggestion: (AuthorSuggestion) -> Unit,
    onCommitInput: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionTitleText(title)
        val committed = committedName
        if (committed != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 胶囊单源（QimengSegPill）：点按即清除；新建作者前缀区分两种确定态
                QimengSegPill(
                    text = if (committedIsExisting) "$committed ✕" else "新建：$committed ✕",
                    selected = true,
                    onClick = onClear,
                )
                Text(
                    text = if (committedIsExisting) "已选作者" else "将新建作者",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // 浮层开合状态（用户手动关闭后，继续输入即重新打开——搜索补全惯例）
            var menuOpen by rememberSaveable { mutableStateOf(true) }
            LaunchedEffect(query) {
                if (query.isNotBlank()) menuOpen = true
            }
            // 输入框窗口矩形自抓 = 浮层定位唯一坐标源（2026-09-29 真机取证：material3
            // 1.5.0-alpha28 的 DropdownMenu 在 verticalScroll 容器内锚定 y 坐标丢失，
            // 浮层兜底弹到窗口顶部盖住表单；Popup 原生锚同一管线不可信，故浮层改
            // 自定义 PopupPositionProvider + 自抓 boundsInWindow 定位，不读内部锚）。
            // State 整只传给 provider（非解包值）：滚动/键盘导致锚移动时经 snapshot
            // 读取链实时重定位浮层。
            val anchorBounds = remember { mutableStateOf<Rect?>(null) }
            Box(modifier = Modifier.onGloballyPositioned { anchorBounds.value = it.boundsInWindow() }) {
                QimengCapsuleTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = "输入作者名（联想选择，回车新建）",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onCommitInput() }),
                )
                if (anchorBounds.value != null && query.isNotBlank() && suggestions.isNotEmpty()) {
                    AuthorSuggestionMenu(
                        anchorBounds = anchorBounds,
                        suggestions = suggestions,
                        visible = menuOpen,
                        onDismiss = { menuOpen = false },
                        onPick = onPickSuggestion,
                    )
                }
            }
        }
    }
}

/**
 * 联想浮层菜单（无状态；搜索式下拉，不占表单布局空间）：命中行 = displayName +
 * 文件数（照 AuthorScreen 作者行口径）。列表上限 = 协议 limit 10，超出菜单内部自滚。
 *
 * 定位不走 material3 DropdownMenu（alpha28 在 verticalScroll 内锚定 y 丢失，见
 * [QimengAuthorSuggestSection] 头注），改裸 [Popup] + [AuthorSuggestionPopupPositionProvider]
 * 自锚定位；宽 = 输入框宽，下方空间不足时上翻（键盘把输入框顶到可视区下部的主场景，
 * 可视底按 ime insets 扣键盘——rootView.height 在键盘过渡期不缩，会把下方空间高估
 * 导致浮层盖住键盘）。focusable=false：不抢输入框焦点，敲字连续联想不闪断。
 *
 * 夸克浏览器式「点击扩大一体化」观感（2026-09-29 用户以夸克基准实拍定稿，第三笔；
 * 此前的直角拼接版被否——「输入框和联想推荐被剥离了，是两个元素」）：浮层自输入框
 * **中线** y 起覆盖输入框下半部，真输入框的上半（含顶部弧）充当大容器的顶部，浮层
 * 补齐下半部，两层拼成一个完整大圆角容器——
 *  1. 让位区（高 = 胶囊半径 = 输入框 34dp 的一半 17dp）：中间透明（Popup 窗口局部
 *     透明，露出下面真输入框的文字下半与光标），两侧用 [Path] 画弧形补块——补块 =
 *     让位区角部方格 [0..r]×[0..r] 减去胶囊弧四分之一圆盘（下弹时弧圆心在方格内上角
 *     (r, 0)），恰好填平胶囊底弧两侧的缺口，使容器左右边缘从头到尾垂直连续；
 *  2. 列表区：矩形 Surface，衔接边直角零间隙、外侧两角 [MENU_OUTER_CORNER_DP] 圆角、
 *     底色同聚焦态输入框（surfaceContainerHigh，见 QimengCapsuleTextField KDoc）、
 *     零阴影零 tonalElevation（纯色块一体感，靠与表单背景的色差区分）。
 * 视觉结果 = 真输入框顶弧 + 两侧补块延续的直边 + 列表底圆角 = 单一背景色大圆角容器，
 * 即夸克浏览器输入框点击后整个扩大的观感。
 *
 * 已知代价（用户已接受，不解决）：Android 浮层窗口无法部分穿透触摸，输入框下半
 * 17dp 条带的点击落在让位区透明处会被浮层窗口吃掉——打字联想场景输入框必已聚焦、
 * 无需再点它，影响可忽略。上翻方向（兜底场景）做镜像：列表区在上（顶部两角圆角）、
 * 让位区在下盖输入框上半，几何全部对称。
 */
@Composable
private fun AuthorSuggestionMenu(
    anchorBounds: State<Rect?>,
    suggestions: List<AuthorSuggestion>,
    visible: Boolean,
    onDismiss: () -> Unit,
    onPick: (AuthorSuggestion) -> Unit,
) {
    if (!visible) return
    val density = LocalDensity.current
    // 窗口可视底 = 根高 - ime insets（键盘弹出时实时扣键盘高；与 boundsInWindow 同
    // 坐标系）。adjustResize 的窗口收缩是过渡动画，rootView.height 滞后不可直接用。
    val windowBottomPx = LocalView.current.rootView.height.toFloat() -
        WindowInsets.ime.getBottom(density)
    val marginPx = with(density) { MENU_WINDOW_MARGIN_DP.dp.toPx() }
    val minMenuHeightPx = with(density) { MENU_MIN_HEIGHT_DP.dp.toPx() }
    val anchor = anchorBounds.value ?: return
    // 胶囊半径 = 输入框高的一半（34dp → 17dp）：让位区高度、补块弧半径、定位中线同源
    val halfCapsulePx = anchor.height / 2f
    // 空间值量化到整数 dp 再用：键盘弹出动画期 ime insets 逐帧微变，取整后多数帧同值
    // ——限高免逐帧重测、展开方向在阈值附近不抖动翻转（两处都是实测卡顿来源）
    val belowPx = with(density) {
        (windowBottomPx - anchor.bottom - marginPx).toDp().value.roundToInt().dp.toPx()
    }
    val abovePx = with(density) {
        (anchor.top - marginPx).toDp().value.roundToInt().dp.toPx()
    }
    // 展开方向组合期定死（内容限高、外侧圆角方向、定位三处同源，永不互相打架）：
    // 下方容不下最小高度且上方更宽裕才上翻，常规键盘场景输入框被顶到键盘上方、
    // 浮层自然落在输入框与键盘之间
    val expandUp = belowPx < minMenuHeightPx && abovePx > belowPx
    // 列表区限高：让位区（r）叠在输入框半高之内、不占输入框以下可视空间，故预算口径
    // 与拼接版完全一致（下弹 listH ≤ belowPx，上翻 listH ≤ abovePx）；已整数 dp 量化
    val maxHeightPx = (if (expandUp) abovePx else belowPx).coerceAtLeast(minMenuHeightPx)
    val panelColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val yieldHeightDp = with(density) { halfCapsulePx.toDp() }
    // 定位器实例稳定化：ime 动画期本函数每帧重组，若每帧新建 provider 实例会触发
    // Popup 的 updateParameters → updateViewLayout 与位置跟随的 updateViewLayout
    // 叠加成每帧双重窗口重排（实测卡顿来源）；key 稳定后仅位置跟随单次重排
    val positionProvider = remember(anchorBounds, expandUp) {
        AuthorSuggestionPopupPositionProvider(anchorBounds, expandUp)
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        Column(modifier = Modifier.width(with(density) { anchor.width.toDp() })) {
            if (expandUp) {
                AuthorSuggestionListPanel(
                    suggestions = suggestions,
                    expandUp = true,
                    maxHeightPx = maxHeightPx,
                    color = panelColor,
                    onPick = { suggestion ->
                        onDismiss()
                        onPick(suggestion)
                    },
                )
                AuthorSuggestionYieldZone(
                    expandUp = true,
                    color = panelColor,
                    modifier = Modifier.height(yieldHeightDp),
                )
            } else {
                AuthorSuggestionYieldZone(
                    expandUp = false,
                    color = panelColor,
                    modifier = Modifier.height(yieldHeightDp),
                )
                AuthorSuggestionListPanel(
                    suggestions = suggestions,
                    expandUp = false,
                    maxHeightPx = maxHeightPx,
                    color = panelColor,
                    onPick = { suggestion ->
                        onDismiss()
                        onPick(suggestion)
                    },
                )
            }
        }
    }
}

/**
 * 联想列表区（无状态；一体化容器的下半/上翻时上半）：衔接边（贴输入框一侧）直角
 * 零间隙、外侧两角 [MENU_OUTER_CORNER_DP] 圆角、纯色零阴影——与让位区补块同色无缝。
 */
@Composable
private fun AuthorSuggestionListPanel(
    suggestions: List<AuthorSuggestion>,
    expandUp: Boolean,
    maxHeightPx: Float,
    color: Color,
    onPick: (AuthorSuggestion) -> Unit,
) {
    Surface(
        modifier = Modifier.heightIn(max = with(LocalDensity.current) { maxHeightPx.toDp() }),
        shape = if (expandUp) {
            RoundedCornerShape(topStart = MENU_OUTER_CORNER_DP.dp, topEnd = MENU_OUTER_CORNER_DP.dp)
        } else {
            RoundedCornerShape(bottomStart = MENU_OUTER_CORNER_DP.dp, bottomEnd = MENU_OUTER_CORNER_DP.dp)
        },
        color = color,
    ) {
        LazyColumn {
            items(suggestions, key = { it.id }) { suggestion ->
                AuthorSuggestionRow(suggestion = suggestion, onPick = onPick)
            }
        }
    }
}

/**
 * 一体化容器的让位区（无状态；下弹盖输入框下半、上翻镜像盖上半）：中间透明露出真
 * 输入框（文字下半/光标），两侧 [Path] 弧形补块把胶囊弧的缺口补成直边。
 *
 * 补块几何（下弹、左补块为例，坐标系 = 让位区局部、y 向下、r = 让位区高 = 胶囊半径）：
 * 胶囊左下弧的圆心在 (r, 0)、半径 r，弧内侧属输入框、外侧（靠容器左缘）是缺口——
 * 补块 = 缺口多边形 (0,0)→(0,r)→(r,r) 加回弧 (r,r)→(0,0)（arcTo 90°→180°，圆心
 * (r, 0)），即角部方格减去四分之一圆盘。右补块镜像（圆心 (w-r, 0)，弧 90°→0°）。
 * 上翻镜像：弧圆心在方格内下角 (r, r)，缺口多边形在方格顶角，弧 270°→180°（左）/
 * 270°→360°（右）。补块外缘全程贴容器边（x=0 / x=w），与列表区同色同窗拼接后容器
 * 左右边缘垂直连续。
 */
@Composable
private fun AuthorSuggestionYieldZone(
    expandUp: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxWidth()) {
        val r = size.height
        val w = size.width
        if (r <= 0f || w <= 0f) return@Canvas
        val leftPatch = Path()
        val rightPatch = Path()
        if (expandUp) {
            // 上翻：让位区盖输入框上半，缺口在角部方格的顶角（弧圆心 (r, r) / (w-r, r)）
            leftPatch.moveTo(0f, 0f)
            leftPatch.lineTo(r, 0f)
            leftPatch.arcTo(Rect(0f, 0f, 2 * r, 2 * r), 270f, -90f, forceMoveTo = false)
            leftPatch.close()
            rightPatch.moveTo(w, 0f)
            rightPatch.lineTo(w - r, 0f)
            rightPatch.arcTo(Rect(w - 2 * r, 0f, w, 2 * r), 270f, 90f, forceMoveTo = false)
            rightPatch.close()
        } else {
            // 下弹：让位区盖输入框下半，缺口在角部方格的底角（弧圆心 (r, 0) / (w-r, 0)）
            leftPatch.moveTo(0f, 0f)
            leftPatch.lineTo(0f, r)
            leftPatch.lineTo(r, r)
            leftPatch.arcTo(Rect(0f, -r, 2 * r, r), 90f, 90f, forceMoveTo = false)
            leftPatch.close()
            rightPatch.moveTo(w, 0f)
            rightPatch.lineTo(w, r)
            rightPatch.lineTo(w - r, r)
            rightPatch.arcTo(Rect(w - 2 * r, -r, w, r), 90f, -90f, forceMoveTo = false)
            rightPatch.close()
        }
        drawPath(leftPatch, color)
        drawPath(rightPatch, color)
    }
}

/**
 * 联想浮层定位器：忽略 [calculatePosition] 传入的内部锚（verticalScroll 内 y 不可信，
 * 2026-09-29 真机取证浮层弹到窗口顶部即此管线），以自抓的输入框窗口矩形定位——
 * 一体式定位 y = 输入框**中线**（top + 高/2）：下弹 = 浮层顶起于中线（让位区盖输入框
 * 下半），上翻 = 浮层底贴中线（让位区盖输入框上半）。读 [anchorBounds] snapshot
 * state：锚随滚动/键盘移动时浮层实时跟随重定位。展开方向由组合期决策传入（见
 * [AuthorSuggestionMenu]），与圆角/限高同源。
 */
private class AuthorSuggestionPopupPositionProvider(
    private val anchorBounds: State<Rect?>,
    private val expandUp: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val anchor = this.anchorBounds.value ?: return IntOffset.Zero
        val middleY = anchor.top + anchor.height / 2f
        val rawY = if (expandUp) {
            (middleY - popupContentSize.height).roundToInt()
        } else {
            middleY.roundToInt()
        }
        val clampedY = rawY.coerceIn(
            0,
            (windowSize.height - popupContentSize.height).coerceAtLeast(0),
        )
        val clampedX = anchor.left.roundToInt().coerceIn(
            0,
            (windowSize.width - popupContentSize.width).coerceAtLeast(0),
        )
        return IntOffset(clampedX, clampedY)
    }
}

/**
 * 空输入种子列表（无状态）：与联想列表同一行渲染，但数据源是全量常规作者
 * （无协议条数上限），必须限高内滚防把外层表单撑爆——限高内滚用 LazyColumn
 * 而非联想列表的固定 Column（全量作者可能数百行，全组合不可接受）。
 */
@Composable
private fun QimengAuthorSeedList(
    seeds: List<AuthorSuggestion>,
    onPick: (AuthorSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        LazyColumn(modifier = Modifier.heightIn(max = AUTHOR_SEED_LIST_MAX_HEIGHT_DP.dp)) {
            items(seeds, key = { it.id }) { seed ->
                AuthorSuggestionRow(suggestion = seed, onPick = onPick)
            }
        }
    }
}

/** 联想/种子共用的命中行（displayName + 文件数副文案，照 AuthorScreen 作者行双行口径） */
@Composable
private fun AuthorSuggestionRow(
    suggestion: AuthorSuggestion,
    onPick: (AuthorSuggestion) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(suggestion) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = suggestion.displayName,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${suggestion.fileCount} 个文件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 来源段（无状态）：已选胶囊流 + 快捷词表横滚胶囊 + 自由输入行。
 * [enabled]=false 时整段降透明并展示 [disabledHint]（点击由调用方 VM 门槛兜底）。
 * 词表选项由调用方传入（服务端单一来源，ADR-0008：客户端禁硬编码词表）。
 */
@Composable
fun QimengSourceSection(
    title: String,
    selectedSources: List<String>,
    options: List<String>,
    enabled: Boolean,
    disabledHint: String,
    onToggle: (String) -> Unit,
    onAddCustom: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.then(
            if (enabled) {
                Modifier
            } else {
                Modifier.alpha(DISABLED_SECTION_ALPHA)
            },
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitleText(title)
        if (!enabled) {
            Text(
                text = disabledHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selectedSources.isNotEmpty()) {
            QimengWordPillFlow(
                pills = selectedSources.map { name -> QimengPill(text = name, selected = true) },
                onPillClick = { index -> onToggle(selectedSources[index]) },
            )
        }
        if (options.isNotEmpty()) {
            // 快捷词表横滚胶囊（点击 toggle；选中态由调用方状态驱动，组件本身受控）
            QimengChipRow(
                pills = options.map { name -> QimengPill(text = name, selected = name in selectedSources) },
                onPillClick = { index -> onToggle(options[index]) },
            )
        }
        var customInput by rememberSaveable { mutableStateOf("") }
        QimengCapsuleTextField(
            value = customInput,
            onValueChange = { customInput = it },
            placeholder = "输入新站点或 URL，回车加入",
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    onAddCustom(customInput)
                    customInput = ""
                },
            ),
        )
    }
}

/** 分节标题（titleMedium；原 UploadScreen SectionTitle 同款） */
@Composable
private fun SectionTitleText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** 来源段未启用时的降透明系数（整段禁用的视觉表达） */
private const val DISABLED_SECTION_ALPHA = 0.5f

/** 空输入种子列表限高（dp）：全量常规作者无条数上限，限高内滚防撑爆外层长表单 */
private const val AUTHOR_SEED_LIST_MAX_HEIGHT_DP = 200

// —— 联想浮层（Popup 自锚版）尺寸常量：夸克式一体化（中线覆盖+弧形补块+外侧圆角+零阴影） ——

/** 浮层与窗口边缘的最小留白（dp）：上下方向可用空间计算时扣减 */
private const val MENU_WINDOW_MARGIN_DP = 8

/** 浮层最小可用高度（dp）：内容限高的下限 + 上翻判定阈值，防极端场景压成零高 */
private const val MENU_MIN_HEIGHT_DP = 96

/** 浮层外侧边（不贴输入框一侧）圆角（dp）：一体式列表的收尾弧度 */
private const val MENU_OUTER_CORNER_DP = 12

/**
 * 拦截/错误横幅（点击关闭；原 UploadScreen MessageCard 同款抽出，上传页与编辑页共用）：
 * 错误用 error 容器色、提示/拦截用 tertiary 容器色，由调用方传色。
 * [onDismiss] 收尾位：支持调用点 trailing-lambda 写法。
 */
@Composable
fun QimengMessageCard(
    text: String,
    container: Color,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onDismiss() },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}
