# android/ — Android 客户端（M4 · 旧 UI 照搬路线，ADR-0013）

> M4 里程碑在此开发。动手前必读：`../AI_README_FIRST.md`、`../docs/adr/0008`、`../docs/adr/0013`、`../docs/HANDOVER_APP.md`（批次任务书）。

## 技术栈（2026-09-04 定论，ADR-0013：旧 UI 照搬，非 Compose 重建）

对齐旧项目（QimengMedia）实测技术栈，照搬为主：

- Kotlin + View/XML + ViewBinding + Fragment 单 Activity（旧 UI 资产整体照搬：15 Fragment / 19 XML / 5 自定义控件）
- 手写 AppContainer DI（旧项目同款，不引入 Hilt）
- Coil 3（加载服务端签名直链——缩略图/原图；本地解码管线不搬）
- Media3/ExoPlayer + BiliPlayerView（旧项目 837 行自写播放控件整体照搬：B 站式手势/时间轴标签）
- Room（仅作客户端缓存与事件队列，非数据源；服务端才是唯一事实）
- make sdk 生成的 Kotlin 客户端（`media.qimeng.sdk`）
- 包名/namespace/applicationId 沿用旧项目 `com.qimeng.media`（照搬文件零包改动）

## 职责边界（薄客户端纪律）

- 列表/搜索/推荐/统计：调 API，客户端不复制任何算法——服务端 M3 已实现全部领域算法；`MediaBrowserLogic` 的 recommend/rank/applyFilter **不搬**，只搬纯展示 helper（formatSize/dateLabel 分组等）
- 图片/视频：签名直链交给 Coil / ExoPlayer，客户端不做解码管线
- 行为上报：浏览/点赞/收藏事件本地排队，联网补传（离线不丢）
- 上传（核心功能）：系统分享接收 + 文件选择 + 目录浏览 + 队列进度——"手机采集端"主通道
- 本地持久化三类：登录配置、可设上限的媒体缓存（LRU）、事件队列

## 禁搬清单（单机生态，ADR-0013）

旧项目以下代码禁止搬入，新实现一律走网络 SDK：`scan/` 全部、`domain/ScanUseCase`、`domain/AutoSyncUseCase`（本地自动扫描）、`backup/BackupManager`、`core/` 本地缩略图解码管线（ThumbnailCache / ThumbnailLoader / LargeImageDecoder / SpngDecoder）、`data/repository/DefaultLocalMediaRepository` 旧实现。对应 UI 页（数据备份/数据管理/扫描设置项）不搬。
