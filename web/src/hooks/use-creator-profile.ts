import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  getApiV1AuthorsByAuthorIdProfile,
  getApiV1AuthorsByAuthorIdProfileAvatar,
  postApiV1AuthorsByAuthorIdProfileRefresh,
  putApiV1AuthorsByAuthorIdProfile,
  type CreatorProfile, type CreatorProfileWrite,
} from '@/api/generated'
import { unwrapSdkResult } from '@/lib/api-client'
import { AUTHORS_QUERY_KEY } from '@/lib/query-keys'

const profileKey = (authorId: string) => [...AUTHORS_QUERY_KEY, 'profile', authorId] as const

function editable(profile: CreatorProfile): CreatorProfileWrite {
  const { revision, nickname, biography, avatarUrl, homepageUrl, evidenceUrls, status, autoRefresh, feedUrl, trackUpdates } = profile
  return { revision, nickname, biography, avatarUrl, homepageUrl, evidenceUrls, status, autoRefresh, feedUrl, trackUpdates }
}

/** 网络档案与 TXT 来源分离；草稿不会被背景回读覆盖，PUT 使用读取时的修订。 */
export function useCreatorProfile(authorId: string) {
  const qc = useQueryClient()
  const key = profileKey(authorId)
  const query = useQuery({
    queryKey: key,
    queryFn: ({ signal }) => unwrapSdkResult(getApiV1AuthorsByAuthorIdProfile({ path: { authorId }, signal })),
    retry: false,
  })
  const [draft, setDraft] = useState<CreatorProfileWrite | null>(null)
  const save = useMutation({
    mutationFn: async (body: CreatorProfileWrite) => {
      await qc.cancelQueries({ queryKey: key })
      return unwrapSdkResult(putApiV1AuthorsByAuthorIdProfile({ path: { authorId }, body: { ...body, evidenceUrls: body.evidenceUrls.map(url => url.trim()).filter(Boolean) } }))
    },
    onSuccess: (data) => { qc.setQueryData(key, data); setDraft(null) },
  })
  const refresh = useMutation({
    mutationFn: async () => {
      await qc.cancelQueries({ queryKey: key })
      return unwrapSdkResult(postApiV1AuthorsByAuthorIdProfileRefresh({ path: { authorId } }))
    },
    onSuccess: (data) => qc.setQueryData(key, data),
  })
  const profile = query.data
  const form = draft ?? (profile ? editable(profile) : null)
  const busy = save.isPending || refresh.isPending
  function change<K extends keyof CreatorProfileWrite>(field: K, value: CreatorProfileWrite[K]) {
    if (form && !busy) {
      const next = { ...form, [field]: value }
      if (next.status !== 'verified' || !next.homepageUrl.trim().startsWith('https://')) next.autoRefresh = false
      if (next.status !== 'verified' || !next.feedUrl.trim().startsWith('https://')) next.trackUpdates = false
      setDraft(next)
    }
  }
  function applyCandidate() {
    if (!profile || busy) return
    const candidate = profile.candidate
    setDraft({
      ...editable(profile),
      nickname: candidate.nickname || profile.nickname,
      biography: candidate.biography || profile.biography,
      avatarUrl: candidate.avatarUrl || profile.avatarUrl,
      homepageUrl: candidate.homepageUrl || profile.homepageUrl,
      evidenceUrls: [...new Set([...profile.evidenceUrls, ...candidate.evidenceUrls])],
      // 抓取只补全资料，不替用户证明身份；仍需编辑器中的显式核实。
      status: profile.status === 'verified' ? 'verified' : 'candidate',
    })
  }
  const candidate = profile?.candidate
  const hasCandidate = !!candidate && [candidate.nickname, candidate.biography, candidate.avatarUrl, candidate.homepageUrl, ...candidate.evidenceUrls].some(Boolean)
  return { ...query, profile, form, dirty: draft !== null, busy, save, refresh, change, applyCandidate, hasCandidate, discard: () => setDraft(null) }
}

/** 头像只通过认证后的同源接口读取，不把 token 放进 URL 或发给第三方。 */
export function useCreatorAvatar(authorId: string, source: string, revision: number) {
  const query = useQuery({
    queryKey: [...profileKey(authorId), 'avatar', revision],
    enabled: !!source,
    queryFn: ({ signal }) => unwrapSdkResult(getApiV1AuthorsByAuthorIdProfileAvatar({ path: { authorId }, signal, parseAs: 'blob' })),
    retry: false,
  })
  const url = useMemo(() => query.data instanceof Blob ? URL.createObjectURL(query.data) : undefined, [query.data])
  useEffect(() => () => { if (url) URL.revokeObjectURL(url) }, [url])
  return url
}
