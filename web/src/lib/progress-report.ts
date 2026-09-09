/**
 * 播放进度上报纯逻辑（ADR-0017：vitest 只测 src/lib 纯函数，不装 @testing-library/react）。
 *
 * 为什么抽离：hooks/use-progress.ts 同时承担 React 生命周期、TanStack mutation 与
 * 「谁的进度、何时该发」规则——规则若留在 hook 内则无法在 node 环境单测，回归只能靠人肉。
 * 本文件只放无 IO、无 React 的判定；hook 侧行为语义不变，仅改为调用本文件。
 */

/**
 * 进度心跳节流间隔（毫秒）：5s——严于协议建议值 10s（api/openapi.yaml
 * PUT /assets/{id}/progress description「建议 10s 间隔」，协议允许更密）。
 * 协议侧建议值改动须同步此处，反之亦然。
 */
export const PROGRESS_REPORT_INTERVAL_MS = 5000

/**
 * 最后已知播放位置与「它所属的资产」配对。
 * 为什么必须配对：详情→详情导航时组件不卸载，闭包里的 assetId 会切到新资产；
 * 若只存 position，卸载/flush 会把旧资产位置写进新资产（跨资产数据污染）。
 */
export interface LastPositionPair {
  assetId: string
  positionSeconds: number
}

/**
 * tick 是否应立即上报：距上次上报已满节流间隔则发。
 * lastSentAt=0（首轮 / 切资产重置后）时 now-0 ≥ interval 成立，首轮 tick 即发——
 * 与 hook 原行为一致，不改成「首轮不发」。
 */
export function shouldSendProgress(
  lastSentAt: number,
  now: number,
  intervalMs: number = PROGRESS_REPORT_INTERVAL_MS,
): boolean {
  return now - lastSentAt >= intervalMs
}

/**
 * 切资产（详情→详情）补报判定：仅当配对仍归属旧资产时补报旧位置。
 * 返回 null = 不补报（无配对，或配对已不属于 prevAssetId——防御性，正常不会漂移）。
 */
export function resolveSwitchFlush(
  last: LastPositionPair | null,
  prevAssetId: string,
): LastPositionPair | null {
  if (last && last.assetId === prevAssetId) {
    return { assetId: last.assetId, positionSeconds: last.positionSeconds }
  }
  return null
}

/**
 * flush 应上报的位置（秒）：
 * - 显式 positionSeconds 优先（暂停瞬间播放器可带最新位置，不受配对限制）
 * - 否则仅当配对归属当前资产时取 last.positionSeconds
 * - 都不满足 → null（不上报，防旧资产位置写进新资产）
 */
export function resolveFlushPosition(
  explicitPositionSeconds: number | undefined,
  last: LastPositionPair | null,
  currentAssetId: string,
): number | null {
  if (explicitPositionSeconds !== undefined) return explicitPositionSeconds
  if (last && last.assetId === currentAssetId) return last.positionSeconds
  return null
}

/**
 * 卸载补报目标：有配对则原样报回配对资产（谁的位置报给谁；不受 enabled 门限制——
 * 切资产 effect 已先补旧资产，卸载时 last 已清空或归属当前资产）。
 */
export function resolveUnloadFlush(last: LastPositionPair | null): LastPositionPair | null {
  return last ? { assetId: last.assetId, positionSeconds: last.positionSeconds } : null
}

/**
 * 时间轴标签按 timeMillis 升序（进度条打点渲染与回看跳转依赖稳定时间序）。
 * 缺 timeMillis / null 视为 0 排前；返回新数组不改入参（select 需保持引用语义安全）。
 */
export function sortTimelineTagsByTime<T extends { timeMillis?: number | null }>(
  tags: readonly T[],
): T[] {
  return [...tags].sort((a, b) => (a.timeMillis ?? 0) - (b.timeMillis ?? 0))
}
