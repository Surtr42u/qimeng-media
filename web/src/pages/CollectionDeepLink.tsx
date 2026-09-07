import { Navigate, useSearchParams } from 'react-router'
import {
  COLLECTION_AUTHOR,
  COLLECTION_TAG,
  HOME_PATH,
  collectionPath,
} from '@/lib/route-keys'

/**
 * /app/collection 查询串深链归一（F1 白屏修复）。
 *
 * 缺陷机理：router.tsx 只注册了路径形态 /app/collection/:kind/:name（站内
 * 链接全部走该形态），裸 /app/collection?author=<显示名> / ?tag=<名> 这类
 * 外部深链不匹配任何路由 → 数据路由整树渲染 null → main 元素都不渲染的
 * 整页白屏。本组件把查询串翻译成规范路径后 replace 跳转（不新增历史记录，
 * 返回键语义不变），CollectionPage 仍只消费路径参数这一种形态。
 *
 * 参数口径：author 优先于 tag（二者同传时）；名称不做 trim（与集合页
 * displayName/tag.name 精确匹配同口径）；都缺省回首页（裸 /app/collection
 * 无业务语义，不存在集合索引页）。
 */
export function CollectionDeepLink() {
  const [searchParams] = useSearchParams()
  const authorName = searchParams.get('author') ?? ''
  const tagName = searchParams.get('tag') ?? ''
  if (authorName) return <Navigate to={collectionPath(COLLECTION_AUTHOR, authorName)} replace />
  if (tagName) return <Navigate to={collectionPath(COLLECTION_TAG, tagName)} replace />
  return <Navigate to={HOME_PATH} replace />
}
