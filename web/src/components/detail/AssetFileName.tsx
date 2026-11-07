import { useState } from 'react'
import { Check, Copy } from 'lucide-react'
import { toast } from 'sonner'

/**
 * 详情页完整文件名行 + 复制。
 *
 * 详情标题（.detail-title）对 COS 资产显示作品名（cosWork），完整文件名
 * （作品编号/组图页码/扩展名）不一定可见；搜索语义按文件名等字段匹配后，
 * 用户需要能在详情核对并复制真实文件名。本组件只做展示与剪贴板动作，
 * 不做任何匹配/编号规则。
 */
export function AssetFileName({ fileName }: { fileName?: string | null }) {
  const [copied, setCopied] = useState(false)
  const name = fileName?.trim() ?? ''
  if (name === '') return null

  const copy = (): void => {
    const clipboard = navigator.clipboard
    if (!clipboard) {
      toast.error('当前环境不支持剪贴板，请手动选择文件名复制')
      return
    }
    void clipboard.writeText(name).then(() => {
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    }).catch(() => toast.error('复制文件名失败，请手动选择复制'))
  }

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 8,
        flexWrap: 'wrap',
        marginTop: 6,
        fontSize: 13,
        color: 'var(--qm-text-muted)',
      }}
    >
      <span style={{ overflowWrap: 'anywhere', wordBreak: 'break-all' }}>{name}</span>
      <button type="button" className="pill" onClick={copy} title="复制完整文件名">
        {copied ? <Check aria-hidden="true" size={14} /> : <Copy aria-hidden="true" size={14} />}
        {copied ? '已复制' : '复制文件名'}
      </button>
    </div>
  )
}
