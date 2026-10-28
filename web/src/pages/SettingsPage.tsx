import { toast } from 'sonner'
import { batchFailureReason } from '@/lib/batch'
import type { RecommendPrefs } from '@/api/generated'
import { Pill } from '@/components/ui/pill'
import { Switch } from '@/components/ui/switch'
import { CONFIG_BOUNDS } from '@/hooks/use-config'
import { useConfigForm } from '@/hooks/use-config-form'
import {
  DEFAULT_PREFS,
  PREFS_KEYS,
  RECOMMEND_PRESETS,
  usePrefs,
  useSavePrefs,
} from '@/hooks/use-prefs'
import { useAuthLogout } from '@/hooks/use-session'
import { useThumbSize } from '@/hooks/use-thumb-size'
import { THUMB_SIZE_OPTIONS } from '@/lib/thumb-size'

/** 设置页只渲染配置与触发动作；草稿修订和提交快照由 useConfigForm 持有。 */

/** 缩略图档位 Pill 组（客户端本地偏好，点击即存 lib/thumb-size 持久化） */
function ThumbSizePills() {
  const [thumbSize, setThumbSize] = useThumbSize()
  return (
    <>
      {THUMB_SIZE_OPTIONS.map((o) => (
        <Pill key={o.value} active={thumbSize === o.value} onClick={() => setThumbSize(o.value)}>
          {o.label}
        </Pill>
      ))}
    </>
  )
}

export default function SettingsPage() {
  const { data } = usePrefs()
  const savePrefs = useSavePrefs()
  // 登出（ADR-0021 多会话）：吊销本设备会话后清本地态，AuthGate 切回登录门禁
  const logout = useAuthLogout()

  const { cfg, isPending, save, setScan, setUpload, setAutoAccept } = useConfigForm()

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
        toast.error(`推荐偏好保存失败：${batchFailureReason(err)}`),
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
            onChange={(e) => setUpload(e.target.value)}
          />
          <small>{CONFIG_BOUNDS.maxBytesMb.min}–{CONFIG_BOUNDS.maxBytesMb.max} MB（与配置文件上限取更严者）</small>
        </label>
        <label className="settings-switch">
          {/* radix Switch 接管行为（Space 切换/aria-checked/焦点环），滑块视觉走
              .settings-switch-track（复刻原隐藏 checkbox+<i>）；label 文字点击仍联动 */}
          <Switch
            checked={cfg.upload.autoAccept}
            onCheckedChange={setAutoAccept}
          />
          <span>自动接收上传</span>
        </label>
      </div>
      <div className="settings-card">
        <h3>来源与镜像</h3>
        <p>通用来源词表与作者总表镜像已移至「数据管理 → 上传文件」页维护（与上传挂靠同页）。</p>
      </div>
      <div className="settings-preferences">
        <div className="settings-card">
          <h3>界面</h3>
          <p>外观与主题</p>
          <div className="settings-muted">
            <span>主题模式</span>
            {/* ADR-0031 暗色优先：无手动选择时默认深色；月亮按钮切换后持久化 */}
            <span>默认深色（侧栏月亮按钮切换并记忆）</span>
          </div>
          {/* 缩略图档位偏好（HANDOVER §5④）：localStorage 客户端持久化，
              点击即存全站生效；改写 size 查询参数语义见 lib/thumb-size.ts */}
          <p>缩略图清晰度（全站图片，点击即生效并记忆）</p>
          <div className="settings-options">
            <ThumbSizePills />
          </div>
        </div>
        <div className="settings-card">
          <h3>推荐偏好</h3>
          <p>作用于首页推荐流的 9 维权重；点击预设立即保存生效</p>
          <div className="settings-options">
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
      </div>
      <div className="settings-actions">
        <button
          className="save-btn"
          type="button"
          disabled={isPending}
          onClick={save}
        >
          {isPending ? '保存中…' : '保存设置'}
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
