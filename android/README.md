# android/ — Android 薄客户端

> M4 里程碑在此开发。动手前必读：`../AI_README_FIRST.md`、`../docs/adr/0008`。

## 技术栈（定论）

Kotlin + Jetpack Compose + ViewModel + Hilt + Coil 3（HTTP 直链加载）+ Media3/ExoPlayer（直链播放）+ make sdk 生成的 Kotlin 客户端。

## 职责边界（薄客户端纪律）

- 列表/搜索/推荐/统计：调 API，客户端不复制任何算法
- 图片/视频：签名直链交给 Coil / ExoPlayer，客户端不做解码管线
- 行为上报：浏览/点赞/收藏事件本地排队，联网补传（离线不丢）
- 上传（核心功能）：系统分享接收 + 文件选择 + 目录浏览 + 队列进度——"手机采集端"主通道
- 本地持久化仅两类：登录配置、可设上限的媒体缓存（LRU 自动清理）
