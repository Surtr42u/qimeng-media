# HANDOVER-UI - 桌面客户端风格媒体库 UI 交接说明

> 写给下一位专做 UI 的 AI（任何模型/工具）。人类用户无编程基础，全部代码由 AI 生成。
> 最后更新：2026-09-01（原型 UI 收尾：六页搬入 + 明暗主题 + v1 旧壳删除 + 中性化措辞）
> 用途：新开的 AI 会话直接读本文档即可接手 UI 工作，无需回看本会话记录。
> 主交接文档（后端/进度/约定）仍以 `docs/HANDOVER.md` 为准，本文档只覆盖 UI 路线。

## 1. 任务背景与当前路线（用户拍板）

1. 用户要求复刻桌面 PC 客户端 UI，并把这个界面的**侧边栏**移植进 qimeng-media 项目。
2. **UI 主路线 = `media-ui-prototype/`**（桌面客户端风格的静态原型：纯 HTML/CSS/JS）。
3. **v2（`web/src/panel-demo/`）也已删除（2026-09-01）**——web 端只保留鉴权基建（AuthGate/RootLayout/SseBridge/LoginGate）与生成 SDK、共享工具，首页为占位提示；
   待原型敲定后把功能移植进 web 重建页面（**用户拍板"先不做 UI 接入"**，接真实数据等 UI 做完再启动）。
4. **v1 旧壳与 v2 panel-demo 已于 2026-09-01 彻底删除**（v1：pages/、旧壳专属 components、旧 hooks、/legacy 与 /detail；v2：panel-demo/、components/tremor/、lib/tremor、相应依赖包），web 端仅保留鉴权基建与工具层。
5. 原型内已**去除所有平台命名痕迹**（标题、注释、数据字段、任务书），措辞统一中性化；后续新代码同样不提及。

## 2. 真机调研方法（可复用）

桌面客户端是 Electron 应用，可用 Chrome DevTools 协议（CDP）读取内部 DOM。

```bash
# ① 确保旧进程退出（未带调试参数启动的进程不会监听端口）
taskkill /F /IM 客户端.exe
# ② 以调试模式启动（保持运行，另开终端/后台）
"C:\Program Files\...\客户端.exe" --remote-debugging-port=9222
# ③ 列出页面（主页 = index.html#/page/home/recommends；player.html = 播放器）
curl http://127.0.0.1:9222/json
# ④ 连接 ws://127.0.0.1:9222/devtools/page/{id} 发 Runtime.evaluate 读 DOM / Page.captureScreenshot 截图
```

现成抓取脚本（在 `%TEMP%`，可拷到工作区复用）：

| 脚本 | 用法 | 功能 |
|---|---|---|
| `read-dom.mjs` | `node read-dom.mjs <pageId> [depth] [nodes]` | 通用 DOM 树（tag/id/class/role/文本/href/可见性过滤） |
| `card-extractor.mjs` | `node card-extractor.mjs <pageId> [out.json]` | 推荐卡片结构化（id/cover 真实封面/lines 分行的 播放·互动·时长·标题·作者·日期）；%TEMP% 中的旧脚本可按此改名取用 |

踩坑记录：
- 卡片根容器定位 = 沿 `a[href*="/video/VID"]` 向上找 **textContent 含"不感兴趣"的最近祖先**（卡片 hover 菜单特征文本）
- `node -e` 内联脚本传中文会乱码；用脚本文件 + 结果写入 utf8 文件再查看（终端管道显示乱码不全代表数据坏）
- 封面要过滤 `data:` 打头的占位 img，拿 `img.src`（真实地址形如 `//cdn.example/archive/<hash>.jpg@672w_378h_1c_!pc-common-cover-h.webp`）
- 本地 curl 可访问原图床，但 ZCode 内嵌浏览器（IAB）加载不了 → 原型图片全部下载到本地

## 3. 原型实测参数（复刻依据）

客户端测试窗口 **1188×742**；白色底；14px 系统字体栈（-apple-system/BlinkMacSystemFont/Helvetica Neue）。

| 区域 | 尺寸 | 说明 |
|---|---|---|
| 左侧边栏 | **64px 宽、通栏** | 顶部返回箭头（灰）→ 页面导航（图标+12px 文字）→ 底部图标组（明暗主题/维护/设置，20px 线形图标，hover 圆角灰底） |
| 顶栏 | **75px 高** | 左：分类 tab；中：搜索框（顶栏框内居中）；右：窗口键（—□×） |
| 分类 tab | 320×44 | 直播/推荐(激活)/热门/追番/影视；16px；激活态主色加粗 + 底部 22×3 圆角短横线（现为 推荐/cos/热门 三项） |
| 搜索框 | **336×34**，圆角 17 | 聚焦主色描边；聚焦弹出居中下拉面板（搜索历史+推荐搜索） |
| 卡片流 | **4 列**（minmax ≈230px，gap 20-24） | 封面 16:9 圆角 8px |
| 标题 | 14px/20px 两行截断 | 卡片 hover 变主色 |

主题色（已统一为侧边栏主色）：`#4250af`（激活 tab/激活侧栏项），激活深色 `#35428f`；文字 `#18191C`；次要 `#9499A0`；分割 `#E3E5E7`。深色模式由侧栏月亮按钮切换（`.dark`，主色 oklch(0.836 0.074 258.58)）。

## 4. 原型现状（media-ui-prototype/，已完成并逐页截图验证）

```
media-ui-prototype/
├── index.html   # 六页结构（首页/相册/我的/数据/维护/设置）+ 侧栏 + 顶栏 + 搜索面板
├── style.css    # 全部样式（设计 token 集中在 :root/.dark，颜色无重复字面量）
├── app.js       # 卡片渲染 + 相册筛选JS + 页面切换 + 主题切换 + 页面内交互
├── covers/      # 本地化封面/头像（14 张）
└── data/        # home-dom.json / home-cards.json（真机抓取档案）
```

| 导航 | 页面 | 状态 |
|---|---|---|
| 首页 | 卡片流（12 张真机卡，已删播放/互动/点赞数/作者头像/广告/hover 菜单——用户拍板） | ✓ |
| 相册 | 两段式胶囊筛选（维度行 4 维+值行带计数+展开/收起+排序）+ 即时过滤内容网格（12 卡、mock 空态） | ✓ |
| 我的 | 头部资料卡 + Tabs（关注作者/收藏作品/浏览历史历史流样式） | ✓ |
| 数据 | 时间筛选 + 6 指标 + 趋势线 + 内容分布双环 + 排行榜三卡 | ✓ |
| 维护 | 性能监控（4 圆环+指标卡+网络负载曲线）+ 维护工具（文件管理/回收站+日志表） | ✓ |
| 设置 | 扫描/上传/界面表单卡 + 保存提示 | ✓ |
| （顶栏） | 推荐/cos/热门 只在首页显示；搜索面板（搜索历史胶囊+展开更多+推荐搜索占位） | ✓ |
| 热门二级导航 | 综合热门/排行榜胶囊；排行榜=日/月/周/年；固定贴顶栏、不随内容滚动 | ✓ |

注：推荐搜索占位「推荐搜索词将在接入推荐参数后生成」——推荐参数（9 维权重）**未接入**（用户拍板等做完再一起接）。

## 5. 待办与约定

1. 原型各页达成视觉验收后 → 把功能移植进 web 端重建页面（web 当前为占位页 + 鉴权基建，v1/v2 已删）。
2. 原型敲定后移植到 web 端重建页面，再接真实数据（资产列表 /stats/* /system/status）——已拍板先不做。
3. 推荐偏好设置页（9 维权重+4 预设）：后端 GET/PUT `/recommendations/prefs` 已就绪。
4. 跑法：不要单独拉前端——统一访问 `http://127.0.0.1:8420`（后端托管 `web/dist`）；改前端先 `npm --prefix web run build`。
   原型单独预览：`cd media-ui-prototype && node serve.mjs 8099`（或直接双击 index.html）。
5. 铁律 7：UI 组件禁止直接调 API、禁止内嵌业务规则——接数据走 hooks/客户端逻辑层。
6. UI 工作另见 `docs/adr/0008`（UI 解耦策略）。

## 6. 工作树现状

- 已删（git 可见删除）：`web/src/pages/`、`components/{admin,asset,charts,organize,upload,viewer,misc}/`、`components/layout/AppShell.tsx`、`components/ui/` 大部分、若干旧 `hooks/`（use-assets 等）；保留：`components/auth/`、`components/layout/{AuthGate,RootLayout,SseBridge}`、`components/ui/{button,input,sonner}.tsx`、`hooks/{use-session,use-sse-events}`、`lib/{api-client,constants,sse,utils}`、`api/*`。
- `media-ui-prototype/` 全部为未跟踪新文件（本会话产出）。
- 后端 M3 收尾的 18 项未提交改动（/dirs libraryId 适配等）仍在工作树——接手时先确认归属，不要覆盖。

## 7. 给下一位 AI 的起点建议

1. 先读本文档 §4（原型现状）与 `media-ui-prototype/style.css` 的 token 层。
2. 浏览器打开 `http://127.0.0.1:8099/` 逐页过一遍（侧栏切页 + 月亮切深色 + 热门二级导航 + 搜索面板）。
3. 用户会继续对照参考原型逐项提修改（截至 2026-09-01 的修改记录见 docs/CHANGELOG.md「UI 原型收尾」条目）。
