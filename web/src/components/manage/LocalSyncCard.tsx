import { toast } from 'sonner'
import type { LocalSyncItem, LocalSyncStatus } from '@/api/generated'
import { batchFailureReason } from '@/lib/batch'
import { LOCALE_ZH } from '@/lib/constants'
import { useLocalSyncStatus, useTriggerLocalSync } from '@/hooks/use-local-sync'

/**
 * 本机同步卡（维护页）：通道状态徽标 + 信息行 + 通道警示条 + 同步源条目
 * 三段列表（失败/忽略/最近成功）+「立即同步」手动触发。
 * 数据全走 use-local-sync hooks（铁律 7 / ADR-0008 逻辑 UI 分层），本组件只渲染。
 * 错误消息统一走 batchFailureReason（与批量操作同口径）：unwrapSdkResult 抛的是
 * {code,message,status} 错误体非 Error 实例，instanceof 模式会落成 "[object Object]"。
 */

/** 三段列表各段最多展示条数（服务端 items 上限 500，全渲染会淹没维护页） */
const LIST_PREVIEW_LIMIT = 50

/** 状态徽标（标题右侧）：启用态用成功色、未启用灰、上传闸门暂停黄（token 语义见 prototype.css） */
function StatusBadges({ status }: { status: LocalSyncStatus }) {
  return (
    <span className="ls-badges">
      <span className={`ls-badge ${status.enabled ? 'ls-badge--on' : 'ls-badge--off'}`}>
        {status.enabled ? '已启用' : '未启用'}
      </span>
      {status.paused && <span className="ls-badge ls-badge--paused">上传闸门暂停中</span>}
    </span>
  )
}

/** 每段条目行：主行 = 路径（synced 追加「→ 库名」），副行/原因按段语义着色 */
function ItemRows({ rows, kind }: { rows: LocalSyncItem[]; kind: 'failed' | 'ignored' | 'synced' }) {
  const shown = rows.slice(0, LIST_PREVIEW_LIMIT)
  return (
    <div className="ls-rows">
      {shown.map((it, i) => {
        const sub =
          kind === 'failed'
            ? [it.libraryName, it.attempts != null ? `尝试 ${it.attempts} 次` : ''].filter(Boolean).join(' · ')
            : ''
        return (
          <div className="ls-row" key={`${it.path ?? 'item'}-${i}`}>
            <span className={`ls-row-path${kind === 'ignored' ? ' ls-row-path--muted' : ''}`}>
              {it.path ?? '—'}
              {kind === 'synced' && it.libraryName ? ` → ${it.libraryName}` : ''}
            </span>
            {sub !== '' && <span className="ls-row-sub">{sub}</span>}
            {it.error && (
              <span className={kind === 'failed' ? 'ls-row-err' : 'ls-row-muted'}>{it.error}</span>
            )}
          </div>
        )
      })}
      {rows.length > LIST_PREVIEW_LIMIT && (
        <p className="ls-more">+{rows.length - LIST_PREVIEW_LIMIT} 更多</p>
      )}
    </div>
  )
}

/** 列表区三段（失败/忽略/最近成功）：各段仅有内容时渲染；items 与 recentSynced 均空出空态 */
function ListSections({ items, recentSynced }: { items: LocalSyncItem[]; recentSynced: LocalSyncItem[] }) {
  if (items.length === 0 && recentSynced.length === 0) {
    return <p className="grid-empty">同步源内暂无待处理文件</p>
  }
  const failed = items.filter((it) => it.state === 'failed')
  const ignored = items.filter((it) => it.state === 'ignored')
  // 最近成功保证「最近在前」：服务端环形保留顺序未承诺，按最后尝试时刻降序兜底
  const synced = [...recentSynced].sort(
    (a, b) => Date.parse(b.lastAttemptAt ?? '') - Date.parse(a.lastAttemptAt ?? ''),
  )
  return (
    <>
      {failed.length > 0 && (
        <div className="ls-sec">
          <h4 className="ls-sec-title">失败（{failed.length}）</h4>
          <ItemRows rows={failed} kind="failed" />
        </div>
      )}
      {ignored.length > 0 && (
        <div className="ls-sec">
          <h4 className="ls-sec-title">忽略（{ignored.length}）</h4>
          <ItemRows rows={ignored} kind="ignored" />
        </div>
      )}
      {synced.length > 0 && (
        <div className="ls-sec">
          <h4 className="ls-sec-title">最近成功（{synced.length}）</h4>
          <ItemRows rows={synced} kind="synced" />
        </div>
      )}
    </>
  )
}

/** 本机同步卡（维护页维护工具区，渲染在库文件快照备份卡之后） */
export function LocalSyncCard() {
  const { data: status, isLoading } = useLocalSyncStatus()
  const trigger = useTriggerLocalSync()

  const enabled = status?.enabled === true
  const root = status?.root ?? ''
  const lastError = status?.lastError ?? ''
  const counts = status?.counts
  const items = status?.items ?? []
  const recentSynced = status?.recentSynced ?? []
  /** 未配置同步根：按钮禁用并提示服务端配置入口 */
  const unconfigured = !enabled && root === ''
  const triggerDisabled = trigger.isPending || !enabled || lastError !== ''

  const onTrigger = (): void => {
    trigger.mutate(undefined, {
      onSuccess: () => toast.success('已触发同步扫描，结果稍后自动刷新'),
      onError: (err) => toast.error(batchFailureReason(err)),
    })
  }

  return (
    <div className="chart-card">
      <h3>
        本机同步
        {status && <StatusBadges status={status} />}
      </h3>
      <p>同步根目录文件自动匹配入库 · 媒体走直传同款校验，作者 TXT 走片段导入</p>
      {status ? (
        <>
          <div className="ls-info">
            <span>同步根：<b>{root || '未配置'}</b></span>
            <span>轮询周期：每 <b>{status.intervalSeconds ?? '—'}</b> 秒</span>
            <span>
              上一轮扫描：
              <b>{status.lastCycleAt ? new Date(status.lastCycleAt).toLocaleString(LOCALE_ZH) : '从未运行'}</b>
            </span>
            <span>累计已同步：<b>{status.syncedTotal ?? 0}</b></span>
          </div>
          {lastError !== '' && (
            <div className="ls-warn" role="alert">通道异常：{lastError}</div>
          )}
          <p className="ls-summary">
            等待稳定 {counts?.waitingStable ?? 0} · 失败 {counts?.failed ?? 0} · 忽略 {counts?.ignored ?? 0}
          </p>
          <ListSections items={items} recentSynced={recentSynced} />
          <div className="settings-actions">
            <button className="save-btn" type="button" onClick={onTrigger} disabled={triggerDisabled}>
              {trigger.isPending ? '触发中…' : '立即同步'}
            </button>
            {unconfigured && (
              <span className="ls-hint">未配置同步根（服务端 QIMENG_LOCAL_SYNC_ROOT）</span>
            )}
          </div>
        </>
      ) : (
        <p className="grid-empty">{isLoading ? '通道状态加载中…' : '通道状态暂不可用'}</p>
      )}
    </div>
  )
}
