/**
 * 打点本地账的补发触发（任务L L5「本地优先」的 Web 侧恢复通道，与 App 端三通道同构）：
 * - 挂载即补发一轮（页面刷新/重开后把离线积压送出，补发路径）；
 * - 浏览器 'online' 事件（断网恢复的高概率时刻，对应 App 的网络恢复语义）；
 * - 页面回可见（切回标签页，对应 App 回前台 ON_START）；
 * - 周期兜底（FLUSH_INTERVAL_MS，对应 App WorkManager 15min 兜底的页内等价物）。
 *
 * 全部触发收敛到账本 scheduleFlush（退避闸+单飞闸在账本内，见 lib/event-ledger），
 * 本 hook 只做事件转发——组件零业务规则（铁律 7）。挂载点=AuthGate（登录后才有
 * 有效 token；未登录期间的失败保留原行，登录后触发通道自然补发）。
 */

import { useEffect } from 'react'
import { getEventLedger } from '@/lib/ledger-instance'

/** 周期兜底间隔（毫秒）：与账本全失败退避闸 FLUSH_FAILURE_BACKOFF_MS 同量级——
 * 闸开着的触发是 no-op，这里只负责闸关后的兜底重试节奏 */
const FLUSH_INTERVAL_MS = 60_000

export function useEventLedgerFlusher(): void {
  useEffect(() => {
    let disposed = false

    const trigger = () => {
      if (disposed) return
      void getEventLedger()
        .then((ledger) => ledger.scheduleFlush())
        .catch(() => {
          // 装配失败（存储不可用且降级也未建账）属极端环境：静默——打点尽力而为
        })
    }

    // 挂载即补发（离线积压的重开页面路径）
    trigger()
    window.addEventListener('online', trigger)
    document.addEventListener('visibilitychange', trigger)
    const timer = window.setInterval(trigger, FLUSH_INTERVAL_MS)
    return () => {
      disposed = true
      window.removeEventListener('online', trigger)
      document.removeEventListener('visibilitychange', trigger)
      window.clearInterval(timer)
    }
  }, [])
}
