import { useState } from 'react'
import { toast } from 'sonner'
import type { AuthorMirrorConfig, ClientConfig, RecommendPrefs } from '@/api/generated'
import { Pill } from '@/components/ui/pill'
import { Switch } from '@/components/ui/switch'
import {
  CONFIG_BOUNDS,
  DEFAULT_CLIENT_CONFIG,
  mergeClientConfig,
  useConfig,
  useSaveConfig,
} from '@/hooks/use-config'
import { useAuthorMirror, useSaveAuthorMirror, useSaveSourceVocabulary, useSourceVocabulary } from '@/hooks/use-authors'
import {
  DEFAULT_PREFS,
  PREFS_KEYS,
  RECOMMEND_PRESETS,
  usePrefs,
  useSavePrefs,
} from '@/hooks/use-prefs'
import { useAuthLogout } from '@/hooks/use-session'
import { batchFailureReason } from '@/lib/batch'

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
 * 镜像卡（REQ-上传指定作者与来源 §3.4 自动镜像，2026-09-25）：GET/PUT
 * /authors/mirror——path 空=关闭；保存成功即触发一次镜像刷新（尽力而为）。
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

  // 作者总表镜像卡（REQ §3.4）：GET 回填 + 本地草稿（同扫描/上传卡范式），
  // 保存一次 PUT 两字段（path 空=关闭）
  const { data: mirrorData } = useAuthorMirror()
  const saveMirror = useSaveAuthorMirror()
  const [mirrorDraft, setMirrorDraft] = useState<AuthorMirrorConfig | null>(null)
  const mirror: AuthorMirrorConfig = mirrorDraft ?? {
    path: mirrorData?.path ?? '',
    fragmentFilename: mirrorData?.fragmentFilename ?? '',
  }

  const setMirrorField = (key: keyof AuthorMirrorConfig, raw: string): void => {
    setMirrorDraft({ ...mirror, [key]: raw })
  }

  const doSaveMirror = (): void => {
    saveMirror.mutate(
      { path: mirror.path ?? '', fragmentFilename: mirror.fragmentFilename ?? '' },
      {
        onSuccess: () => {
          setMirrorDraft(null)
          // 保存成功即尝试一次镜像刷新（服务端尽力而为）——照现有卡的反馈样式
          toast.success('镜像配置已保存')
        },
        onError: (err) =>
          // 4xx 时 unwrapSdkResult 抛的是 {code,message,status} 错误体（非 Error
          // 实例），弱模式会落成 "[object Object]"——统一走 batchFailureReason 提取
          toast.error(`镜像配置保存失败：${batchFailureReason(err)}`),
      },
    )
  }

  // 通用来源词表卡（2026-09-25 协议批）：GET 回填 + 本地草稿（同镜像卡范式）；
  // 保存一次 PUT 整体替换。条目增删走草稿：逐条点击移除 / 输入框回车添加
  // （trim 非空、重复不加——与服务端 400 去重口径对齐，省一次白打请求）
  const { data: vocabData, isLoading: vocabLoading } = useSourceVocabulary()
  const saveVocab = useSaveSourceVocabulary()
  const [vocabDraft, setVocabDraft] = useState<string[] | null>(null)
  const [vocabInput, setVocabInput] = useState('')
  const vocab: string[] = vocabDraft ?? vocabData?.sources ?? []

  const addVocabWord = (): void => {
    const word = vocabInput.trim()
    if (word === '') return
    if (!vocab.includes(word)) setVocabDraft([...vocab, word])
    setVocabInput('')
  }

  const removeVocabWord = (word: string): void => {
    setVocabDraft(vocab.filter((w) => w !== word))
  }

  const doSaveVocab = (): void => {
    saveVocab.mutate(
      { sources: vocab },
      {
        onSuccess: () => {
          setVocabDraft(null)
          toast.success('通用来源词表已保存')
        },
        onError: (err) => toast.error(`通用来源词表保存失败：${batchFailureReason(err)}`),
      },
    )
  }

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
        <h3>作者总表镜像</h3>
        <p>服务端把作者总表片段自动镜像到该路径 · 保存即生效</p>
        <div className="settings-grid">
          <label className="settings-field">
            <span>镜像文件路径</span>
            <input
              type="text"
              placeholder="服务端可写的绝对路径；留空 = 关闭"
              autoComplete="off"
              value={mirror.path ?? ''}
              onChange={(e) => setMirrorField('path', e.target.value)}
            />
            <small>须为服务端文件系统内的绝对路径（相对路径被拒绝）；留空 = 关闭镜像</small>
          </label>
          <label className="settings-field">
            <span>镜像目标片段</span>
            <input
              type="text"
              placeholder="留空=最近导入的片段"
              autoComplete="off"
              value={mirror.fragmentFilename ?? ''}
              onChange={(e) => setMirrorField('fragmentFilename', e.target.value)}
            />
            <small>多片段场景指明镜像哪份片段</small>
          </label>
        </div>
        {/* 单向镜像口径必须向用户言明（REQ §3.4）：本地手改会被覆盖 */}
        <p className="rank-note" style={{ marginTop: 10 }}>
          单向同步：本地手改该文件会在下次服务端变更时被覆盖——需要手工编辑清单时，复制一份改完再导入。
        </p>
        <div className="settings-actions" style={{ marginTop: 12 }}>
          <button
            className="save-btn"
            type="button"
            disabled={saveMirror.isPending}
            onClick={doSaveMirror}
          >
            {saveMirror.isPending ? '保存中…' : '保存镜像配置'}
          </button>
        </div>
      </div>
      <div className="settings-card">
        <h3>通用来源词表</h3>
        <p>资产编辑页来源区的建议词（获取渠道/平台名，如「老王论坛」）· 全员共享，服务端统一保存</p>
        <div className="source-chips">
          {vocab.map((word) => (
            <Pill
              key={word}
              active
              disabled={saveVocab.isPending}
              onClick={() => removeVocabWord(word)}
              title="点击移除该建议词"
            >
              {word}
            </Pill>
          ))}
          {vocabLoading && vocab.length === 0 ? (
            <span className="source-empty-hint">词表加载中…</span>
          ) : null}
          {!vocabLoading && vocab.length === 0 ? (
            <span className="source-empty-hint">暂无建议词，在下方输入框回车添加</span>
          ) : null}
        </div>
        <div className="tag-newrow">
          <input
            className="tag-input"
            type="text"
            placeholder="新来源词，回车加入词表草稿"
            autoComplete="off"
            value={vocabInput}
            onChange={(e) => setVocabInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                addVocabWord()
              }
            }}
          />
        </div>
        <small>点击词表条目移除 · 回车添加（自动去空白，重复词不加）· 保存为整体替换</small>
        <div className="settings-actions" style={{ marginTop: 12 }}>
          <button
            className="save-btn"
            type="button"
            disabled={saveVocab.isPending || (vocabDraft === null && vocab.length === 0)}
            onClick={doSaveVocab}
          >
            {saveVocab.isPending ? '保存中…' : '保存来源词表'}
          </button>
        </div>
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
