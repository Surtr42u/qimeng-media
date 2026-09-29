import { useState } from 'react'
import { toast } from 'sonner'
import type { ClientConfig, RecommendPrefs } from '@/api/generated'
import { Pill } from '@/components/ui/pill'
import { Switch } from '@/components/ui/switch'
import {
  CONFIG_BOUNDS,
  DEFAULT_CLIENT_CONFIG,
  mergeClientConfig,
  useConfig,
  useSaveConfig,
} from '@/hooks/use-config'
import {
  DEFAULT_PREFS,
  PREFS_KEYS,
  RECOMMEND_PRESETS,
  usePrefs,
  useSavePrefs,
} from '@/hooks/use-prefs'
import { useAuthLogout } from '@/hooks/use-session'

/**
 * 设置页（原型 #page-settings 移植）。
 * 阶段 B：新增「推荐偏好」卡（9 维权重，GET/PUT /recommendations/prefs，全量整体替换）——
 * 预设点击即保存；GET 失败兜底「均衡推荐」默认（DOMAIN_RULES §1.3）。
 * 阶段 C（2026-09-04）：扫描/上传两卡接 GET/PUT /api/v1/config 真实持久化——
 * 全量替换语义（四字段一并提交）；upload 两项服务端实时生效，scan 两项仅
 * 持久化、重启后生效（保存 toast 与输入提示注明，不假装实时）；
 * 界面卡维持静态说明。
 * 阶段 E4（2026-09-07）：推荐偏好只留 4 预设（口径对齐旧版 GUIDE_UI:255）——
 * 移除 9 维滑杆/保存按钮/本地草稿，点击预设即 PUT；激活态对服务端保存值逐维比较。
 * 2026-09-28：「作者总表镜像」「通用来源词表」两卡迁往文件管理域（数据管理 →
 * 上传文件，与上传挂靠工作台同页维护），此处只留指引文案。
 */

/** 越界提示（协议 400 之前本地先拦，文案与 CONFIG_BOUNDS 对齐） */
const BOUND_HINTS = {
  workers: `扫描并发数须在 ${CONFIG_BOUNDS.workers.min}–${CONFIG_BOUNDS.workers.max}`,
  thumbEdge: `缩略图长边须在 ${CONFIG_BOUNDS.thumbEdge.min}–${CONFIG_BOUNDS.thumbEdge.max} px`,
  maxBytesMb: `单文件上限须在 ${CONFIG_BOUNDS.maxBytesMb.min}–${CONFIG_BOUNDS.maxBytesMb.max} MB`,
} as const

export default function SettingsPage() {
  const { data } = usePrefs()
  const savePrefs = useSavePrefs()
  // 登出（ADR-0021 多会话）：吊销本设备会话后清本地态，AuthGate 切回登录门禁
  const logout = useAuthLogout()

  // 客户端配置（扫描/上传卡）：GET 回填 + 本地草稿；保存按钮一次 PUT 全量四字段
  const { data: cfgData } = useConfig()
  const saveConfig = useSaveConfig()
  const [cfgDraft, setCfgDraft] = useState<ClientConfig | null>(null)
  const cfg: ClientConfig = cfgDraft ?? (cfgData ? mergeClientConfig(cfgData) : DEFAULT_CLIENT_CONFIG)

  const setScan = (key: keyof ClientConfig['scan'], raw: string): void => {
    // 清空/非法输入不更新草稿（受控值维持原样，用户续输即可覆盖）
    const n = Number(raw)
    if (raw.trim() === '' || !Number.isFinite(n)) return
    setCfgDraft({ ...cfg, scan: { ...cfg.scan, [key]: n } })
  }

  const setUpload = (key: keyof ClientConfig['upload'], raw: string): void => {
    const n = Number(raw)
    if (raw.trim() === '' || !Number.isFinite(n)) return
    setCfgDraft({ ...cfg, upload: { ...cfg.upload, [key]: n } })
  }

  const doSaveConfig = (): void => {
    // 本地范围校验先拦（与服务端 400 INVALID_PARAM 同口径，省一次白打请求）
    if (cfg.scan.workers < CONFIG_BOUNDS.workers.min || cfg.scan.workers > CONFIG_BOUNDS.workers.max) {
      toast.error(BOUND_HINTS.workers)
      return
    }
    if (cfg.scan.thumbEdge < CONFIG_BOUNDS.thumbEdge.min || cfg.scan.thumbEdge > CONFIG_BOUNDS.thumbEdge.max) {
      toast.error(BOUND_HINTS.thumbEdge)
      return
    }
    if (cfg.upload.maxBytesMb < CONFIG_BOUNDS.maxBytesMb.min || cfg.upload.maxBytesMb > CONFIG_BOUNDS.maxBytesMb.max) {
      toast.error(BOUND_HINTS.maxBytesMb)
      return
    }
    const scanChanged = cfgData !== undefined &&
      (cfg.scan.workers !== cfgData.scan.workers || cfg.scan.thumbEdge !== cfgData.scan.thumbEdge)
    saveConfig.mutate(cfg, {
      onSuccess: () => {
        setCfgDraft(null)
        // scan 两项是预留字段（未接入扫描器/缩略图管线，暂不生效）——
        // 变更时明确告知，绝不说"重启后生效"（那是假的）
        toast.success(scanChanged ? '设置已保存（扫描参数为预留字段，暂不生效）' : '设置已保存')
      },
      onError: (err) =>
        toast.error(`设置保存失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  // 作者总表镜像卡与通用来源词表卡已迁往「数据管理 → 上传文件」（组件 =
  // components/manage/AuthorMirrorCard.tsx / SourceVocabularyCard.tsx，零逻辑改动）

  // 当前生效值 = 服务端保存值（缺字段兜底默认），无本地草稿——预设点击即 PUT（方案拍板语义）。
  // 激活态对它逐维比较：保存 pending 期间滞后一拍（invalidate 后到位）可接受。
  const prefs: RecommendPrefs = data ? { ...DEFAULT_PREFS, ...data } : DEFAULT_PREFS

  // 预设点击即保存（含「均衡推荐」默认；方案拍板语义）
  const applyPreset = (p: RecommendPrefs): void => {
    savePrefs.mutate(p, {
      onSuccess: () => toast.success('推荐偏好已保存'),
      onError: (err) =>
        toast.error(`推荐偏好保存失败：${err instanceof Error ? err.message : String(err)}`),
    })
  }

  const isActivePreset = (p: RecommendPrefs): boolean =>
    PREFS_KEYS.every((k) => (prefs[k] ?? 0) === (p[k] ?? 0))

  return (
    <div className="page" id="page-settings">
      <div className="settings-card">
        <h3>扫描</h3>
        <p>媒体库扫描与缩略图生成 · 参数为预留字段，暂不生效</p>
        <div className="settings-grid">
          <label className="settings-field">
            <span>扫描并发数</span>
            <input
              type="number"
              min={CONFIG_BOUNDS.workers.min}
              max={CONFIG_BOUNDS.workers.max}
              step={1}
              value={cfg.scan.workers}
              onChange={(e) => setScan('workers', e.target.value)}
            />
            <small>{CONFIG_BOUNDS.workers.min}–{CONFIG_BOUNDS.workers.max}，超出可能加重 NAS 负载 · 预留字段，暂不生效</small>
          </label>
          <label className="settings-field">
            <span>缩略图长边</span>
            <input
              type="number"
              min={CONFIG_BOUNDS.thumbEdge.min}
              max={CONFIG_BOUNDS.thumbEdge.max}
              step={1}
              value={cfg.scan.thumbEdge}
              onChange={(e) => setScan('thumbEdge', e.target.value)}
            />
            <small>{CONFIG_BOUNDS.thumbEdge.min}–{CONFIG_BOUNDS.thumbEdge.max} px · 预留字段，暂不生效</small>
          </label>
        </div>
      </div>
      <div className="settings-card">
        <h3>上传</h3>
        <p>客户端上传行为约束 · 保存即生效</p>
        <label className="settings-field settings-single">
          <span>单文件上限</span>
          <input
            type="number"
            min={CONFIG_BOUNDS.maxBytesMb.min}
            max={CONFIG_BOUNDS.maxBytesMb.max}
            step={1}
            value={cfg.upload.maxBytesMb}
            onChange={(e) => setUpload('maxBytesMb', e.target.value)}
          />
          <small>{CONFIG_BOUNDS.maxBytesMb.min}–{CONFIG_BOUNDS.maxBytesMb.max} MB（与配置文件上限取更严者）</small>
        </label>
        <label className="settings-switch">
          {/* radix Switch 接管行为（Space 切换/aria-checked/焦点环），滑块视觉走
              .settings-switch-track（复刻原隐藏 checkbox+<i>）；label 文字点击仍联动 */}
          <Switch
            checked={cfg.upload.autoAccept}
            onCheckedChange={(checked) =>
              setCfgDraft({ ...cfg, upload: { ...cfg.upload, autoAccept: checked } })
            }
          />
          <span>自动接收上传</span>
        </label>
      </div>
      <div className="settings-card">
        <h3>来源与镜像</h3>
        <p>通用来源词表与作者总表镜像已移至「数据管理 → 上传文件」页维护（与上传挂靠同页）。</p>
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
        <p>作用于首页推荐流的 9 维权重；点击预设立即保存生效</p>
        <div className="settings-grid">
          {RECOMMEND_PRESETS.map((p) => (
            <Pill
              key={p.label}
              active={isActivePreset(p.prefs)}
              disabled={savePrefs.isPending}
              onClick={() => applyPreset(p.prefs)}
            >
              {p.label}
            </Pill>
          ))}
        </div>
      </div>
      <div className="settings-actions">
        <button
          className="save-btn"
          type="button"
          disabled={saveConfig.isPending}
          onClick={doSaveConfig}
        >
          {saveConfig.isPending ? '保存中…' : '保存设置'}
        </button>
        <button
          className="save-btn"
          type="button"
          disabled={logout.isPending}
          onClick={() => logout.mutate()}
        >
          {logout.isPending ? '登出中…' : '退出登录'}
        </button>
      </div>
    </div>
  )
}
