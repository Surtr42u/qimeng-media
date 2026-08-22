// Package httpapi 是唯一的 HTTP 接口层：请求校验、鉴权、调用编排服务、序列化响应。
//
// M1 里程碑接线了「浏览闭环」：鉴权（setup/verify + Bearer）、库管理、
// 资产列表/详情（动态筛选 + keyset 分页）、媒体签名直链（orig/thumb）、
// 行为上报（view/like/favorite）、SSE 事件流，以及一个内嵌的极简验收页
// （永久保留作调试入口）。其余端点（上传/回收站/推荐/统计等）按里程碑
// 逐步接入，当前统一返回 501 NOT_IMPLEMENTED。
//
// 为什么所有 HTTP 处理集中在这一层：保证"换 UI/加客户端零服务端改动"，
// 接口定义以 api/openapi.yaml 为唯一权威（协议宪法），路由与类型由
// oapi-codegen 生成到本包的 gen/ 子目录。
//
// 边界（见 docs/ARCHITECTURE.md §5）：
//   - 职责：请求校验、鉴权、调编排服务、序列化
//   - 禁止：业务规则（算法和领域规则属于 scanner/recommend/stats 等业务包）
package httpapi
