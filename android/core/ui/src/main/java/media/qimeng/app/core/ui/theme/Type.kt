package media.qimeng.app.core.ui.theme

import androidx.compose.material3.Typography

/**
 * 字体排印：M3 基线起步。
 * 旧版使用系统默认中文字体栈（prototype.css --font，PingFang SC/微软雅黑），Compose 默认
 * Typography 同样落到系统字体——无需自定义 fontFamily；字号体系待 M4-6 设置页批次对照规格书细化。
 *
 * Y3 批（2026-09-12 全局字体对齐旧版）裁决记档：**Typography 角色本身不改**（保持 M3 默认
 * 字号/字重）——旧版页标题自身就有三档 28sp（相册 fragment_all_files.xml L36）/24sp Bold
 * （首页 fragment_home.xml L31）/22sp Bold（收藏 fragment_favorite.xml L41、历史
 * fragment_browse_history.xml L39），角色级一刀切会让收藏/历史/设置页走样；页面级差异一律走
 * 组件级覆盖（QimengTitleRow 新增 titleStyle 参数 / 各页 titleLarge.copy / QimengMediaGrid
 * 组头 titleSmall.copy(16sp, Bold)），本文件保持 M3 基线不动。
 */
val QimengTypography = Typography()
