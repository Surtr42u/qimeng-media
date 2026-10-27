/** 纯合成目录：固定身份与分布，日期相对启动日；没有用户文件、路径或外部媒体。 */
export const DAY_MS = 86_400_000
export const FIXTURE_COUNT = 144
export const authors = ['青禾', '星野', '林间记录', '城市镜头', '光影工坊', '远山来信'].map((displayName, index) => ({
  id: `20000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
  displayName, type: index < 4 ? 'regular' : 'cos', followed: index % 2 === 0,
}))
export const tags = ['风景', '城市', '旅行', '日常'].map((name, index) => ({ id: `30000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`, name }))
export const libraries = [{ id: 'preview-regular', name: '虚拟影像库', rootPath: '/preview/regular', kind: 'normal', enabled: true }, { id: 'preview-cos', name: '虚拟COS库', rootPath: '/preview/cos', kind: 'cos', enabled: true }]
const names = ['山间的清晨', '海边随记', '城市夜色', '林中光影', '雨后的街角', '暮色与远山', '午后的旧书店', '沿着海岸线', '夏日长长的散步与一场忽然落下的雨']
const sources = ['旅途手记', '光影拾遗', '城市漫游', '其他']

export function createCatalog(now = Date.now()) {
  return Array.from({ length: FIXTURE_COUNT }, (_, index) => {
    const author = authors[index % authors.length]
    const cos = author.type === 'cos'
    const mediaType = index % 8 === 0 ? 'video' : index % 11 === 0 ? 'animated_image' : 'image'
    const extension = mediaType === 'video' ? 'mp4' : mediaType === 'animated_image' ? 'gif' : 'jpg'
    const modifiedAt = new Date(now - Math.floor(index / 12) * DAY_MS - index * 60_000).toISOString()
    return {
      id: `10000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
      fileName: `${names[index % names.length]}_${String(index + 1).padStart(3, '0')}.${extension}`,
      mediaType, modifiedAt, addedAt: modifiedAt, sizeBytes: (index % 80 + 1) * 1024 ** 2,
      source: sources[index % sources.length], characters: [`角色${index % 5 + 1}`],
      authorNames: [author.displayName], authorId: author.id, cos, libraryId: libraries[cos ? 1 : 0].id,
      cosWork: cos ? `光影企划 ${index % 4 + 1}` : null,
      tagIds: [tags[index % tags.length].id], thumbUrl: `/preview/cover.svg?v=${index % 9}`,
      origUrl: `/preview/cover.svg?v=${index % 9}`, isFavorite: index % 7 === 0,
      likeCount: index % 12, likedToday: false, viewCount: FIXTURE_COUNT - index,
      playCount: mediaType === 'video' ? index % 24 : 0,
      ...(mediaType === 'video' ? { durationMs: (90 + index) * 1000, lastPositionSeconds: index % 2 ? 20 : 90 + index } : {}),
    }
  })
}

export function coverSvg(index = 0) {
  const palettes = [['#bacbd8', '#657f95', '#f1dfba'], ['#a8d0cc', '#4d8488', '#e3e9cb'], ['#c8b9d4', '#76658d', '#ead3ce'], ['#c1d1b5', '#6d896b', '#e9d8b1'], ['#b5c5d9', '#586c8c', '#e1c4ae'], ['#e2c6ae', '#aa7f74', '#f3dfba'], ['#b8cbd2', '#647d85', '#e3d5cf'], ['#b7c6ae', '#6d7c5f', '#f0dfb9'], ['#c5bed9', '#74758f', '#e7d9c3']]
  const [sky, mountain, sun] = palettes[Math.abs(index) % palettes.length]
  return `<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360"><defs><linearGradient id="sky" x2="1" y2="1"><stop stop-color="${sky}"/><stop offset="1" stop-color="${sun}"/></linearGradient></defs><rect width="640" height="360" fill="url(#sky)"/><circle cx="484" cy="90" r="39" fill="${sun}"/><path d="M0 290 145 120 310 290 430 190 640 335V360H0Z" fill="${mountain}" opacity=".65"/><path d="M0 335 190 235 350 330 525 260 640 335V360H0Z" fill="${mountain}" opacity=".85"/></svg>`
}
