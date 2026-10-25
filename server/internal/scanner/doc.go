// Package scanner 负责媒体库的全量/增量扫描、变更检测（size + mtime）与移动合并。
//
// 为什么扫描器独立成包：扫描是长时后台任务，与 API 请求生命周期完全不同，
// 进度通过 events 包推送（SSE），不阻塞任何在线请求。
//
// 组成（M1）：
//   - media.go    媒体类型识别（扩展名白名单，DOMAIN_RULES §11）与目录过滤
//   - scanner.go  全量扫描 Scan：遍历→变更检测入库→差集→移动合并/清理；
//     单库防重入闸门（ErrAlreadyScanning）
//   - watch.go / watch_update.go 增量监听、同库批次合并与已有文件更新；
//     结构变化交全量扫描，保留移动合并身份。
//   - background.go 动态监听管理、失败重试及轮询兜底；后台全扫跨库串行。
//
// 边界（见 docs/ARCHITECTURE.md §5）：
//   - 职责：全量/增量扫描、变更检测、移动合并
//   - 禁止：直接写库（一切持久化必须经 store 包；本包所需的
//     ListScanAssetSnapshots/DeleteAsset/MoveAssetPath 已入 queries 并生成）
package scanner
