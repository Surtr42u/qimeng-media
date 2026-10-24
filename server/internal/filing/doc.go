// Package filing 负责文件操作：上传、移动、重命名、回收站。
//
// 为什么文件操作收口到一个包：所有写媒体库的入口必须经过同一套路径安全校验，
// 分散校验等于没有校验（一个遗漏就是路径穿越漏洞）。删除永远进回收站，禁止物理删除。
//
// 边界（见 docs/ARCHITECTURE.md §5）：
//   - 职责：上传、移动、重命名、回收站
//   - 禁止：绕过路径安全校验
//
// 文件名/布局/校验规则保持纯函数；路径检查、根句柄与恢复索引承担文件IO。
// 本包不依赖HTTP或数据库，磁盘与持久化提交的编排仍由调用方负责。
//   - path.go     路径规范化 + 现存父段链接检查（SECURITY 红线 #1）
//   - reserved.go Windows 保留设备名判定（路径与文件名共用）
//   - filename.go 文件名清洗与冲突重命名
//   - namesuggest.go 作品名序号联想（GET /assets/name-suggestions 纯函数判定）
//   - mime.go     文件头魔数嗅探（不信扩展名，SECURITY 红线 #4 第②道）
//   - upload.go   上传四道校验（SECURITY 红线 #4）与扩展名白名单
//   - trash.go    回收站路径布局与保留期计算（ADR-0007）
//   - gate.go     按库互斥设施（#9：冲突探测→rename 关键段串行化）——IO 例外 1
//   - move.go     跨挂载点文件移动（回收站进/出，EXDEV 回落）
//   - move_root.go 根内改名与回滚的目标占用保护（不提供跨进程原子 no-replace）
//   - restore_intent.go 未完成恢复的路径索引/显式缓存失效与根句柄收尾
//
// 文件与数据库之间的事务编排不在本包；恢复meta仍是唯一事实源（ADR-0039）。
package filing
