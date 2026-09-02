import { FolderMonitorIcon, TrashIcon } from '@/components/shell/icons'

/** 圆环卡数据（SVG stroke-dasharray 数值照搬原型，阶段 A 无实时刷新） */
const GAUGES = [
  { label: 'CPU', sub: '8 核 · 3.2 GHz', arc: 'arc-1', dash: '184 276' },
  { label: '内存', sub: '6.8 / 16 GB', arc: 'arc-2', dash: '121 276' },
  { label: '系统盘', sub: '467 / 512 GB', arc: 'arc-1', dash: '207 276' },
  { label: '媒体盘', sub: '3.2 / 8 TB', arc: 'arc-2', dash: '162 276' },
]

const METRICS = [
  { label: '下行', value: '12.4 MB/s', sub: '会话累计 ↓ 4.82 GB · 峰值 86 MB/s' },
  { label: '上行', value: '1.8 MB/s', sub: '会话累计 ↑ 0.66 GB · 峰值 24 MB/s' },
  { label: '存储', value: '6.9 / 30 TB', sub: '媒体库 128.4 GB' },
  { label: '运行时长', value: '32 天', sub: '32 天 9 时' },
]

const LOG_ROWS = [
  { time: '2026-08-31 21:14', level: 'warn', levelText: '警告', source: 'web', message: '缩略图解码超时，已重试' },
  { time: '2026-08-31 18:02', level: 'info', levelText: '信息', source: 'app', message: '后台同步完成，共 36 项' },
  { time: '2026-08-30 09:41', level: 'err', levelText: '错误', source: 'web', message: '文件上传中断（连接重置）' },
  { time: '2026-08-30 02:17', level: 'info', levelText: '信息', source: 'server', message: '定时扫描完成，耗时 412s' },
]

/**
 * 维护页（原型 #page-maintenance 移植）。
 * 纯 mock 静态展示；库管理入口按交接说明不在此页添加（后续任务单独做）。
 */
export default function MaintenancePage() {
  return (
    <div className="page" id="page-maintenance">
      <div className="page-head">
        <h2>性能监控</h2>
        <p>服务器硬件实时状态 · mock 数据（2s 自动刷新占位）</p>
      </div>
      <div className="gauge-grid">
        {GAUGES.map((g) => (
          <div key={g.label} className="gauge-card">
            <svg className="gauge" viewBox="0 0 100 100" aria-hidden="true">
              <circle cx="50" cy="50" r="44" className="ring" />
              <circle cx="50" cy="50" r="44" className={`${g.arc} gauge-arc`} strokeDasharray={g.dash} />
            </svg>
            <p className="gauge-label">{g.label}</p>
            <p className="gauge-sub">{g.sub}</p>
          </div>
        ))}
      </div>
      <div className="metric-grid">
        {METRICS.map((m) => (
          <div key={m.label} className="metric-card">
            <p className="m-label">{m.label}</p>
            <p className="m-value">{m.value}</p>
            <p className="m-sub">{m.sub}</p>
          </div>
        ))}
      </div>
      <div className="chart-card">
        <h3>网络负载 · 实时</h3>
        <p>浏览流量上下行曲线 · 2s 采样 · 最近 2 分钟 · mock 数据</p>
        <svg className="trend-svg" viewBox="0 0 600 200" preserveAspectRatio="none" aria-label="网络负载">
          <polyline
            points="0,120 30,96 60,106 90,74 120,88 150,58 180,80 210,64 240,92 270,70 300,52 330,78 360,60 390,84 420,66 450,48 480,72 510,60 540,82 570,64 600,76"
            className="line-a"
          />
          <polyline
            points="0,170 30,164 60,168 90,158 120,166 150,156 180,164 210,160 240,166 270,158 300,162 330,156 360,164 390,158 420,162 450,156 480,164 510,160 540,166 570,158 600,162"
            className="line-b"
          />
        </svg>
      </div>
      <div className="page-head">
        <h2>维护工具</h2>
        <p>文件管理与客户端异常排查</p>
      </div>
      <div className="entry-grid">
        <div className="entry-card">
          <div className="entry-head">
            <FolderMonitorIcon />
            <h3>文件管理</h3>
          </div>
          <p>资产文件浏览、整理与去重（接入点预留）</p>
          <span className="entry-badge">接入点预留</span>
        </div>
        <div className="entry-card">
          <div className="entry-head">
            <TrashIcon />
            <h3>回收站</h3>
          </div>
          <p className="entry-count">
            <b>36</b> 条待处理
          </p>
          <div className="progress">
            <i style={{ width: '18%' }} />
          </div>
          <p className="entry-sub">占用 0.9 GB · 容量 5 GB</p>
        </div>
      </div>
      <div className="chart-card">
        <h3>用户端崩溃 / 错误日志</h3>
        <p>最近上报的客户端异常（mock 数据）</p>
        <table className="log-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>级别</th>
              <th>来源</th>
              <th>消息摘要</th>
            </tr>
          </thead>
          <tbody>
            {LOG_ROWS.map((r) => (
              <tr key={r.time}>
                <td>{r.time}</td>
                <td>
                  <span className={`log-badge ${r.level}`}>{r.levelText}</span>
                </td>
                <td>{r.source}</td>
                <td>{r.message}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
