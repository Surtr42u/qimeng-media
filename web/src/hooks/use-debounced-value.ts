/**
 * 输入防抖共享 hook（代码卫生约束 6：同一逻辑第 2 次出现必须抽共享——
 * 原先内联在 TopBar，作者联想字段（AuthorSuggestField）是第 2 个消费方）。
 * 值稳定 delayMs 后才同步给消费方：逐键请求是请求风暴，停顿后才取数。
 */

import { useEffect, useState } from 'react'

/** 联想输入防抖（ms）：顶栏搜索补全与上传卡作者联想共用（卫生约束 2：
 *  字面量第 2 次出现即提取——原先内联在 TopBar） */
export const SUGGEST_DEBOUNCE_MS = 200

export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs)
    return () => clearTimeout(timer)
  }, [value, delayMs])
  return debounced
}
