// Package research 实现媒体研究流水线：服务端原生纯 HTTP provider 网关
// 加本机 Lens sidecar 代理（ADR-0053）。
//
// 职责边界：
//   - provider 网关：固定矩阵（fxtwitter/pixiv/yandere/e621/wayback/steam/
//     bing），每个 provider 只支持声明的 operation；出口统一走安全 HTTP
//     客户端（仅 https、域名白名单、禁重定向与环境代理、DNS 解析后校验
//     公网地址、超时与响应字节上限），不提供任意 URL 抓取面。
//   - Lens：只把图片转发给本机 loopback sidecar（Bearer token），sidecar
//     的视觉匹配卡片没有稳定外链，links 是 best-effort，可为空；status=ok
//     不等于识别出了作者。
//   - 证据：公开字段 + 哈希落盘到 DataDir/research-evidence/日期/，剥会话
//     参数、不存图片字节、不入库；落盘失败只告警，不影响响应。
//
// 证据等级口径（与批次复盘一致）：平台 API 返回的字段决定 confirmed/
// likely，检索（bing/wayback）只表示有线索；本包只产出事实字段，不替调用
// 方下"已核验"结论。
//
// 失败回退：单个 provider 失败只让本次查询 status=provider_unavailable，
// 不影响其他 provider，也不阻断调用方改走其他渠道；研究整体关闭时
// enabled=false（context 可读），查询与 Lens 显式返回禁用错误。
package research
