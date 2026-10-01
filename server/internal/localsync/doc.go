// Package localsync 本机文件夹自动同步通道的纯逻辑层（ADR-0030）：库名净化
// 匹配、同步根目录扫描分类、同步根与库根/数据目录的重叠检查。
//
// 边界：本包无自有 IO 状态（ScanTree/RootOverlapError 的文件系统访问是
// 无状态只读调用，入参出参即全部），不做导入编排、不碰数据库——编排与
// 运行态（观测/失败记录/状态端点）在 httpapi/localsync.go 与
// localsync_runner.go。编排不上收进本包的原因：importTxt 与上传策略
// （resolveUploadPolicy）是 httpapi 私有逻辑，编排依赖它们；同
// trash_sweeper 先例（清扫编排放 httpapi，判定纯函数下沉 filing）。
package localsync
