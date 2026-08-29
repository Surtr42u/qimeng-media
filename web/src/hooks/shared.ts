/**
 * hooks 层共享辅助。
 * 页面组件禁止直接 import SDK（web/README.md 分层纪律）——数据访问一律经 hooks。
 */

/**
 * 对象 → 稳定序列化字符串（query key 用）。
 * 为什么必须稳定：React Query 用引用/结构比较 hash key——两次渲染构造的
 * filters 对象键序不同会 hash 出不同 key 造成缓存漂移与重复请求；
 * 这里按键名排序后序列化，键序无关。
 */
export function stableObjectKey(value: object | undefined): string {
  if (!value) return '{}'
  // 接口/类型字面量没有 index signature，用断言后按键名遍历（仅读 keys + 取值，无运行时风险）
  const record = value as Record<string, unknown>
  return JSON.stringify(
    Object.keys(record)
      .sort()
      .map((key) => [key, record[key]]),
  )
}
