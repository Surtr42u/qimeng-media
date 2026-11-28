/**
 * 视频播放器封装（遵循 ArtPlayer 官方技术规范 https://artplayer.org/document，v5.4.0）。
 *
 * 架构规范（2026-11-28 播控修复后）：
 * 1. 控件体系：
 *    - 清晰度/倍速/字幕：自定义常驻面板（点击开关，非官方 hover selector）。
 *      官方 selector 靠 :hover 展开、面板在按钮上方，指针离开按钮就收起，
 *      鼠标/触屏都够不着选项；这里复用 ArtPlayer 的 `.art-control-selector` /
 *      `.art-selector-list` 视觉类，但展开状态只由点击驱动：点按钮切换、点选项生效
 *      并关闭、点面板外/ESC 关闭；面板打开期间控制条禁止自动隐藏。
 *    - 设置：官方 setting 面板，支持画面比例调节（默认/16:9/4:3/拉伸）与单片循环；
 *    - 字幕数据：外挂（服务端 AssetDetail.subtitles）+ 内嵌轨道合并，关闭恒第一项。
 * 2. 字幕渲染（位置固定，不随控制条移动）：
 *    - ass/ssa：assjs 画到独立画布层 `.qm-ass-subtitle-layer`，外层固定 z-index=20。
 *      不能把 z-index 写进 `.ASS-box`：assjs 每次 resize 重写内联 cssText 会抹掉，
 *      画布掉到 <video>(z-index:10) 之下 = 字幕存在但完全看不见（本批根因）；
 *    - srt/vtt 与内嵌轨：照旧把 `<track>` 交给浏览器原生渲染（mode=showing）。
 *    字幕位置保持 ASS MarginV / 浏览器原生定位，控制条显隐不改字幕几何；控制条
 *    可见时压住底部字幕可接受（用户 2026-11-28 明确否决上移方案），条子自动隐藏后
 *    字幕在原位置正常可见。
 * 3. 全屏与跨端：
 *    - 使用 ArtPlayer 原生全屏体系（fullscreen / fullscreenWeb），全屏时官方原生挂载至 document.body，
 *      彻底超越所有父级弹层 transform 与 Header 层叠限制，全屏与退出零冲突、桌面端绝对不上移。
 */

import ASS from 'assjs'
import Artplayer from 'artplayer'
import { useEffect, useRef, useState } from 'react'
import { stepPlayGate, type PlayGateState } from '@/lib/engagement-reporting'
import { createAutoHideScheduler } from '@/lib/control-autohide'
import {
  buildSubtitleMenu,
  createSubtitleApplyGate,
  defaultSubtitleIndex,
  EMBEDDED_PREFIX,
  ensureSubtitleFonts,
  EXTERNAL_PREFIX,
  fetchSubtitleText,
  srtToVtt,
  SUBTITLE_OFF,
  type SubtitleMenuEntry,
} from '@/lib/external-subtitles'
import { PLAYBACK_RATES, externalSubtitleLabel, humanLanguageLabel, qualityLabel } from '@/lib/player-labels'
import { attachFullscreenChrome } from '@/lib/player-fullscreen'

/** 进度条打点 */
export interface PlayerHighlight {
  time: number
  text: string
}

/**
 * 外挂字幕轨（协议 AssetSubtitle，2026-11-28）：url 是服务端签好的直链
 * （/media/sub/{assetId}?lang=...&exp=...&sig=...），与视频直链同款鉴权——
 * fetch 原样请求即可，禁止改写任何参数（ADR-0002）。
 */
export interface ExternalSubtitle {
  /** 规范化 BCP 47 语言码（服务端已归一，如 zh-Hans） */
  lang: string
  /** 下发格式（ass/ssa/srt/vtt；转换轨服务端已转成 vtt） */
  format: string
  url: string
  /** 服务端给的人类可读标签（三端统一展示，不各自造） */
  label?: string
  /** 服务端首选轨（简体中文优先、其次繁体）；旧服务端缺省时回退中文判定 */
  isDefault?: boolean
}

export interface VideoPlayerProps {
  /** 视频签名直链（detail.origUrl） */
  src: string
  poster?: string
  /** 断点续播起点（秒；已看完场景由页面推导传 0） */
  startTime?: number
  /** 时间轴标签打点 */
  highlights?: PlayerHighlight[]
  /** 播放中心跳（页面侧经 useProgress 5s 节流上报） */
  onTimeUpdate?: (positionSeconds: number) => void
  /** 暂停（页面侧立即上报） */
  onPause?: (positionSeconds: number) => void
  /** 起播（页面侧上报 play 事件，口径 B 防重闸门） */
  onPlay?: () => void
  /** 外挂字幕轨（detail.subtitles；缺省/空 = 无外挂字幕，仅内嵌轨可用） */
  subtitles?: ExternalSubtitle[]
}

/** 运行时读主题 token */
function readPrimaryColor(): string {
  return getComputedStyle(document.documentElement).getPropertyValue('--qm-primary').trim()
}

/**
 * 进度条点击/拖拽 seek 归一：项目全局 `html { zoom: 1.1 }`（prototype.css）下，
 * ArtPlayer 官方坐标换算会造成 seek 偏置，接管捕获阶段消除偏置。
 */
function attachProgressSeekFix(container: HTMLElement, art: Artplayer): () => void {
  let dragging = false

  const seekToClientX = (clientX: number) => {
    const $progress = container.querySelector<HTMLElement>('.art-control-progress')
    if (!$progress) return
    const rect = $progress.getBoundingClientRect()
    const duration = art.duration
    if (rect.width <= 0 || !Number.isFinite(duration) || duration <= 0) return
    const fraction = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width))
    art.seek = fraction * duration
  }

  const inProgress = (target: EventTarget | null): target is Element =>
    target instanceof Element && target.closest('.art-control-progress') !== null

  const onClickCapture = (event: MouseEvent) => {
    if (!inProgress(event.target)) return
    if ((event.target as Element).closest('.art-progress-indicator')) return
    seekToClientX(event.clientX)
    event.stopPropagation()
  }

  const onMouseDownCapture = (event: MouseEvent) => {
    if (event.button !== 0 || !inProgress(event.target)) return
    dragging = true
    event.stopPropagation()
  }
  const onDocumentMouseMove = (event: MouseEvent) => {
    if (dragging) seekToClientX(event.clientX)
  }
  const onDocumentMouseUp = () => {
    dragging = false
  }

  container.addEventListener('click', onClickCapture, true)
  container.addEventListener('mousedown', onMouseDownCapture, true)
  document.addEventListener('mousemove', onDocumentMouseMove)
  document.addEventListener('mouseup', onDocumentMouseUp)
  return () => {
    container.removeEventListener('click', onClickCapture, true)
    container.removeEventListener('mousedown', onMouseDownCapture, true)
    document.removeEventListener('mousemove', onDocumentMouseMove)
    document.removeEventListener('mouseup', onDocumentMouseUp)
  }
}

export default function VideoPlayer({
  src,
  poster,
  startTime = 0,
  highlights = [],
  subtitles = [],
  onTimeUpdate,
  onPause,
  onPlay,
}: VideoPlayerProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [initial] = useState({ src, poster, startTime, highlights, subtitles })
  const handlersRef = useRef({ onTimeUpdate, onPause, onPlay })
  useEffect(() => {
    handlersRef.current = { onTimeUpdate, onPause, onPlay }
  })
  const playGateRef = useRef<PlayGateState>('idle')

  useEffect(() => {
    const container = containerRef.current
    if (!container) return

    // 1. 倍速档位表
    Artplayer.PLAYBACK_RATE = [...PLAYBACK_RATES]
    playGateRef.current = 'idle'

    // 桌面壳检测（Tauri 壳内禁用系统全屏防 WebView 死锁，走网页全屏铺满窗口）
    const isTauriShell = typeof window !== 'undefined' && '__TAURI__' in window

    // 2. 官方规范设置面板（齿轮入口）
    const settings: NonNullable<Artplayer['option']['settings']> = [
      {
        html: '画面比例',
        icon: '',
        selector: [
          { default: true, html: '默认', value: 'default' },
          { html: '16:9', value: '16:9' },
          { html: '4:3', value: '4:3' },
          { html: '拉伸铺满', value: 'fill' },
        ],
        onSelect: function (this: Artplayer, item) {
          if (this.video) {
            if (item.value === 'fill') {
              this.video.style.objectFit = 'fill'
            } else {
              this.video.style.objectFit = 'contain'
            }
          }
          if (this.setting) {
            this.setting.show = false
          }
          return item.html
        },
      },
      {
        html: '单片循环',
        switch: false,
        onSwitch: function (this: Artplayer, item) {
          const next = !item.switch
          if (this.video) this.video.loop = next
          return next
        },
      },
    ]

    // 4. 初始化 ArtPlayer 官方实例
    const art = new Artplayer({
      container,
      url: initial.src,
      poster: initial.poster,
      theme: readPrimaryColor(),
      lang: 'zh-cn',
      volume: 0.7,
      muted: true,
      setting: true, // 官方设置齿轮入口
      settings,
      fullscreen: !isTauriShell,
      fullscreenWeb: true, // 官方网页全屏入口，全屏时自动挂至 document.body
      highlight: initial.highlights,
    })

    // 屏蔽左上角一闪而过的 seek/时间气泡通知（用户明确要求去除不用）
    if (art.notice) {
      try {
        Object.defineProperty(art.notice, 'show', {
          get: () => '',
          set: () => {},
          configurable: true,
        })
      } catch {
        // 忽略非致命属性拦截异常
      }
    }

    // 4.1 自定义常驻菜单（清晰度/倍速/字幕共用）：
    // 官方 selector 只认 :hover，面板在按钮上方，指针一离开按钮就收起，鼠标/触屏都够不着
    // 选项。这里保留 ArtPlayer 的 .art-control-selector/.art-selector-list 视觉类，但展开
    // 状态只由点击驱动；面板是控制按钮的 DOM 子节点，指针在面板内移动不会触发收起。
    interface MenuItem {
      value: string
      label: string
      detail?: string
      current: boolean
      onSelect: () => void
    }

    interface MenuHandle {
      el: HTMLElement
      setLabel: (label: string) => void
      setItems: (items: MenuItem[]) => void
      close: (focusButton?: boolean) => void
      toggle: () => void
    }

    let openMenu: MenuHandle | null = null

    const closeOpenMenu = () => {
      if (!openMenu) return
      openMenu.close(false)
      openMenu = null
    }

    const addMenuControl = (options: {
      name: string
      index: number
      label: string
      ariaLabel: string
      items: MenuItem[]
    }): MenuHandle => {
      let handle: MenuHandle | null = null
      art.controls.add({
        name: options.name,
        position: 'right',
        index: options.index,
        html: '<span class="art-selector-value"></span>',
        click: (_component, event) => {
          event.preventDefault()
          event.stopPropagation()
          handle?.toggle()
        },
        mounted: (control) => {
          control.classList.add('art-control-selector', 'art-selector-manual')
          control.setAttribute('aria-haspopup', 'menu')
          control.setAttribute('aria-expanded', 'false')
          control.setAttribute('aria-label', options.ariaLabel)
          control.setAttribute('tabindex', '0')
          const valueEl = control.querySelector<HTMLElement>('.art-selector-value')
          if (!valueEl) throw new Error(`[player] menu value missing: ${options.name}`)
          const listEl = document.createElement('div')
          listEl.className = 'art-selector-list'
          listEl.id = `qm-player-menu-${options.name}`
          listEl.setAttribute('role', 'menu')
          listEl.setAttribute('aria-hidden', 'true')
          control.setAttribute('aria-controls', listEl.id)
          control.appendChild(listEl)
          valueEl.textContent = options.label

          let menuItems = options.items
          let isOpen = false

          const itemElements = () => Array.from(listEl.querySelectorAll<HTMLElement>('.art-selector-item'))

          /** roving tabindex：焦点所在项 tabIndex=0，其余 -1；上下键在项间移动 */
          const focusItem = (index: number) => {
            const items = itemElements()
            if (items.length === 0) return
            const target = items[((index % items.length) + items.length) % items.length]
            for (const item of items) item.tabIndex = item === target ? 0 : -1
            target.focus()
          }

          /**
           * 打开瞬间列表仍在 visibility 过渡起点，直接 focus() 可能落空（焦点退回按钮）。
           * 先读一次布局强制样式生效，再聚焦首项。
           */
          const scheduleFocusFirst = () => {
            void listEl.offsetHeight
            if (isOpen) focusItem(0)
          }

          const renderItems = () => {
            listEl.replaceChildren()
            for (const item of menuItems) {
              const itemEl = document.createElement('div')
              itemEl.className = 'art-selector-item' + (item.current ? ' art-current' : '')
              itemEl.dataset.value = item.value
              itemEl.setAttribute('role', 'menuitem')
              itemEl.setAttribute('aria-current', item.current ? 'true' : 'false')
              itemEl.tabIndex = -1
              const labelEl = document.createElement('span')
              labelEl.className = 'art-selector-label'
              labelEl.textContent = item.label
              itemEl.appendChild(labelEl)
              if (item.detail) {
                const detailEl = document.createElement('span')
                detailEl.className = 'art-selector-sub'
                detailEl.textContent = item.detail
                itemEl.appendChild(detailEl)
                itemEl.style.flexDirection = 'column'
              }
              itemEl.addEventListener('click', (event) => {
                event.preventDefault()
                event.stopPropagation()
                item.onSelect()
                close(true)
              })
              listEl.appendChild(itemEl)
            }
            if (isOpen) scheduleFocusFirst()
          }

          const setOpenState = (next: boolean) => {
            isOpen = next
            control.classList.toggle('art-selector-show', next)
            control.setAttribute('aria-expanded', next ? 'true' : 'false')
            listEl.setAttribute('aria-hidden', next ? 'false' : 'true')
            // 收起时清掉 roving tabindex：隐藏面板里的项不能被 Tab 命中
            if (!next) for (const item of itemElements()) item.tabIndex = -1
          }

          const close = (focusButton = false) => {
            if (isOpen) {
              setOpenState(false)
              if (openMenu === handle) openMenu = null
              // 面板收起后重新开始自动隐藏倒计时（不要依赖残留状态）
              syncAutoHide()
            }
            if (focusButton) {
              // 选中项可能在本次 click 里被 setItems 重建：重建引发 focusout 会先以
              // close(false) 收起菜单，因此不能因 !isOpen 提前 return，仍要还焦点。
              control.focus()
              window.setTimeout(() => {
                if (!isOpen && control.isConnected) control.focus()
              }, 50)
            }
          }

          const open = () => {
            if (isOpen) return
            closeOpenMenu()
            // 齿轮设置面板与自定义菜单互斥（两者都从控制条向上展开）
            if (art.setting?.show) art.setting.show = false
            if (handle) openMenu = handle
            setOpenState(true)
            // 面板打开期间停表，避免控制条把面板一起收起
            syncAutoHide()
            // 键盘打开即进入第一项；鼠标打开同样把焦点交给菜单，Tab/方向键可直接用
            scheduleFocusFirst()
          }

          const toggle = () => {
            if (isOpen) close(false)
            else open()
          }

          control.addEventListener('keydown', (event) => {
            // 只处理按钮自身获得焦点时的按键；菜单项里的 Enter/Space 不得冒泡触发再次开关
            if (event.target !== control) return
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault()
              event.stopPropagation()
              toggle()
              return
            }
            if (!isOpen && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
              event.preventDefault()
              event.stopPropagation()
              open()
            }
          })
          // 键盘导航：↑↓ 移动、Home/End 跳首尾、Enter/Space 选中、Tab 收起并移出
          listEl.addEventListener('keydown', (event) => {
            const items = itemElements()
            const active = document.activeElement instanceof HTMLElement ? document.activeElement : null
            const index = active ? items.indexOf(active) : -1
            if (event.key === 'ArrowDown') {
              event.preventDefault()
              event.stopPropagation()
              focusItem(index + 1)
            } else if (event.key === 'ArrowUp') {
              event.preventDefault()
              event.stopPropagation()
              focusItem(index - 1)
            } else if (event.key === 'Home') {
              event.preventDefault()
              event.stopPropagation()
              focusItem(0)
            } else if (event.key === 'End') {
              event.preventDefault()
              event.stopPropagation()
              focusItem(items.length - 1)
            } else if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault()
              event.stopPropagation()
              if (active && items.includes(active)) active.click()
            } else if (event.key === 'Tab') {
              close(false)
            }
          })
          // 焦点离开控件（Tab 到下一个控件/页面元素）时收起，避免菜单悬空
          control.addEventListener('focusout', (event) => {
            if (!isOpen) return
            const next = event.relatedTarget
            if (next instanceof Node && control.contains(next)) return
            close(false)
          })
          // 面板空白处点击不冒泡到控制按钮（否则会先被按钮的 toggle 收起）
          listEl.addEventListener('click', (event) => event.stopPropagation())

          renderItems()
          handle = {
            el: control,
            setLabel: (label) => {
              valueEl.textContent = label
            },
            setItems: (nextItems) => {
              menuItems = nextItems
              renderItems()
            },
            close,
            toggle,
          }
        },
      })
      if (!handle) throw new Error(`[player] menu control not mounted: ${options.name}`)
      return handle
    }

    // 点击面板外 / ESC 关闭常驻菜单（pointerdown 先于 click，避免同一击又在别处开新面板）
    const onDocumentPointerDown = (event: PointerEvent) => {
      if (!openMenu) return
      const target = event.target
      if (target instanceof Node && openMenu.el.contains(target)) return
      closeOpenMenu()
    }
    const onDocumentKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      if (openMenu) {
        // Esc：关闭并把焦点还给触发按钮（键盘可达性要求）
        openMenu.close(true)
        openMenu = null
        event.stopPropagation()
      }
      if (art.setting?.show) art.setting.show = false
    }
    document.addEventListener('pointerdown', onDocumentPointerDown, true)
    document.addEventListener('keydown', onDocumentKeyDown)

    // 控制条自动隐藏：ArtPlayer 5.4 已移除内置 autoHide（option.d.ts 无该字段、
    // dist 中无该字符串），控制条只支持显式 show/hide，因此由播放器层调度。
    // 旧的「control(false) 时若有 openMenu 就拉回」写法在 openMenu 残留时会永久阻止
    // 隐藏，这里改为：活动→显示并重新计时；到点且菜单/设置面板都没开才真正收起。
    const AUTO_HIDE_MS = 3000
    const isOverlayOpen = () => openMenu !== null || Boolean(art.setting?.show)
    const autoHide = createAutoHideScheduler({
      hideMs: AUTO_HIDE_MS,
      setTimer: (fn, ms) => window.setTimeout(fn, ms),
      clearTimer: (id) => window.clearTimeout(id),
      onHide: () => {
        art.controls.show = false
      },
      isBlocked: isOverlayOpen,
    })
    const revealControls = () => {
      art.controls.show = true
      autoHide.schedule()
    }
    const hideControlsOnLeave = () => {
      autoHide.cancel()
      if (!isOverlayOpen()) art.controls.show = false
    }
    container.addEventListener('mousemove', revealControls)
    container.addEventListener('touchstart', revealControls, { passive: true })
    container.addEventListener('mouseleave', hideControlsOnLeave)
    // 菜单/设置面板开关后重新计时：打开即停表，关闭后才重新开始倒计时
    const syncAutoHide = () => {
      if (isOverlayOpen()) autoHide.cancel()
      else autoHide.schedule()
    }
    // 初始化：进页面时控制条是显示的，先起一次倒计时，静止 3s 后自动收起
    autoHide.schedule()

    // 清晰度面板：默认「原画」，loadedmetadata 后换成真实分辨率标签
    let qualityMenu: MenuHandle | null = null
    qualityMenu = addMenuControl({
      name: 'quality',
      index: 10,
      label: '原画',
      ariaLabel: '清晰度',
      items: [{ value: 'orig', label: '原画', current: true, onSelect: () => undefined }],
    })

    // 倍速面板：PLAYBACK_RATES 档位表（1x 按钮显示「倍速」，菜单项显示「1.0x 正常」）
    let speedMenu: MenuHandle | null = null
    const speedLabel = (rate: number) => (rate === 1 ? '倍速' : `${rate}x`)
    const speedItems = (currentRate: number): MenuItem[] =>
      PLAYBACK_RATES.map((rate) => ({
        value: String(rate),
        label: rate === 1 ? '1.0x 正常' : `${rate}x`,
        current: Math.abs(rate - currentRate) < 0.01,
        onSelect: () => {
          art.playbackRate = rate
          speedMenu?.setLabel(speedLabel(rate))
          speedMenu?.setItems(speedItems(rate))
        },
      }))
    speedMenu = addMenuControl({
      name: 'playbackRate',
      index: 12,
      label: '倍速',
      ariaLabel: '倍速',
      items: speedItems(art.playbackRate),
    })

    // 4.5 外挂字幕（2026-11-28 外挂字幕批）：服务端详情响应 AssetDetail.subtitles 下发的
    // sidecar 轨，与内嵌轨合并进字幕菜单（关闭恒第一项，外挂在前）。
    // 渲染器分两类：
    //  - ass/ssa：assjs 按脚本样式/定位/描边/黑边条绘制（Chrome 不能原生渲染 ASS；
    //    ArtPlayer 自带的 ass→vtt 转换会丢样式，明确不用）；
    //  - srt/vtt（以及内嵌轨）：转成 <track> 交给浏览器原生渲染（位置固定，不随
    //    控制条移动；条子可见时允许遮挡，隐藏后原位置可见）。
    const externalSubs = initial.subtitles
    let assInstance: ASS | null = null
    /** assjs 画布外层：固定 z-index=20，避免 assjs resize 把层级抹掉（见文件头与 glass.css） */
    let assLayerEl: HTMLDivElement | null = null
    let externalTrackEl: HTMLTrackElement | null = null
    let externalBlobUrl: string | null = null
    let selectedSubtitle = SUBTITLE_OFF
    let defaultSubtitleApplied = false
    // 组件销毁后异步回调（字体预载/字幕文本次加载）不得再建 DOM 实例
    let disposed = false
    const subtitleTextCache = new Map<string, Promise<string>>()
    // 每次 apply 递增的挂载令牌：只比较 selectedSubtitle 挡不住 A→关闭→A 的旧回调，
    // 旧 assjs/track 没有引用，关掉后仍会残留/叠层（2026-11-28 审查必修）。
    const subtitleApplyGate = createSubtitleApplyGate()
    // 默认轨下标由服务端 isDefault 决定（三端同源）；旧服务端缺字段时回退
    // 「第一条中文轨」。-1 = 保持「关闭」，内嵌轨行为与旧版一致。
    const defaultExternalIndex = defaultSubtitleIndex(externalSubs)
    const defaultSubtitleValue =
      defaultExternalIndex >= 0 ? EXTERNAL_PREFIX + defaultExternalIndex : SUBTITLE_OFF

    /** 外挂轨展示名：服务端 label 优先，旧服务端回退本地语言表 */
    const externalTrackLabel = (sub: ExternalSubtitle) =>
      sub.label || externalSubtitleLabel(sub.lang, sub.format)

    /** 内嵌字幕轨（排除 ArtPlayer 自带 kind=metadata 的空占位轨） */
    const embeddedTextTracks = (): TextTrack[] => {
      const out: TextTrack[] = []
      const list = art.video?.textTracks
      if (!list) return out
      for (let i = 0; i < list.length; i += 1) {
        if (list[i].kind !== 'metadata') out.push(list[i])
      }
      return out
    }

    const detachExternalSubtitle = () => {
      assInstance?.destroy()
      assInstance = null
      assLayerEl?.remove()
      assLayerEl = null
      externalTrackEl?.remove()
      externalTrackEl = null
      if (externalBlobUrl) {
        URL.revokeObjectURL(externalBlobUrl)
        externalBlobUrl = null
      }
    }

    /** 应用字幕选择：off | ext:<i>（外挂）| emb:<i>（内嵌）；先拆旧渲染再挂新轨 */
    const applySubtitle = (value: string) => {
      const video = art.video
      if (!video) return
      // 每次应用（含关闭/内嵌切换）都换新令牌：旧请求/字体回调只认自己的令牌，
      // 避免 A→关闭→A 时旧回调因 selectedSubtitle 又等于 A 而重复挂载。
      const applyToken = subtitleApplyGate.begin()
      selectedSubtitle = value
      detachExternalSubtitle()
      for (const track of embeddedTextTracks()) track.mode = 'hidden'
      if (value.startsWith(EMBEDDED_PREFIX)) {
        const track = embeddedTextTracks()[Number(value.slice(EMBEDDED_PREFIX.length))]
        if (track) track.mode = 'showing'
        return
      }
      if (!value.startsWith(EXTERNAL_PREFIX)) return
      const sub = externalSubs[Number(value.slice(EXTERNAL_PREFIX.length))]
      if (!sub) return
      const cached = subtitleTextCache.get(sub.url) ?? fetchSubtitleText(sub.url)
      subtitleTextCache.set(sub.url, cached)
      cached
        .then((text) => {
          if (disposed || !subtitleApplyGate.isCurrent(applyToken)) return // 已离场 / 已被后续选择取代
          if (sub.format === 'ass' || sub.format === 'ssa') {
            // 随包字体就绪后再构造 assjs：它在构造时按家族名量字体度量，
            // 字体未加载会用系统回落字体量宽，位置/行高整体跑偏。
            void ensureSubtitleFonts().then(() => {
              if (disposed || !subtitleApplyGate.isCurrent(applyToken)) return
              // 再拆一次是兜底：任意时刻只允许一个外挂渲染层（旧层无引用会残留）。
              detachExternalSubtitle()
              // 画布外层必须独立存在：assjs 每次 resize 会重写 .ASS-box 的内联 cssText，
              // 直接给 .ASS-box 设 z-index 会在首次 ResizeObserver 后丢失，画布掉到
              // <video>(z-index:10) 下面 → DOM 有节点但观众什么都看不到（本批根因）。
              // 层本身固定 z-index=20、不做上下位移，见 glass.css。
              const layer = document.createElement('div')
              layer.className = 'qm-ass-subtitle-layer'
              ;(video.parentElement ?? container).appendChild(layer)
              assLayerEl = layer
              assInstance = new ASS(text, video, { container: layer })
              assInstance.show()
            })
            return
          }
          // 同上：挂 track 前再拆一次，保证 <track> 至多一个
          detachExternalSubtitle()
          const vtt = sub.format === 'srt' ? srtToVtt(text) : text
          externalBlobUrl = URL.createObjectURL(new Blob([vtt], { type: 'text/vtt' }))
          const trackEl = document.createElement('track')
          trackEl.kind = 'subtitles'
          trackEl.label = externalTrackLabel(sub)
          trackEl.srclang = sub.lang
          trackEl.src = externalBlobUrl
          video.appendChild(trackEl)
          // 原生 cue 绘制，位置由浏览器/字幕自身决定，不随控制条移动
          if (trackEl.track) trackEl.track.mode = 'showing'
          externalTrackEl = trackEl
        })
        .catch((err: unknown) => {
          subtitleTextCache.delete(sub.url)
          console.warn('[player] 外挂字幕加载失败', sub.url, err)
        })
    }

    /** 字幕菜单条目（关闭恒第一项、外挂在前、内嵌在后；当前项高亮） */
    let subtitleEntries: SubtitleMenuEntry[] = []
    let subtitleMenu: MenuHandle | null = null

    function subtitleItems(current: string): MenuItem[] {
      return subtitleEntries.map((entry) => ({
        value: entry.value,
        label: entry.label,
        current: entry.value === current,
        onSelect: () => {
          applySubtitle(entry.value)
          subtitleMenu?.setLabel(entry.label)
          refreshSubtitleItems(entry.value)
        },
      }))
    }

    function refreshSubtitleItems(current: string) {
      subtitleMenu?.setItems(subtitleItems(current))
    }

    /** 重建字幕菜单：外挂（语言 + 格式）在前，内嵌在后，关闭恒第一项 */
    const syncSubtitleControl = () => {
      const embedded = embeddedTextTracks()
      if (externalSubs.length === 0 && embedded.length === 0) {
        if (subtitleMenu) {
          art.controls.remove('subtitle')
          subtitleMenu = null
        }
        return
      }
      const menu = buildSubtitleMenu(
        externalSubs.map((sub) => ({ label: externalTrackLabel(sub) })),
        embedded.map((track, index) => ({
          label: track.label || humanLanguageLabel(track.language) || '字幕 ' + (index + 1),
        })),
        defaultExternalIndex,
      )
      subtitleEntries = menu.entries
      if (!subtitleMenu) {
        subtitleMenu = addMenuControl({
          name: 'subtitle',
          index: 14,
          label: menu.defaultLabel,
          ariaLabel: '字幕',
          items: subtitleItems(selectedSubtitle),
        })
      } else {
        subtitleMenu.setLabel(menu.defaultLabel)
        refreshSubtitleItems(selectedSubtitle)
      }
    }

    // 5. 监听 loadedmetadata 动态更新清晰度与字幕菜单
    const onLoadedMetadata = () => {
      const v = art.video
      if (!v) return

      const q = qualityLabel(v.videoHeight)
      const mainText = q.main ? (q.tag ? `${q.main} ${q.tag}` : q.main) : '原画'
      qualityMenu?.setLabel(mainText)
      qualityMenu?.setItems([
        {
          value: 'orig',
          label: `原画 · ${q.main || '原画'}`,
          detail: `${v.videoWidth}×${v.videoHeight}`,
          current: true,
          onSelect: () => qualityMenu?.setLabel(mainText),
        },
      ])

      // 外挂 + 内嵌字幕合并进同一个菜单（外挂在前，关闭恒第一项）
      syncSubtitleControl()
      // 默认中文外挂轨：元数据就绪后应用一次（assjs 要读视频尺寸，<track> 要等元素可挂）
      if (!defaultSubtitleApplied) {
        defaultSubtitleApplied = true
        if (defaultSubtitleValue !== SUBTITLE_OFF) {
          applySubtitle(defaultSubtitleValue)
          const entry = subtitleEntries.find((item) => item.value === defaultSubtitleValue)
          if (entry) {
            subtitleMenu?.setLabel(entry.label)
            refreshSubtitleItems(defaultSubtitleValue)
          }
        }
      }
    }
    art.on('video:loadedmetadata', onLoadedMetadata)
    // 时序防线：本地/缓存媒体可能在监听注册前就完成 metadata（loadedmetadata 只报变化，
    // 错过就永不触发 → 字幕按钮不出现、默认轨不请求）。按 readyState 补跑一次，幂等。
    if (art.video && art.video.readyState >= HTMLMediaElement.HAVE_METADATA) onLoadedMetadata()

    // 倍速变化同步按钮与菜单当前项（菜单点击与外部改速走同一处）
    art.on('video:ratechange', () => {
      const currentRate = art.playbackRate
      speedMenu?.setLabel(speedLabel(currentRate))
      speedMenu?.setItems(speedItems(currentRate))
    })

    // 断点续播
    if (initial.startTime > 0) art.on('ready', () => (art.seek = initial.startTime))
    art.on('video:timeupdate', () => handlersRef.current.onTimeUpdate?.(art.currentTime))
    art.on('pause', () => {
      playGateRef.current = stepPlayGate(playGateRef.current, 'pause').state
      handlersRef.current.onPause?.(art.currentTime)
    })

    const handlePlaySignal = () => {
      const step = stepPlayGate(playGateRef.current, 'play')
      playGateRef.current = step.state
      if (step.report) handlersRef.current.onPlay?.()
    }
    const handlePauseReset = () => {
      playGateRef.current = stepPlayGate(playGateRef.current, 'pause').state
    }
    art.on('play', handlePlaySignal)
    art.on('video:play', handlePlaySignal)
    art.on('video:pause', handlePauseReset)

    // 深色模式跟随 token
    const themeObserver = new MutationObserver(() => {
      art.theme = readPrimaryColor()
    })
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })

    const detachSeekFix = attachProgressSeekFix(container, art)
    const detachFullscreenChrome = attachFullscreenChrome(art)

    return () => {
      disposed = true
      autoHide.cancel()
      container.removeEventListener('mousemove', revealControls)
      container.removeEventListener('touchstart', revealControls)
      container.removeEventListener('mouseleave', hideControlsOnLeave)
      document.removeEventListener('pointerdown', onDocumentPointerDown, true)
      document.removeEventListener('keydown', onDocumentKeyDown)
      themeObserver.disconnect()
      detachSeekFix()
      detachFullscreenChrome()
      detachExternalSubtitle()
      art.destroy()
    }
  }, [initial.src, initial.poster, initial.startTime, initial.highlights, initial.subtitles])

  return <div className="video-player-box" ref={containerRef} />
}
