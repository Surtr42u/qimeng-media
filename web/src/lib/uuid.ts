/**
 * 随机 UUID v4（单一来源）。
 *
 * crypto.randomUUID 只在安全上下文（HTTPS / localhost）暴露，局域网 IP 直连
 * （http://192.168.x.x）的手机浏览器没有该 API，直接调用会让整个应用白屏崩溃；
 * 降级用 getRandomValues 拼 v4——它在非安全上下文同样可用。
 *
 * 消费方：每标签页 sessionId（use-session）与打点本地账的 clientEventId
 * （lib/ledger-instance，任务L L5 幂等键）——两处语义同为「客户端生成的 UUID」，
 * 生成逻辑禁止第二份实现（代码卫生约束 2）。
 */
export function randomUUID(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return '10000000-1000-4000-8000-100000000000'.replace(/[018]/g, (c) =>
    (+c ^ (crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (+c / 4)))).toString(16),
  )
}
