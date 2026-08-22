// Package filing 负责文件操作：上传、移动、重命名、回收站。
//
// 为什么文件操作收口到一个包：所有写媒体库的入口必须经过同一套路径安全校验，
// 分散校验等于没有校验（一个遗漏就是路径穿越漏洞）。删除永远进回收站，禁止物理删除。
//
// 边界（见 docs/ARCHITECTURE.md §5）：
//   - 职责：上传、移动、重命名、回收站
//   - 禁止：绕过路径安全校验
//
// 本包全部为纯函数（无 IO、无数据库、无 HTTP），便于穷举测试：
//   - path.go     路径安全（SECURITY 红线 #1）：NormalizeRelPath / PathWithinRoot
//   - reserved.go Windows 保留设备名判定（路径与文件名共用）
//   - filename.go 文件名清洗与冲突重命名
//   - mime.go     文件头魔数嗅探（不信扩展名，SECURITY 红线 #4 第②道）
//   - upload.go   上传四道校验（SECURITY 红线 #4）与扩展名白名单
//   - trash.go    回收站路径布局与保留期计算（ADR-0007）
//
// 磁盘 IO、HTTP handler、数据库事务的接线由后续任务完成，本包不引入。
package filing
