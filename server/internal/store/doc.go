// Package store 是唯一的数据访问层：sqlc 生成代码、migrations 引导与只读装载策略。
//
// 为什么 SQL 先写再生成：让编译器保证 Go 类型与表结构一致，
// 消灭手写扫描代码里的字段错位这类运行期才爆的 bug。
// 表结构演进只能新增 migration 文件（server/migrations/，只增不改）。
//
// 边界（见 docs/ARCHITECTURE.md §5）：
//   - 职责：sqlc 生成代码 + migrations；推荐输入的查询计划选择与标签装载
//   - 领域评分、响应缓存与 HTTP 适配由各自模块负责
//   - 禁止：SQL 字符串拼接（防注入，一律参数化）
package store
