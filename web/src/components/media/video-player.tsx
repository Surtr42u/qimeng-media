/**
 * 视频播放器封装（遵循 ArtPlayer 官方技术规范 https://artplayer.org/document，v5.4.0）。
 *
 * 架构规范（严格按官方技术文档与控件体系实现）：
 * 1. 控件体系完全接入 ArtPlayer 官方 controls / selector / settings API：
 *    - 清晰度：官方 selector 控件，精准定位在清晰度按钮正上方，绝不错位；
 *    - 倍速：官方 selector 控件，使用 PLAYBACK_RATES 档位表，精准居中于倍速按钮正上方；
 *    - 设置：官方 setting 面板，支持画面比例调节（默认/16:9/4:3/拉伸）与单片循环；
 *    - 字幕：检测视频内置轨道，动态生成官方 selector；
 * 2. 界面与尺寸：
 *    - 播放器直接铺满舞台（.video-player-box），绝无外层冗余 flex 包装挤压，恢复饱满大气的高度；
 *    - 底栏与弹出层注入现代毛玻璃质感，暂停时屏蔽中央大播放图标；
 * 3. 全屏与跨端：
 *    - 使用 ArtPlayer 原生全屏体系（fullscreen / fullscreenWeb），全屏时官方原生挂载至 document.body，
 *      彻底超越所有父级弹层 transform 与 Header 层叠限制，全屏与退出零冲突、桌面端绝对不上移。
 */

import Artplayer from 'artplayer'
import { useEffect, useRef, useState } from 'react'
import { stepPlayGate, type PlayGateState } from '@/lib/engagement-reporting'
import { PLAYBACK_RATES, qualityLabel } from '@/lib/player-labels'

/** 进度条打点 */
export interface PlayerHighlight {
  time: number
  text: string
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
  /** 原生内核播放窗标题（兼容保留） */
  nativeTitle?: string
}

/** 运行时读主题 token */
function readPrimaryColor(): string {
  return getComputedStyle(document.documentElement).getPropertyValue('--qm-primary').trim()
}

/** 若处于 Tauri 壳中且曾经开启过 mpv，做一次性安全关停，防孤儿窗残留 */
function closeOrphanMpvInShell(): void {
  if (typeof window !== 'undefined' && '__TAURI__' in window) {
    const t = (window as unknown as {
      __TAURI__?: {
        core?: {
          invoke?: (cmd: string, args?: Record<string, unknown>) => Promise<unknown>
        }
      }
    }).__TAURI__
    void t?.core?.invoke?.('mpv_stage_rect', { x: 0, y: 0, w: 0, h: 0, docW: 1, visible: false }).catch(() => {})
    void t?.core?.invoke?.('mpv_close').catch(() => {})
  }
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
  onTimeUpdate,
  onPause,
  onPlay,
}: VideoPlayerProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [initial] = useState({ src, poster, startTime, highlights })
  const handlersRef = useRef({ onTimeUpdate, onPause, onPlay })
  useEffect(() => {
    handlersRef.current = { onTimeUpdate, onPause, onPlay }
  })
  const playGateRef = useRef<PlayGateState>('idle')

  useEffect(() => {
    const container = containerRef.current
    if (!container) return

    closeOrphanMpvInShell()

    // 1. 倍速档位表
    Artplayer.PLAYBACK_RATE = [...PLAYBACK_RATES]
    playGateRef.current = 'idle'

    // 桌面壳检测（Tauri 壳内禁用系统全屏防 WebView 死锁，走网页全屏铺满窗口）
    const isTauriShell = typeof window !== 'undefined' && '__TAURI__' in window

    // 关闭指定 selector，并标记 art-selector-closed 彻底覆盖 :hover 状态实现即时消失收起
    const closeSelector = (selectorEl: HTMLElement | null | undefined) => {
      if (!selectorEl) return
      selectorEl.classList.remove('art-selector-show')
      selectorEl.classList.add('art-selector-closed')
    }

    // 2. 自定义控件列表（按官方规范扩展右侧入口）
    const controls: NonNullable<Artplayer['option']['controls']> = []

    // 官方规范清晰度 selector 控件：默认显示"原画"，loadedmetadata 后更新真实分辨率标签
    controls.push({
      name: 'quality',
      position: 'right',
      index: 10,
      html: '原画',
      selector: [
        {
          default: true,
          html: '原画',
          value: 'orig',
        },
      ],
      onSelect: function (item) {
        closeSelector(container.querySelector<HTMLElement>('.art-control-quality'))
        return item.html
      },
    })

    // 官方规范倍速 selector 控件：直接挂在控制栏右侧，绝不错位
    controls.push({
      name: 'playbackRate',
      position: 'right',
      index: 12,
      html: '倍速',
      selector: PLAYBACK_RATES.map((rate) => ({
        value: rate,
        default: rate === 1,
        html: rate === 1 ? '1.0x 正常' : `${rate}x`,
      })),
      onSelect: function (this: Artplayer, item) {
        const rate = Number(item.value)
        this.playbackRate = rate
        closeSelector(container.querySelector<HTMLElement>('.art-control-playbackRate'))
        return rate === 1 ? '倍速' : `${rate}x`
      },
    })

    // 3. 官方规范设置面板（齿轮入口）
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
      controls,
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

    // 5. 监听 loadedmetadata 动态更新清晰度与内置字幕
    art.on('video:loadedmetadata', () => {
      const v = art.video
      if (!v) return

      // 更新清晰度标签
      const q = qualityLabel(v.videoHeight)
      const mainText = q.main ? (q.tag ? `${q.main} ${q.tag}` : q.main) : '原画'
      const detailText = `原画 · ${q.main || '原画'}<br><span style="font-size:11px;opacity:0.72">${v.videoWidth}×${v.videoHeight}</span>`

      art.controls.update({
        name: 'quality',
        position: 'right',
        index: 10,
        html: mainText,
        selector: [
          {
            default: true,
            html: detailText,
            value: 'orig',
          },
        ],
        onSelect: function () {
          closeSelector(container.querySelector<HTMLElement>('.art-control-quality'))
          return mainText
        },
      })

      // 若视频包含内嵌字幕轨，动态挂载官方字幕 selector 控件
      if (v.textTracks && v.textTracks.length > 0) {
        const subSelectors = [{ default: true, html: '关闭', value: 0 }]
        for (let i = 0; i < v.textTracks.length; i++) {
          const t = v.textTracks[i]
          subSelectors.push({
            default: false,
            html: t.label || `字幕 ${i + 1}`,
            value: i + 1,
          })
        }
        art.controls.add({
          name: 'subtitle',
          position: 'right',
          index: 14,
          html: '字幕',
          selector: subSelectors,
          onSelect: function (item) {
            const sid = Number(item.value)
            for (let i = 0; i < v.textTracks.length; i++) {
              v.textTracks[i].mode = sid === i + 1 ? 'showing' : 'hidden'
            }
            closeSelector(container.querySelector<HTMLElement>('.art-control-subtitle'))
            return item.html
          },
        })
      }
    })

    // 监听倍速变化，同步更新倍速按钮显示文案
    art.on('video:ratechange', () => {
      const currentRate = art.playbackRate
      const label = currentRate === 1 ? '倍速' : `${currentRate}x`
      const $val = container.querySelector('.art-control-playbackRate .art-selector-value')
      if ($val) $val.textContent = label
    })

    // 统一播控菜单交互控制器（完全对标 B站 Web 播控交互逻辑）：
    // 1) 点击 selector 按钮（.art-selector-value）：
    //    - 若当前处于打开状态（有 art-selector-show 且无 art-selector-closed，或处于 hover 展开中），再次点击则收起关闭（Toggle 逻辑）；
    //    - 若当前处于关闭状态，则清除 closed、开启 show，并关闭其他已打开的 selector 与设置面板；
    // 2) 点击菜单选项（.art-selector-item）：
    //    - 选项生效后立即调用 closeSelector，菜单即刻收起消失；
    // 3) 鼠标移出 selector 区域（mouseleave / mouseout）：
    //    - 移出 selector 及其弹出列表时，同时清理 show 与 closed，菜单自然收起；再次移入时自然触发 hover 展现；
    // 4) 点击空白处 / 外部区域：
    //    - 视频画面、外部页面或非当前控件被点击时，全部菜单及设置面板立即收起；
    // 5) 设置按钮（齿轮）：
    //    - 点击齿轮按钮展示/收起设置面板，点击其他控件或选项或外部区域收起设置面板。
    const onControlClick = (e: MouseEvent) => {
      const target = e.target as HTMLElement | null
      const selectorVal = target?.closest('.art-selector-value')
      const selectorItem = target?.closest('.art-selector-item')

      if (selectorVal) {
        const parent = selectorVal.closest<HTMLElement>('.art-control-selector')
        if (parent) {
          const isClosed = parent.classList.contains('art-selector-closed')
          const isShown = parent.classList.contains('art-selector-show')
          const isHovered = parent.matches(':hover')
          const isOpen = !isClosed && (isShown || isHovered)

          if (isOpen) {
            // 当前处于打开状态 -> 再次点击按钮执行收起关闭（Toggle 逻辑）
            parent.classList.remove('art-selector-show')
            parent.classList.add('art-selector-closed')
          } else {
            // 当前处于关闭状态 -> 打开当前 selector，同时关闭其他 selector 与设置面板
            parent.classList.remove('art-selector-closed')
            container.querySelectorAll<HTMLElement>('.art-control-selector').forEach((el) => {
              if (el !== parent) {
                el.classList.remove('art-selector-show')
                el.classList.add('art-selector-closed')
              }
            })
            if (art.setting && art.setting.show) {
              art.setting.show = false
            }
            parent.classList.add('art-selector-show')
          }
        }
      } else if (selectorItem) {
        // 点击具体选项 -> 立即关闭当前 selector
        const parent = selectorItem.closest<HTMLElement>('.art-control-selector')
        if (parent) {
          closeSelector(parent)
        }
      } else {
        const inSelectorList = target?.closest('.art-selector-list')
        if (!inSelectorList) {
          // 点击外部区域（视频画面、空白处等）-> 清理所有 selector
          container.querySelectorAll<HTMLElement>('.art-control-selector').forEach((el) => {
            el.classList.remove('art-selector-show')
          })
          // 若点击既不是齿轮按钮也不是设置面板自身，收起设置面板
          const inSetting = target?.closest('.art-settings')
          const inSettingBtn = target?.closest('.art-control-setting')
          if (!inSetting && !inSettingBtn && art.setting && art.setting.show) {
            art.setting.show = false
          }
        }
      }

      // 若点击了设置齿轮按钮，关闭其他已打开的 selector
      const settingBtn = target?.closest('.art-control-setting')
      if (settingBtn) {
        container.querySelectorAll<HTMLElement>('.art-control-selector').forEach((el) => {
          el.classList.remove('art-selector-show')
          el.classList.add('art-selector-closed')
        })
      }
    }

    // 鼠标移出 selector（包括按钮和弹出菜单整体）时，移除 art-selector-show 与 art-selector-closed，
    // 菜单自然关闭，且保证下次鼠标再移入时能自然重新触发
    const onControlMouseOut = (e: MouseEvent) => {
      const fromSelector = (e.target as HTMLElement | null)?.closest<HTMLElement>('.art-control-selector')
      const toSelector = (e.relatedTarget as HTMLElement | null)?.closest<HTMLElement>('.art-control-selector')
      if (fromSelector && fromSelector !== toSelector) {
        fromSelector.classList.remove('art-selector-show')
        fromSelector.classList.remove('art-selector-closed')
      }
    }

    // 鼠标移入 selector 按钮或菜单时，若存在残留的 closed 状态则及时清除
    const onControlMouseOver = (e: MouseEvent) => {
      const fromSelector = (e.relatedTarget as HTMLElement | null)?.closest<HTMLElement>('.art-control-selector')
      const toSelector = (e.target as HTMLElement | null)?.closest<HTMLElement>('.art-control-selector')
      if (toSelector && toSelector !== fromSelector) {
        toSelector.classList.remove('art-selector-closed')
      }
    }

    // capture 阶段捕获原生 mouseleave，双重确保移出 selector 时恢复状态
    const onControlMouseLeaveCapture = (e: MouseEvent) => {
      const target = (e.target as HTMLElement | null)?.closest<HTMLElement>('.art-control-selector')
      if (target) {
        target.classList.remove('art-selector-show')
        target.classList.remove('art-selector-closed')
      }
    }

    // 页面全局点击：点击播放器外部空白处时，收起所有 selector 菜单与设置面板
    const onDocumentClick = (e: MouseEvent) => {
      const target = e.target as HTMLElement | null
      if (!container.contains(target)) {
        container.querySelectorAll<HTMLElement>('.art-control-selector').forEach((el) => {
          el.classList.remove('art-selector-show')
        })
        if (art.setting && art.setting.show) {
          art.setting.show = false
        }
      }
    }

    container.addEventListener('click', onControlClick)
    container.addEventListener('mouseout', onControlMouseOut)
    container.addEventListener('mouseover', onControlMouseOver)
    container.addEventListener('mouseleave', onControlMouseLeaveCapture, true)
    document.addEventListener('click', onDocumentClick)

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

    return () => {
      container.removeEventListener('click', onControlClick)
      container.removeEventListener('mouseout', onControlMouseOut)
      container.removeEventListener('mouseover', onControlMouseOver)
      container.removeEventListener('mouseleave', onControlMouseLeaveCapture, true)
      document.removeEventListener('click', onDocumentClick)
      themeObserver.disconnect()
      detachSeekFix()
      art.destroy()
    }
  }, [initial.src, initial.poster, initial.startTime, initial.highlights])

  return <div className="video-player-box" ref={containerRef} />
}
