// Package httpapi 是唯一的 HTTP 接口层：请求校验、鉴权、调用编排服务、序列化响应。
//
// 为什么所有 HTTP 处理集中在这一层：保证"换 UI/加客户端零服务端改动"，
// 接口定义以 api/openapi.yaml 为唯一权威（协议宪法），实现代码由 oapi-codegen 生成到
// 本包的 gen/ 子目录（M0 阶段暂未生成）。
//
// 边界（见 docs/ARCHITECTURE.md §5）：
//   - 职责：请求校验、鉴权、调编排服务、序列化
//   - 禁止：业务规则（算法和领域规则属于 scanner/recommend/stats 等业务包）
package httpapi
