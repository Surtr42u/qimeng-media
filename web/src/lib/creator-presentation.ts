/** 外链只格式化可读的平台名，身份归属仍由服务端已保存资料决定。 */
export function creatorSourceLabel(value: string): string {
  try {
    const host = new URL(value).hostname.toLowerCase().replace(/^www\./, '')
    if (host === 'x.com' || host === 'twitter.com') return 'X / Twitter'
    if (host === 'patreon.com') return 'Patreon'
    if (host.endsWith('.fanbox.cc') || host === 'fanbox.cc') return 'FANBOX'
    if (host === 'pixiv.net') return 'Pixiv'
    if (host === 'bsky.app') return 'Bluesky'
    if (host === 'iwara.tv') return 'Iwara'
    if (host === 'space.bilibili.com' || host === 'bilibili.com') return '哔哩哔哩'
    if (host === 'weibo.com' || host === 'weibo.cn') return '微博'
    if (host === 'fantia.jp') return 'Fantia'
    if (host === 'subscribestar.adult' || host === 'subscribestar.com') return 'SubscribeStar'
    return host || '公开资料'
  } catch {
    return '公开资料'
  }
}

export function creatorBiographyPreview(biography: string): string {
  return biography.trim().split(/\n\s*\n/, 1)[0] ?? ''
}
