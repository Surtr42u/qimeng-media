/**
 * 性能监控区块（原型）：服务器硬件监控假象，供维护页「性能监控」区块引用。
 *
 * mock 定时刷新：setInterval 2s 波动快照，组件卸载清理定时器。
 * 圆环挂载时数值从 0 平滑滚到目标；网络负载图为上下行双系列实时曲线
 * （60 点 × 2s = 最近 2 分钟）。
 * 原独立性能页已并入维护页（V4 结构调整），本文件仅作内容组件导出。
 */
import { useEffect, useState } from 'react'
import { Card } from '@/components/tremor/card'
import { ProgressCircle } from '@/components/tremor/progress-circle'
import { AreaChart } from '@/components/tremor/area-chart'
import { MetricCardView, Reveal } from './widgets'
import { useCountUp } from './use-count-up'
import {
  MOCK_METRIC_LABELS,
  MOCK_METRIC_SUBS,
  MOCK_METRIC_TRENDS,
  MOCK_NET_PEAK,
  MOCK_PERF_INITIAL,
  NET_SERIES_META,
  SAMPLE_INTERVAL_MS,
  initNetSeries,
  jitterPerf,
  mapNetPoints,
  nextNetSample,
  type GaugeInfo,
  type MetricCard,
  type NetSample,
  type PerfSnapshot,
} from './mock'

/** 圆环尺寸（px） */
const GAUGE_RADIUS = 44
const GAUGE_STROKE = 7

/** 圆环卡：占比圆环 + 标注（label 与 details 均来自 mock） */
function GaugeCard({ gauge }: { gauge: GaugeInfo }) {
  // value 由 state 动画驱动：挂载从 0 滚到目标，300ms ease-out（2s 波动时从当前值续滚）
  const animated = useCountUp(gauge.usage, 300)
  return (
    <Card className="flex flex-col items-center gap-3 py-5">
      {/* 组件自带 CSS transition 关掉，避免与 rAF 滚动叠加双重动画 */}
      <ProgressCircle value={animated} radius={GAUGE_RADIUS} strokeWidth={GAUGE_STROKE} showAnimation={false}>
        <span className="text-sm font-semibold tabular-nums">{Math.round(animated)}%</span>
      </ProgressCircle>
      <div className="text-center">
        <p className="text-sm font-medium">{gauge.label}</p>
        {gauge.details.map((line) => (
          <p key={line} className="mt-0.5 text-xs text-muted-foreground">
            {line}
          </p>
        ))}
      </div>
    </Card>
  )
}

/** 性能监控区块：圆环区 + 指标卡行 + 网络负载实时曲线（维护页上半部分） */
export default function PerfMonitors() {
  const [perf, setPerf] = useState<PerfSnapshot>(MOCK_PERF_INITIAL)
  // 网络负载序列：初始即有完整 60 点窗口（首屏曲线不空白）
  const [netSeries, setNetSeries] = useState<NetSample[]>(() => initNetSeries())

  // mock 实时感：2s 一帧——快照波动 + 网络序列窗口右移一格；卸载统一清理
  useEffect(() => {
    const timer = window.setInterval(() => {
      setPerf((prev) => jitterPerf(prev))
      setNetSeries((prev) => [...prev.slice(1), nextNetSample(prev[prev.length - 1])])
    }, SAMPLE_INTERVAL_MS)
    return () => window.clearInterval(timer)
  }, [])

  // 4 张指标卡的实时 value（趋势 / 峰值等静态部分用 mock）
  const metrics: MetricCard[] = [
    {
      label: MOCK_METRIC_LABELS.netDown,
      value: perf.netDownMBs,
      decimals: 1,
      suffix: 'MB/s',
      sub: `会话累计 ↓ ${perf.sessionDownGB.toFixed(2)} GB · 峰值 ${MOCK_NET_PEAK.downMBs} MB/s`,
      ...MOCK_METRIC_TRENDS[0],
    },
    {
      label: MOCK_METRIC_LABELS.netUp,
      value: perf.netUpMBs,
      decimals: 1,
      suffix: 'MB/s',
      sub: `会话累计 ↑ ${perf.sessionUpGB.toFixed(2)} GB · 峰值 ${MOCK_NET_PEAK.upMBs} MB/s`,
      ...MOCK_METRIC_TRENDS[1],
    },
    {
      label: MOCK_METRIC_LABELS.storage,
      value: perf.storageUsedTB,
      decimals: 1,
      suffix: `/ ${perf.storageTotalTB} TB`,
      sub: MOCK_METRIC_SUBS.storage,
      ...MOCK_METRIC_TRENDS[2],
    },
    {
      label: MOCK_METRIC_LABELS.uptime,
      value: perf.uptimeDays,
      suffix: `天 ${perf.uptimeHours} 时`,
      sub: MOCK_METRIC_SUBS.uptime,
      ...MOCK_METRIC_TRENDS[3],
    },
  ]

  return (
    <div className="space-y-6">
      {/* 圆环区：自适应横排，不够自动换行 */}
      <Reveal index={0}>
        <section
          aria-label="硬件占用圆环"
          className="grid grid-cols-[repeat(auto-fit,minmax(13rem,1fr))] gap-4"
        >
          <GaugeCard gauge={perf.cpu} />
          <GaugeCard gauge={perf.memory} />
          {perf.disks.map((disk) => (
            <GaugeCard key={disk.label} gauge={disk} />
          ))}
        </section>
      </Reveal>

      {/* 指标卡行（每卡带小字补充参数：会话累计 / 峰值等） */}
      <Reveal index={1}>
        <section aria-label="关键指标" className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {metrics.map((metric) => (
            <MetricCardView key={metric.label} {...metric} />
          ))}
        </section>
      </Reveal>

      {/* 网络负载 · 实时：统计浏览使用的上下行流量，60 点 × 2s ≈ 最近 2 分钟 */}
      <Reveal index={2}>
        <Card>
          <div className="mb-4">
            <h2 className="text-sm font-semibold">网络负载 · 实时</h2>
            <p className="mt-0.5 text-xs text-muted-foreground">
              浏览流量上下行曲线 · {SAMPLE_INTERVAL_MS / 1000}s 采样 · 最近 2 分钟 · mock 数据
            </p>
          </div>
          <AreaChart
            data={mapNetPoints(netSeries)}
            index={NET_SERIES_META.index}
            categories={[...NET_SERIES_META.categories]}
            colors={[...NET_SERIES_META.colors]}
            yAxisWidth={56}
            valueFormatter={(value) => `${value.toFixed(1)} MB/s`}
            className="h-64"
          />
        </Card>
      </Reveal>
    </div>
  )
}
