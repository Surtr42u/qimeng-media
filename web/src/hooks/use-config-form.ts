import { useRef, useState } from 'react'
import { toast } from 'sonner'
import type { ClientConfig } from '@/api/generated'
import { batchFailureReason } from '@/lib/batch'
import { CONFIG_BOUNDS, DEFAULT_CLIENT_CONFIG, mergeClientConfig, useConfig, useSaveConfig } from './use-config'

/** 全量配置只有一份草稿；提交快照只确认自己的修订，不清除保存期间的新输入。 */
export function useConfigForm() {
  const { data } = useConfig()
  const mutation = useSaveConfig()
  const revision = useRef(0)
  const [draft, setDraft] = useState<ClientConfig | null>(null)
  const cfg = draft ?? (data ? mergeClientConfig(data) : DEFAULT_CLIENT_CONFIG)

  function edit(next: ClientConfig) {
    revision.current++
    setDraft(next)
  }

  function setNumber(key: keyof ClientConfig['scan'], raw: string) {
    const n = Number(raw)
    if (raw.trim() === '' || !Number.isFinite(n)) return
    edit({ ...cfg, scan: { ...cfg.scan, [key]: n } })
  }

  function save() {
    if (mutation.isPending) return
    const fields = [
      ['workers', cfg.scan.workers, '扫描并发数', ''],
      ['thumbEdge', cfg.scan.thumbEdge, '缩略图长边', ' px'],
    ] as const
    for (const [key, value, label, unit] of fields) {
      const { min, max } = CONFIG_BOUNDS[key]
      if (!Number.isInteger(value) || value < min || value > max) {
        toast.error(`${label}须为 ${min}–${max}${unit} 范围内的整数`)
        return
      }
    }
    const submittedRevision = revision.current
    const scanChanged = data !== undefined &&
      (cfg.scan.workers !== data.scan.workers || cfg.scan.thumbEdge !== data.scan.thumbEdge)
    mutation.mutate(cfg, {
      onSuccess: () => {
        if (revision.current === submittedRevision) setDraft(null)
        toast.success(scanChanged ? '设置已保存，并发设置用于后续扫描；缩略图长边仍为预留' : '设置已保存')
      },
      onError: (err) => toast.error(`设置保存失败：${batchFailureReason(err)}`),
    })
  }

  return {
    cfg,
    isPending: mutation.isPending,
    save,
    setScan: setNumber,
    setAutoAccept: (checked: boolean) => edit({ ...cfg, upload: { ...cfg.upload, autoAccept: checked } }),
  }
}
