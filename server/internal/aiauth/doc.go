// Package aiauth 管理独立 AI 客户端凭据、scope 与写操作审计（ADR-0057 P1）。
//
// 边界（见 docs/AI_PLATFORM_API_GAPS.md 与 docs/SECURITY.md）：
//   - 凭据与管理员会话（auth_sessions）完全分离，禁止相互升级。明文 token
//     只在本地管理命令 create/rotate 的输出中出现一次；库里只存
//     auth.TokenHash 的 SHA-256，日志/错误/审计一律不落明文。
//   - scope 是服务端强制的最小授权模型：未知 scope 解析失败（fail-closed），
//     数据库里带非法 scope 的客户端不会被加载为有效身份。具体 HTTP
//     method+path 需要哪个 scope 由接入层（httpapi）决定；本包不感知路由。
//   - 审计表 ai_audit 只增；写审计失败不阻断业务，由调用方记录错误日志。
//   - 管理动作（创建/列表/撤销/轮换）由 qimeng 本地子命令调用；本轮不开放
//     HTTP 管理端点，也不改主 API 协议。
//
// 依赖方向：store/db + auth + 标准库；不依赖 httpapi（depguard 红线）。
package aiauth
