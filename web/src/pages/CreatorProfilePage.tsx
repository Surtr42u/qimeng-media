import { useParams } from 'react-router'
import { ManagePageHeader } from '@/components/manage/ManagePageHeader'
import { CreatorProfileDetails } from '@/components/author/CreatorProfileDetails'
import { useAuthors } from '@/hooks/use-authors'
import { authorCollectionPath, AUTHORS_PATH } from '@/lib/route-keys'

export default function CreatorProfilePage() {
  const { authorId = '' } = useParams()
  const { data: authors, isPending, isError, refetch } = useAuthors()
  const author = authors?.find(row => row.id === authorId)
  return <div className="page" id="page-author-profile">
    <ManagePageHeader title="作者资料" description={author?.displayName ?? '账号、平台与核验信息'} backTo={author?.id ? authorCollectionPath(author.id, author.displayName ?? '') : AUTHORS_PATH} backLabel="返回作者主页" />
    {author?.id ? <CreatorProfileDetails key={author.id} author={{ ...author, id: author.id }} />
      : isPending ? <p role="status">正在读取作者…</p>
      : isError ? <div role="alert">作者读取失败 <button className="pill" onClick={() => void refetch()}>重试</button></div>
      : <p>该作者已不存在。</p>}
  </div>
}
