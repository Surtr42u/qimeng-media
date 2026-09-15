// Package thumbnail 负责缩略图生成队列：工作池并发、懒生成、缓存键管理。
//
// 为什么用队列而非同步生成：缩略图是 CPU/IO 密集型离线管线（ffmpeg 抽帧等），
// 同步生成会把延迟转嫁给用户请求，违背"列表页秒开"的产品底线。
// 原图永不转码，缩略图/预览副本写入独立数据目录（dataDir），不改媒体库文件。
//
// 边界（见 docs/ARCHITECTURE.md §5）：
//   - 职责：缩略图生成队列（工作池、懒生成、缓存键）
//   - 禁止：阻塞 API 请求
//
// 包组成（M1 管线独立件，入库触发/HTTP 端点由后续任务接线）：
//   - ffmpeg.go     ffmpeg/ffprobe 封装（抽帧、元数据探测、图片缩放、原子落盘）
//   - blackframe.go 黑帧检测（纯函数判定 + 候选时间点选帧，参数逐字遵守 DOMAIN_RULES §11）
//   - cachekey.go   缓存键（SHA-256）与目录布局 dataDir/thumbs/{key[:2]}/{key}{ext}
//   - stillformat.go 静图输出格式自适应（libwebp 缺失时 mjpeg 降级，U11 内嵌形态）
//   - pool.go       工作池（带界队列、非阻塞提交、优雅关闭）
//   - generate.go   按媒体类型（图片/动图/视频）编排生成，幂等
package thumbnail
