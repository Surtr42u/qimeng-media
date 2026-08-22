// Package auth 负责 token 签发/校验、密码哈希与签名直链。
//
// 为什么直链要签名：原图/视频走 HTTP Range 直链播放（性能关键路径不过业务逻辑），
// 用带签名、带过期时间的 URL 兼顾"直链零开销"与"内网之外的最低限度防扩散"。
//
// 为什么密码用 argon2id（docs/SECURITY.md「鉴权设计」）：抗 GPU 爆破的
// 内存硬化 KDF；哈希串存 PHC 标准格式，不与 Go 库绑定。
//
// 本包是纯逻辑件：不连数据库、不挂路由。verify 回调由接入层注入（查库比对），
// 免鉴权路由（/healthz）由中间件挂载位置决定，不在包内写死白名单。
//
// 边界（见 docs/ARCHITECTURE.md §5）：
//   - 职责：token 签发/校验、密码哈希/验证、签名直链、Bearer 鉴权中间件
package auth
