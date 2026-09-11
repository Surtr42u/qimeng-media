/**
 * 详情页停留时长上报（kind=dwell；DOMAIN_RULES §5「浏览时长 = 详情页停留秒数」）。
 * 图片与视频详情页通用（打点挂详情页层级，与媒体类型无关）。
 *
 * 服务端口径（server/internal/httpapi/engagement.go，与本 hook 语义互为镜像）：
 * dwell 是累加量、逐条插入不去重——客户端必须保证一次停留恰好产生一条事件，
 * 重复 flush 会把同一段停留时间算两遍。防重闸门：停留段由 segmentRef 单点
 * 持有，flush 取走即置空，同段内任何后续 flush 都是 no-op。
 *
 * 停留段生命周期（一开一闭 = 恰好一条 dwell）：
 * - 开：进入详情页 / 详情→详情切资产 / 页面从隐藏回到可见；
 * - 闭：离开页面（卸载）/ 切资产 / 页面隐藏（切标签页、最小化、锁屏）。
 * 隐藏期间不计入停留（后台挂着的页面不算「在看」），回可见即开新段。
 */

import { useCallback, useEffect, useRef } from 'react'
import { useReportView } from '@/hooks/use-assets'
import { ensureSessionId } from '@/hooks/use-session'
import { decideDwellSegment } from '@/lib/engagement-reporting'

/** 一段正在计时的停留（归属资产 + 段起点）；null = 当前无打开的停留段 */
interface DwellSegment {
  assetId: string
  startedAtMs: number
}

/**
 * 停留时长打点：进入计时，离开/页面隐藏时 flush 恰好一条 dwell 事件。
 * 组件零参与（铁律 7）——挂一次 useDwellReport(assetId) 即完成全部接线。
 */
export function useDwellReport(assetId: string | undefined) {
  const reportView = useReportView()

  // 停留段配对归属资产：详情→详情导航（同路由参数变化，组件不卸载）时防
  // 旧段时长写进新资产（跨资产数据污染），同 hooks/use-progress.ts 约定
  const segmentRef = useRef<DwellSegment | null>(null)
  const assetIdRef = useRef(assetId)

  // latest-ref 模式（lint 合规：ref 更新放 effect）：flush/begin 无依赖，
  // 经 ref 间接调用 mutate，避免对 useMutation result 对象的依赖抖动
  const mutateRef = useRef(reportView.mutate)
  useEffect(() => {
    mutateRef.current = reportView.mutate
  })

  /** 关闭当前停留段并上报（同段重复调用 no-op——段被取走即置空，禁重复 flush）。
   * 段级口径（DOMAIN_RULES §5）：不足 1s 的停留段不上报（W6 #45 回退——浏览
   * open 事件已计入访问，零值段冗余），非有限毫秒数（脏数据）由
   * decideDwellSegment 一并防御拦截。 */
  const flush = useCallback(() => {
    const segment = segmentRef.current
    segmentRef.current = null
    if (!segment) return
    const decision = decideDwellSegment(Date.now() - segment.startedAtMs)
    if (!decision.report) return
    mutateRef.current({
      assetId: segment.assetId,
      kind: 'dwell',
      startedAt: new Date(segment.startedAtMs).toISOString(),
      sessionId: ensureSessionId(),
      seconds: decision.seconds,
    })
  }, [])

  /** 打开新的停留段（计时起点 = 现在；assetId 未就绪时不打开） */
  const begin = useCallback(() => {
    const id = assetIdRef.current
    if (!id) return
    segmentRef.current = { assetId: id, startedAtMs: Date.now() }
  }, [])

  // 详情→详情切资产：先结旧段（报给旧资产）再开新段，旧段不跨资产归属
  useEffect(() => {
    if (assetIdRef.current === assetId) return
    flush()
    assetIdRef.current = assetId
    begin()
  }, [assetId, begin, flush])

  // 进入即计时；卸载（离开页面）= 关段上报
  useEffect(() => {
    begin()
    return flush
  }, [begin, flush])

  // 页面隐藏 = 本次停留结束（flush 恰好一条），回到可见开新段；
  // pagehide 兜底个别关页路径 visibilitychange 未及触发的场景
  // （flush 幂等，重复触发无害）
  useEffect(() => {
    const onVisibilityChange = () => {
      if (document.visibilityState === 'hidden') flush()
      else begin()
    }
    const onPageHide = () => flush()
    document.addEventListener('visibilitychange', onVisibilityChange)
    window.addEventListener('pagehide', onPageHide)
    return () => {
      document.removeEventListener('visibilitychange', onVisibilityChange)
      window.removeEventListener('pagehide', onPageHide)
    }
  }, [begin, flush])
}
