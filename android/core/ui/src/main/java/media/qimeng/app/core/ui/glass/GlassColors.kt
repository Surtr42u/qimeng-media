package media.qimeng.app.core.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import media.qimeng.app.core.ui.theme.isQimengDarkTheme

/**
 * 玻璃面板配色规范（ADR-0031「流光玻璃」唯一来源）。
 *
 * 玻璃质感四要素（自建实现，零新依赖）：
 * 1. **fill** 半透明基色——面板的「体」，深浅主题各给 普通档/加厚档（fillStrong，底栏等
 *    需要压住下方滚动内容的场合）；
 * 2. **edge** 受光描边——上亮下暗的 1dp 渐变描边，模拟玻璃边缘的环境受光（Liquid Glass
 *    的关键识别特征）；
 * 3. **sheen** 顶部高光——面板上部纵向渐隐的白纱，模拟曲面的镜面反光；
 * 4. **shadow** 深色投影——把面板从背景上「抬」起来，与半透明体一起构成景深。
 *
 * 降级策略（ADR-0031，与 API 级别无关地成立）：本体系不依赖 backdrop 采样模糊——
 * 官方 Compose 无 backdrop-blur API（已核，2026-10-02）；「被磨砂的景深」由
 * [AuroraBackdrop] 自绘辉光承担（玻璃面板覆于其上即成磨砂观感）。当前实现零 blur：
 * `Modifier.blur` 仅为未来对自有内容（头图/氛围层）做真模糊的预留策略（API < 31
 * no-op），面板观感各 API 级别一致——fill alpha 的取值保证文字对比度仍达标。
 */
@Immutable
data class GlassColors(
    /** 普通档面板体（卡片/胶囊容器） */
    val fill: Color,
    /** 加厚档面板体（底栏/悬浮条等压住滚动内容的场合） */
    val fillStrong: Color,
    /** 受光描边·上端 */
    val edgeTop: Color,
    /** 受光描边·下端 */
    val edgeBottom: Color,
    /** 顶部高光纱 */
    val sheen: Color,
    /** 投影色（黑/深靛 @ 低 alpha） */
    val shadow: Color,
    /** 顶部镜面高光池（2026-10-03 液态感强化批：上缘椭圆圆心径向光，曲面镜面反射的
     *  「光聚」近似——液态玻璃识别特征的静态主力笔） */
    val specular: Color,
    /** 底部内影（液态感强化批：下缘向内的纵深渐暗，玻璃体「厚度」感） */
    val innerShade: Color,
)

/** 暗色玻璃：靛夜体 + 白纱受光（2026-10-03 质感对齐批：遮盖提至坞 FROSTED 磨砂档以上——
 *  流内胶囊无 backdrop 采样（下方无滚过内容，采样无意义），观感对齐坞的材料要素靠
 *  scrim 档位/受光边/镜面/投影四件套承担） */
private val DarkGlass = GlassColors(
    fill = Color(0xFF1B2033).copy(alpha = 0.86f),
    fillStrong = Color(0xFF151A2C).copy(alpha = 0.96f),
    edgeTop = Color(0xFFFFFFFF).copy(alpha = 0.32f),
    edgeBottom = Color(0xFFFFFFFF).copy(alpha = 0.08f),
    sheen = Color(0xFFFFFFFF).copy(alpha = 0.14f),
    shadow = Color(0xFF04050A).copy(alpha = 0.45f),
    specular = Color(0xFFFFFFFF).copy(alpha = 0.22f),
    innerShade = Color(0xFF04050A).copy(alpha = 0.32f),
)

/** 浅色玻璃：白瓷体 + 白高光受光（下缘转冷灰紫，保持面板轮廓可辨；遮盖同批提档） */
private val LightGlass = GlassColors(
    fill = Color(0xFFFFFFFF).copy(alpha = 0.85f),
    fillStrong = Color(0xFFFDFDFF).copy(alpha = 0.95f),
    edgeTop = Color(0xFFFFFFFF).copy(alpha = 0.95f),
    edgeBottom = Color(0xFF8F96B8).copy(alpha = 0.30f),
    sheen = Color(0xFFFFFFFF).copy(alpha = 0.55f),
    shadow = Color(0xFF3A4160).copy(alpha = 0.16f),
    specular = Color(0xFFFFFFFF).copy(alpha = 0.90f),
    innerShade = Color(0xFF3A4160).copy(alpha = 0.12f),
)

/** 主题感知的玻璃配色入口（玻璃件唯一取色口，禁止 feature 直引 DarkGlass/LightGlass） */
@Composable
fun glassColors(): GlassColors = if (isQimengDarkTheme()) DarkGlass else LightGlass
