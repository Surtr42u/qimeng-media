// Package localsync 本机文件夹自动同步通道的纯逻辑层（ADR-0030）：库名净化
// 匹配、同步根目录扫描分类、同步根与库根/数据目录的重叠检查，以及
// PlanScan 的只读扫描编排与存活集合构建。
//
// 边界：本包无自有 IO 状态（ScanTree/RootOverlapError 的文件系统访问是
// 无状态只读调用，入参出参即全部），不做导入编排、不碰数据库——编排与
// 运行态（观测/失败记录）在 syncchannel，状态协议适配在 httpapi。
// syncchannel 复用 mediawrite 入库/策略与 authorimport TXT 事务，
// 本包维持无状态扫描/匹配边界。
package localsync
