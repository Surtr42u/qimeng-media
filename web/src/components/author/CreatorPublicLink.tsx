import { creatorSourceLabel } from '@/lib/creator-presentation'

export function CreatorPublicLink({ url, label }: { url: string; label?: string }) {
  // 不让旧缓存或导入载荷把可执行 scheme 带入链接。
  if (!url.startsWith('https://')) return <span>{label ?? '公开资料'}</span>
  return <a href={url} target="_blank" rel="noopener noreferrer" referrerPolicy="no-referrer">{label ?? creatorSourceLabel(url)} ↗</a>
}

