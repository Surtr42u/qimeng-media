// Package authorattach 实现「上传挂靠作者与来源」的服务端编排
// （REQ-上传指定作者与来源）：上传入库后把作者/来源/作品行并进用户导入的
// TXT 清单片段本体，并维护重导入保护元数据与本地 txt 自动镜像。
//
// 四块职责：
//   - 片段存取单一来源（sources.go）：LoadSources/PersistSources 是
//     kv_settings.imported_txt_sources 的唯一读写通道——httpapi 侧统一改用
//     这里，消灭与既有 txtSource 的双份结构。Source 结构在 {filename,
//     content} 之上补 importedAt（RFC3339，store.FormatTimestamp 产出），
//     旧片段无该字段 → 零值 = 最旧。
//   - 片段编排（attach.go / edit.go）：定位目标片段（作者块命中→数组序
//     第一个片段；未命中→最近导入片段；无片段→自动创建承载片段）→ 纯函数
//     变更片段原文 → 事务内写回的多步流。Apply 是上传挂靠编排（REQ §3.3
//     第 6 条）；编辑端点的作者来源/资产作者替换走 edit.go（ADR-0024），
//     与 Apply 共用同一份落点规则与新建块形态，禁止复制粘贴。
//   - 重导入保护元数据（upload_entries.go）：按片段文件名分组记录「哪些行
//     由上传写入」——出处元数据而非第二真相（真相永远是片段本体，REQ
//     §4.2）；「同文件名重导」时用它识别被冲掉的上传条目。
//   - 本地 txt 自动镜像（mirror.go）：把目标片段原文原子写入用户配置的
//     镜像路径，尽力而为的投影——任何失败只告警不抛错，绝不阻塞核心操作
//     （REQ §3.4）。
//
// 边界（docs/ARCHITECTURE.md §5，ADR-0019 编排层下沉 / ADR-0023 上传挂靠）：
//   - 依赖 store（kv/表读写）与 authoring（文本手术纯函数）；
//   - 被 httpapi 调用，禁止反向依赖 httpapi（depguard 红线）；
//   - 文本格式规则全部在 authoring 纯函数层，本包不做行级手术、不改解析
//     语义；时间一律由调用方传入 time.Time（可测性），包内不调 time.Now。
package authorattach
