// Package auth 负责 token 签发/校验与签名直链。
//
// 为什么直链要签名：原图/视频走 HTTP Range 直链播放（性能关键路径不过业务逻辑），
// 用带签名、带过期时间的 URL 兼顾"直链零开销"与"内网之外的最低限度防扩散"。
//
// 边界（见 docs/ARCHITECTURE.md §5）：
//   - 职责：token 签发/校验、签名直链
package auth
