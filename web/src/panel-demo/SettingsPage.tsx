/**
 * 设置页（原型）：分组表单卡，纯视觉演示——保存按钮只弹 toast，不写任何真实配置。
 */
import { useState } from 'react'
import { toast } from 'sonner'
import { Card } from '@/components/tremor/card'
import { Button } from '@/components/tremor/button'
import { Input } from '@/components/tremor/input'
import { Switch } from '@/components/tremor/switch'
import {
  APPEARANCE_NOTE,
  MOCK_SCAN_SETTINGS,
  MOCK_UPLOAD_SETTINGS,
  SETTING_LIMITS,
} from './mock'

/** 保存按钮文案与 toast 内容 */
const SETTINGS_TEXT = {
  groups: {
    scan: { title: '扫描', desc: '媒体库扫描与缩略图生成' },
    upload: { title: '上传', desc: '客户端上传行为约束' },
    appearance: { title: '界面', desc: '外观与主题' },
  },
  autoAccept: '自动接收上传',
  appearanceLabel: '主题模式',
  save: '保存设置',
  savedToast: '已保存（原型演示，未真实写入）',
} as const

export default function SettingsPage() {
  const [concurrency, setConcurrency] = useState(String(MOCK_SCAN_SETTINGS.concurrency))
  const [thumbLongEdge, setThumbLongEdge] = useState(String(MOCK_SCAN_SETTINGS.thumbLongEdge))
  const [sizeLimitMB, setSizeLimitMB] = useState(String(MOCK_UPLOAD_SETTINGS.sizeLimitMB))
  const [autoAccept, setAutoAccept] = useState(MOCK_UPLOAD_SETTINGS.autoAccept)

  return (
    <div className="space-y-6">
      {/* 扫描 */}
      <Card>
        <h2 className="text-sm font-semibold">{SETTINGS_TEXT.groups.scan.title}</h2>
        <p className="mt-0.5 text-xs text-muted-foreground">{SETTINGS_TEXT.groups.scan.desc}</p>
        <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
          <label className="space-y-1.5">
            <span className="text-sm text-muted-foreground">{SETTING_LIMITS.concurrency.label}</span>
            <Input
              type="number"
              value={concurrency}
              onChange={(e) => setConcurrency(e.target.value)}
              min={SETTING_LIMITS.concurrency.min}
              max={SETTING_LIMITS.concurrency.max}
            />
            <span className="block text-xs text-muted-foreground">{SETTING_LIMITS.concurrency.hint}</span>
          </label>
          <label className="space-y-1.5">
            <span className="text-sm text-muted-foreground">{SETTING_LIMITS.thumbLongEdge.label}</span>
            <Input
              type="number"
              value={thumbLongEdge}
              onChange={(e) => setThumbLongEdge(e.target.value)}
              min={SETTING_LIMITS.thumbLongEdge.min}
              max={SETTING_LIMITS.thumbLongEdge.max}
            />
            <span className="block text-xs text-muted-foreground">{SETTING_LIMITS.thumbLongEdge.hint}</span>
          </label>
        </div>
      </Card>

      {/* 上传 */}
      <Card>
        <h2 className="text-sm font-semibold">{SETTINGS_TEXT.groups.upload.title}</h2>
        <p className="mt-0.5 text-xs text-muted-foreground">{SETTINGS_TEXT.groups.upload.desc}</p>
        <div className="mt-4 space-y-4">
          <label className="block space-y-1.5 sm:max-w-xs">
            <span className="text-sm text-muted-foreground">{SETTING_LIMITS.sizeLimitMB.label}</span>
            <Input
              type="number"
              value={sizeLimitMB}
              onChange={(e) => setSizeLimitMB(e.target.value)}
              min={SETTING_LIMITS.sizeLimitMB.min}
              max={SETTING_LIMITS.sizeLimitMB.max}
            />
            <span className="block text-xs text-muted-foreground">{SETTING_LIMITS.sizeLimitMB.hint}</span>
          </label>
          <div className="flex items-center gap-3">
            <Switch id="panel-demo-auto-accept" checked={autoAccept} onCheckedChange={setAutoAccept} />
            <label htmlFor="panel-demo-auto-accept" className="text-sm">
              {SETTINGS_TEXT.autoAccept}
            </label>
          </div>
        </div>
      </Card>

      {/* 界面：跟随系统主题，占位不可改 */}
      <Card>
        <h2 className="text-sm font-semibold">{SETTINGS_TEXT.groups.appearance.title}</h2>
        <p className="mt-0.5 text-xs text-muted-foreground">{SETTINGS_TEXT.groups.appearance.desc}</p>
        <div className="mt-4 flex items-center justify-between gap-4">
          <span className="text-sm">{SETTINGS_TEXT.appearanceLabel}</span>
          <span className="text-sm text-muted-foreground">{APPEARANCE_NOTE}</span>
        </div>
      </Card>

      {/* 保存：原型只弹 toast */}
      <div className="flex justify-end">
        <Button type="button" onClick={() => toast.success(SETTINGS_TEXT.savedToast)}>
          {SETTINGS_TEXT.save}
        </Button>
      </div>
    </div>
  )
}
