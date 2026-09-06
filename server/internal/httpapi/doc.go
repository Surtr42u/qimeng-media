// Package httpapi 是唯一的 HTTP 接口层：请求校验、鉴权、调用编排服务、序列化响应。
//
// 协议端点已全部接线（501 stub 机制随全量接线移除，见 errors.go 注释）：
// 鉴权（setup/login + Bearer + dev 模式免密）、库管理与扫描、资产列表/
// 详情/四维聚合、搜索与补全、媒体签名直链（orig/thumb）、行为上报、
// 上传/文件整理/回收站、作者/标签/出处、推荐/排行/统计、旧备份导入
// 导出、系统监控与客户端配置；另有内嵌的极简验收页（永久保留作调试入口）。
//
// 为什么所有 HTTP 处理集中在这一层：保证"换 UI/加客户端零服务端改动"，
// 接口定义以 api/openapi.yaml 为唯一权威（协议宪法），路由与类型由
// oapi-codegen 生成到本包的 gen/ 子目录。
//
// 边界（见 docs/ARCHITECTURE.md §5）：
//   - 职责：请求校验、鉴权、调编排服务、序列化
//   - 禁止：业务规则（算法和领域规则属于 scanner/recommend/stats 等业务包）
package httpapi
