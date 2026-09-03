import { useState } from 'react'
import { toast } from 'sonner'
import type { RecommendPrefs } from '@/api/generated'
import {
  DEFAULT_PREFS,
  PREFS_KEYS,
  RECOMMEND_PRESETS,
  usePrefs,
  useSavePrefs,
} from '@/hooks/use-prefs'

/**
 * 设置页（原型 #page-settings 移植）。
 * 阶段 B：新增「推荐偏好」卡（9 维权重，GET/PUT /recommendations/prefs，全量整体替换）——
 * 预设点击即保存；滑杆拖动后点「保存」才 PUT；GET 失败兜底「均衡推荐」默认（DOMAIN_RULES §1.3）。
 * 其余三卡（扫描/上传/界面）保持阶段 A 演示行为：保存只显示提示、不真实写入。
 */

/** 9 维中文标签（顺序与 PREFS_KEYS 一致；维度名照 DOMAIN_RULES §1.3） */
const PREFS_LABELS: Record<(typeof PREFS_KEYS)[number], string> = {
  tagRelevance: '标签相关度',
  tagCollection: '标签收集度',
  engagement: '互动权重',
  recency: '时效性',
  likeScore: '点赞偏好',
  discovery: '探索性',
  freshness: '新鲜度',
  browseDepth: '浏览深度',
  maxRandom: '随机打散',
}

export default function SettingsPage() {
  const [saved, setSaved] = useState(false)
  const { data } = usePrefs()
  const savePrefs = useSavePrefs()

  // 草稿态：null = 未改过（显示服务端值，缺字段/Miss 时兜底默认）；拖动/点预设后转本地草稿
  const [draft, setDraft] = useState<RecommendPrefs | null>(null)
  const prefs: RecommendPrefs = draft ?? (data ? { ...DEFAULT_PREFS, ...data } : DEFAULT_PREFS)

  const setPref = (key: (typeof PREFS_KEYS)[number], value: number): void =>
    setDraft({ ...prefs, [key]: value })

  const doSave = (p: RecommendPrefs): void => {
    savePrefs.mutate(p, {
      onSuccess: () => toast.success('推荐偏好已保存'),
      onError: (err) =>
        toast.error(`推荐偏好保存失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  // 预设点击即保存（含「均衡推荐」默认；方案拍板语义）
  const applyPreset = (p: RecommendPrefs): void => {
    setDraft(p)
    doSave(p)
  }

  const isActivePreset = (p: RecommendPrefs): boolean =>
    PREFS_KEYS.every((k) => (prefs[k] ?? 0) === (p[k] ?? 0))

  const dirty = PREFS_KEYS.some((k) => (prefs[k] ?? 0) !== ((data?.[k] ?? DEFAULT_PREFS[k]) ?? 0))

  return (
    <div className="page" id="page-settings">
      <div className="settings-card">
        <h3>扫描</h3>
        <p>媒体库扫描与缩略图生成</p>
        <div className="settings-grid">
          <label className="settings-field">
            <span>扫描并发数</span>
            <input type="number" defaultValue={2} min={1} max={4} />
            <small>1–4，超出可能加重 NAS 负载</small>
          </label>
          <label className="settings-field">
            <span>缩略图长边</span>
            <input type="number" defaultValue={800} min={200} max={1600} />
            <small>200–1600 px</small>
          </label>
        </div>
      </div>
      <div className="settings-card">
        <h3>上传</h3>
        <p>客户端上传行为约束</p>
        <label className="settings-field settings-single">
          <span>单文件上限</span>
          <input type="number" defaultValue={2048} min={64} max={8192} />
          <small>64–8192 MB</small>
        </label>
        <label className="settings-switch">
          <input type="checkbox" defaultChecked />
          <i aria-hidden="true" />
          <span>自动接收上传</span>
        </label>
      </div>
      <div className="settings-card">
        <h3>界面</h3>
        <p>外观与主题</p>
        <div className="settings-muted">
          <span>主题模式</span>
          <span>跟随系统（深色由侧栏月亮按钮切换）</span>
        </div>
      </div>
      <div className="settings-card">
        <h3>推荐偏好</h3>
        <p>作用于首页推荐流的 9 维权重；预设点击即保存，滑杆调整后点「保存推荐偏好」</p>
        <div className="settings-grid">
          {RECOMMEND_PRESETS.map((p) => (
            <button
              key={p.label}
              type="button"
              className={`pill${isActivePreset(p.prefs) ? ' active' : ''}`}
              disabled={savePrefs.isPending}
              onClick={() => applyPreset(p.prefs)}
            >
              {p.label}
            </button>
          ))}
        </div>
        {PREFS_KEYS.map((key) => (
          <label className="settings-field settings-single" key={key}>
            <span>{PREFS_LABELS[key]}</span>
            <input
              type="range"
              min={0}
              max={1}
              step={0.01}
              value={prefs[key] ?? 0}
              onChange={(e) => setPref(key, Number(e.target.value))}
            />
            <small>{Math.round((prefs[key] ?? 0) * 100)}%</small>
          </label>
        ))}
        <div className="settings-actions">
          <button
            className="save-btn"
            type="button"
            disabled={savePrefs.isPending}
            onClick={() => doSave(prefs)}
          >
            {savePrefs.isPending ? '保存中…' : '保存推荐偏好'}
          </button>
          {dirty ? <span className="save-tip">有未保存的调整</span> : null}
        </div>
      </div>
      <div className="settings-actions">
        <button className="save-btn" type="button" onClick={() => setSaved(true)}>
          保存设置
        </button>
        {saved ? <span className="save-tip">已保存（原型演示，未真实写入）</span> : null}
      </div>
    </div>
  )
}
