/**
 * 缩略图档位偏好订阅（铁律 7：组件只消费偏好，不碰 localStorage/URL 改写
 * 细节——持久化在 lib/thumb-size.ts，改写用同文件 applyThumbSize）。
 */

import { useSyncExternalStore } from 'react'
import { getThumbSize, setThumbSize, subscribeThumbSize, type ThumbSizePref } from '@/lib/thumb-size'

/** 当前档位 + 切换动作（切换即持久化并通知全部订阅组件重渲染） */
export function useThumbSize(): [ThumbSizePref, (pref: ThumbSizePref) => void] {
  const pref = useSyncExternalStore(subscribeThumbSize, getThumbSize)
  return [pref, setThumbSize]
}
