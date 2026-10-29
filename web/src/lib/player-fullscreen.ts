import type Artplayer from 'artplayer'

/** ArtPlayer 5.4 的 layer 默认随播放隐藏；全屏退出入口独立保留，CSS 见 glass.css。 */
export function attachFullscreenChrome(art: Artplayer): () => void {
  const player = art.template.$player
  const button = document.createElement('button')
  button.type = 'button'
  button.className = 'img-viewer__btn video-fullscreen-exit'
  button.setAttribute('aria-label', '退出全屏')
  button.title = '退出全屏（Esc）'
  button.textContent = '×'
  art.layers.add({ name: 'fullscreen-exit', html: button })
  const update = () => {
    button.hidden = !art.fullscreen && !art.fullscreenWeb
    player.classList.toggle('qimeng-paused', art.video.paused)
  }
  const exit = (event: Event) => {
    event.stopPropagation()
    if (art.fullscreen) art.fullscreen = false
    if (art.fullscreenWeb) art.fullscreenWeb = false
  }
  const onKey = (event: KeyboardEvent) => {
    if (event.key === 'Escape' && art.fullscreenWeb) exit(event)
  }
  const reveal = (event: PointerEvent) => {
    if (event.pointerType !== 'mouse') return
    const rect = player.getBoundingClientRect()
    const nearBottom = event.clientY >= rect.bottom - FULLSCREEN_REVEAL_EDGE_PX
    player.classList.toggle('qimeng-controls-hover', nearBottom && (art.fullscreen || art.fullscreenWeb))
    if (nearBottom) art.controls.show = true
  }
  const leave = () => player.classList.remove('qimeng-controls-hover')
  button.addEventListener('click', exit)
  player.addEventListener('pointermove', reveal)
  player.addEventListener('pointerleave', leave)
  document.addEventListener('keydown', onKey)
  art.on('fullscreen', update)
  art.on('fullscreenWeb', update)
  art.on('video:pause', update)
  art.on('video:play', update)
  update()
  return () => {
    button.removeEventListener('click', exit)
    player.removeEventListener('pointermove', reveal)
    player.removeEventListener('pointerleave', leave)
    document.removeEventListener('keydown', onKey)
  }
}

const FULLSCREEN_REVEAL_EDGE_PX = 100
