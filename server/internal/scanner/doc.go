// Package scanner 负责媒体库的全量/增量扫描、变更检测（size + mtime）与移动合并。
//
// 为什么扫描器独立成包：扫描是长时后台任务，与 API 请求生命周期完全不同，
// 进度通过 events 包推送（SSE），不阻塞任何在线请求。
//
// 边界（见 docs/ARCHITECTURE.md §5）：
//   - 职责：全量/增量扫描、变更检测、移动合并
//   - 禁止：直接写库（一切持久化必须经 store 包）
package scanner
