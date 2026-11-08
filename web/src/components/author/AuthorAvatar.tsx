import { useEffect, useRef, useState } from 'react'
import { useCreatorAvatar } from '@/hooks/use-creator-profile'

function AvatarImage({ authorId, initial }: { authorId: string; initial: string }) {
  const image = useCreatorAvatar(authorId)
  return <>{initial}{image && <img key={image} src={image} alt="" onError={event => { event.currentTarget.hidden = true }} />}</>
}

/** 仅为视口附近的作者挂载图片查询；离开视口释放Blob和object URL。 */
export function AuthorAvatar({ authorId, name }: { authorId: string; name: string }) {
  const ref = useRef<HTMLSpanElement>(null)
  const [visible, setVisible] = useState(false)
  const initial = Array.from(name)[0] ?? '作'
  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined') {
      setVisible(true)
      return
    }
    const observer = new IntersectionObserver(
      entries => setVisible(entries.some(entry => entry.isIntersecting)),
      { rootMargin: '80px' },
    )
    if (ref.current) observer.observe(ref.current)
    return () => observer.disconnect()
  }, [])
  return <span ref={ref} className="author-avatar" aria-hidden="true">
    {visible && authorId ? <AvatarImage authorId={authorId} initial={initial} /> : initial}
  </span>
}
