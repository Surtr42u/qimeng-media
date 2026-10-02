/**
 * 缩略图档位偏好（HANDOVER §5④「Web 缓存档位选择」，2026-10-03）。
 *
 * 语义（协议事实，openapi GET /media/thumb/{assetId}）：缩略图档位 = size
 * 查询参数（sm/md/lg 三档），它是服务端缓存键的组成部分——换档位 = 换缓存
 * 键，不同档位各存一份，切换无混存。签名（exp/sig）不锚定查询串
 *（server/internal/httpapi/assets_media_url.go 注释：「size 属于缓存选择而非
 * 授权面」），因此客户端改写服务端下发 thumbUrl 的 size 参数即可按偏好档
 * 取图，无需协议改动、不发明新缓存层（缓存仍在浏览器 HTTP 缓存 + 服务端
 * 磁盘缓存两级既有面）。
 *
 * 档位集：auto（跟随服务端下发，默认，行为零变化）/ sm（省流量）/ lg（高清）。
 * 不设显式 md 档：列表卡下发值本就是 md，详情大图是 lg——显式 md 会静默
 * 降级详情大图，语义易误解，故省流量/增强两端已覆盖真实诉求。
 *
 * 持久化走 localStorage（theme.ts 'qimeng_' 前缀同口径）；进程内单例 store
 * + useSyncExternalStore（hooks/use-thumb-size.ts）驱动全站图片重渲染。
 */

export type ThumbSizePref = 'auto' | 'sm' | 'lg'

/** localStorage 键（qimeng_ 前缀口径同 theme.ts 的 qimeng_theme） */
const THUMB_SIZE_STORAGE_KEY = 'qimeng_thumb_size'

/** 默认跟随服务端下发档位（列表 md / 详情 lg，现状行为） */
export const DEFAULT_THUMB_SIZE: ThumbSizePref = 'auto'

const VALID_PREFS: readonly ThumbSizePref[] = ['auto', 'sm', 'lg']

function normalize(v: string | null): ThumbSizePref {
  return VALID_PREFS.includes(v as ThumbSizePref) ? (v as ThumbSizePref) : DEFAULT_THUMB_SIZE
}

let current: ThumbSizePref = normalize(localStorage.getItem(THUMB_SIZE_STORAGE_KEY))
const listeners = new Set<() => void>()

/** 订阅档位变化（useSyncExternalStore 的 subscribe 入口） */
export function subscribeThumbSize(fn: () => void): () => void {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

/** 当前档位（useSyncExternalStore 的 getSnapshot 入口） */
export function getThumbSize(): ThumbSizePref {
  return current
}

/** 切换并持久化档位（通知全部订阅者触发重渲染/重请求） */
export function setThumbSize(pref: ThumbSizePref): void {
  current = normalize(pref)
  localStorage.setItem(THUMB_SIZE_STORAGE_KEY, current)
  listeners.forEach((fn) => fn())
}

/** 设置页档位选项表（值域 + 用户面文案一体；Pill 点击即存，交互同推荐偏好） */
export const THUMB_SIZE_OPTIONS: ReadonlyArray<{ value: ThumbSizePref; label: string }> = [
  { value: 'auto', label: '跟随服务端' },
  { value: 'sm', label: '省流量' },
  { value: 'lg', label: '高清' },
]

/**
 * 把偏好档位应用到缩略图 URL：auto/空 URL 原样返回；否则改写查询串里的
 * size 参数（服务端下发恒带 size=，防御式处理缺失形态——有 ? 用 & 拼）。
 * 纯字符串函数（node 测试环境可用，不依赖 window/URL API）；exp/sig 等
 * 其他参数原样保留（签名锚定路径，改 size 不破签名）。
 */
export function applyThumbSize(url: string, pref: ThumbSizePref): string {
  if (pref === 'auto' || url === '') return url
  if (/[?&]size=[a-z]*/.test(url)) {
    return url.replace(/([?&])size=[a-z]*/, `$1size=${pref}`)
  }
  return url + (url.includes('?') ? '&' : '?') + 'size=' + pref
}
