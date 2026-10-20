# Android 外观优化交付记录

> 2026-10-20，用户确认当前白底与柔和边界，并要求补齐相册胶囊后合入主分支。仅交付最终认可的外观改动，取消的布局/动效方案不再是待办。

## 最终范围

日间整页、卡面及窗口使用 #FFFFFF，辅助面为中性灰；共享 SoftDaylight 轻影为黑色 6%、8dp 模糊、(0,2)dp 偏移。首页搜索/筛选/列数、推荐/COS/排行榜主胶囊和相册维度/值胶囊复用轻影，不加灰色染色或硬描边；选中染色、尺寸、字号、间距和按压/切换语义保持原样。设置入口改名“外观”，提高两档次级文字与系统栏可读性；深色背景/材质保持原值。

- 页面/窗口 #FFFFFF；辅助面 #F4F4F4、胶囊辅助底 #F0F0F0、分割线 #E6E6E6，均无蓝紫渐变。
- 次级文字日间 #666666、深色 #A3A3A3；系统栏图标跟随 App 实际主题。
- core:ui 的 SoftDaylight 使用现有 Compose background/dropShadow；选择胶囊经可选 pillModifier 复用修饰符，默认不影响其他页面。
- 首页原排版、相册原 16:9 预览、筛选/返回/导航、业务/API/SDK/原件与缓存保持原样；不增加运行时依赖。

## 验证与合入

指定 qimeng_api35 Debug，使用隔离实例和 24 张合成图片；8420 真库无测试写入。Android 工作区构建及 UI/首页/相册/设置/统计 155 项单测通过，独立暂存快照另构建通过（1m12s）且同组 155 项全通过；实看首页、我的、统计、外观及主题切换，并在最终包确认相册维度和值区块胶囊及展开/收起。不同轮次的验证范围见 [HANDOVER](HANDOVER.md)。真机观感、帧率/功耗、大字体/旋转与 API ≤30 未验收，手机 Release 不替换。

最终代码在 codex/android-ui-modernization 整理后合入 master；只提交本次 UI 及相关文档，已有其他未提交改动保留。跨轮试调过程不作为最终设计规范，现行外观说明见 [Android README](../android/README.md)与 [UI 指南](GUIDE_UI.md)。

## 官方资料

核对 [Material 表面色角色](https://github.com/material-components/material-components-android/blob/master/docs/theming/Color.md)与 [Compose 阴影教程](https://developer.android.com/develop/ui/compose/graphics/draw/shadows)。纯白和轻影参数是本项目按用户观感的选择，双阴影示例不等于所有白色界面的通用要求。
