import { describe, expect, it } from 'vitest'
import { describeSystem, sampleNetwork, type NetworkSamples } from './system-monitor'

describe('维护页资源与流量展示', () => {
  it('零值是有效读数，缺失数据不伪装成零负载', () => {
    expect(describeSystem()).toMatchObject({ cpu: null, memory: null, diskPercent: null, uptime: '—' })
    expect(describeSystem({ cpuPercent: 0, memUsedBytes: 0, memTotalBytes: 100, uptimeSeconds: 61 })).toMatchObject({ cpu: 0, memory: 0, uptime: '1:01' })
  })
  it('保留全部存储路径及原有合计，不把不可采集项伪造为容量', () => {
    const view = describeSystem({ disks: [{ mount: '/data', usedBytes: 50, totalBytes: 100 }, { mount: '/media', usedBytes: 25, totalBytes: 100 }, { mount: '/missing' }] })
    expect(view.diskPercent).toBe(37.5)
    expect(view.diskFree).toBe('125 B')
    expect(view.disks.map(d => d.mount)).toEqual(['/data', '/media', '/missing'])
    expect(view.disks[2].percent).toBeNull()
  })
  it('累计为零仍开始采样；延迟响应按真实时间差计算，并忽略重复帧', () => {
    const first = sampleNetwork({ baseline: null, points: [] }, { netRxBytes: 0, netTxBytes: 0 }, 1000)
    const next = sampleNetwork(first, { netRxBytes: 6000, netTxBytes: 0 }, 7000)
    expect(next.points).toEqual([{ at: 7000, rx: 1000, tx: 0 }])
    expect(sampleNetwork(next, { netRxBytes: 6000, netTxBytes: 0 }, 7000)).toBe(next)
  })
  it('重启或计数器回退重新取基线，历史点不跨进程串联', () => {
    let samples = sampleNetwork({ baseline: null, points: [] }, { netRxBytes: 100, netTxBytes: 100, uptimeSeconds: 10 }, 1000)
    samples = sampleNetwork(samples, { netRxBytes: 200, netTxBytes: 200, uptimeSeconds: 12 }, 3000)
    expect(sampleNetwork(samples, { netRxBytes: 10, netTxBytes: 20, uptimeSeconds: 1 }, 5000).points).toEqual([])
  })
  it('窗口最多 60 点，断流后的旧采样不冒充最近两分钟', () => {
    let samples: NetworkSamples = { baseline: null, points: [] }
    for (let i = 0; i < 100; i++) samples = sampleNetwork(samples, { netRxBytes: i * 100, netTxBytes: i * 100 }, i * 2000)
    expect(samples.points).toHaveLength(60)
    samples = sampleNetwork(samples, { netRxBytes: 20000, netTxBytes: 20000 }, 400000)
    expect(samples.points).toHaveLength(1)
  })
})
