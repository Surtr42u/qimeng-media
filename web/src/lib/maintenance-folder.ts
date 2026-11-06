import type { LegacyBackupImport } from '@/api/generated'
import { openDatabase, runTx } from '@/lib/idb'

type Directory = FileSystemDirectoryHandle & {
  requestPermission(options: { mode: 'readwrite' }): Promise<PermissionState>
  queryPermission(options: { mode: 'readwrite' }): Promise<PermissionState>
}

const listeners = new Set<() => void>()
let root: Directory | null = null
let loaded: Promise<void> | undefined
let writing = false
let rootRevision = 0
let syncError: string | null = null
const database = () => openDatabase('qimeng-maintenance-folders', 1, db => db.createObjectStore('roots'))
const storageKey = () => location.origin

export function maintenanceFolderSupported(): boolean {
  return typeof window !== 'undefined' && 'showDirectoryPicker' in window
}

export const maintenanceFolderStore = {
  subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener) } },
  snapshot: () => root,
  errorSnapshot: () => syncError,
  setError(error: string | null) { syncError = error; listeners.forEach(listener => listener()) },
  async load() {
    loaded ??= (async () => {
      const revision = rootRevision
      const db = await database()
      try {
        const stored = (await runTx<Directory | undefined>(db, 'roots', 'readonly', store => store.get(storageKey()))) ?? null
        if (revision === rootRevision) root = stored
      } finally { db.close() }
      listeners.forEach(listener => listener())
    })().catch(error => { loaded = undefined; throw error })
    return loaded
  },
}

export async function chooseMaintenanceFolder(): Promise<Directory> {
  // Call the picker before any awaited IO, retaining the user's activation.
  const picker = (window as Window & {
    showDirectoryPicker?(options: { mode: 'readwrite'; id: string }): Promise<Directory>
  }).showDirectoryPicker
  if (!picker) throw new Error('当前浏览器未提供目录读写，请使用文件下载或在支持目录授权的浏览器打开')
  const selected = await picker.call(window, { mode: 'readwrite', id: 'qimeng-maintenance' })
  if (await selected.requestPermission({ mode: 'readwrite' }) !== 'granted') throw new Error('维护目录未获得读写授权，请确认目录授权')
  for (const name of ['作者资料', '备份', '上传归档']) await selected.getDirectoryHandle(name, { create: true })
  await writeFile(selected, '目录说明.md', MAINTENANCE_README)
  const db = await database()
  try { await runTx(db, 'roots', 'readwrite', store => store.put(selected, storageKey())) }
  finally { db.close() }
  rootRevision++
  root = selected
  listeners.forEach(listener => listener())
  return selected
}

export async function authorizeMaintenanceFolder(): Promise<Directory> {
  if (!root) throw new Error('请先选择维护根目录')
  const selected = root
  if (await selected.requestPermission({ mode: 'readwrite' }) !== 'granted') throw new Error('维护目录尚未获得读写授权')
  return selected
}

export function safeMaintenanceName(name: string): boolean {
  return name.trim() !== '' && name !== '.' && name !== '..' && !/[\\/:\u0000-\u001f]/u.test(name)
}

async function writeFile(directory: FileSystemDirectoryHandle, name: string, content: Blob | string): Promise<void> {
  if (!safeMaintenanceName(name)) throw new Error('维护文件名不安全')
  const file = await directory.getFileHandle(name, { create: true })
  const writer = await file.createWritable()
  try { await writer.write(content); await writer.close() }
  catch (error) {
    try { await writer.abort() }
    catch (abortError) { throw new AggregateError([error, abortError], '维护文件写入及撤回均失败') }
    throw error
  }
}

/** Current backup and author documents come from the same consistent snapshot. */
export async function writeMaintenanceSnapshot(selected: Directory, blob: Blob, automatic = false): Promise<void> {
  if (writing) throw new Error('维护目录正在更新，请稍后重试')
  writing = true
  try { await writeSnapshot(selected, blob, automatic) }
  finally { writing = false }
}

async function writeSnapshot(selected: Directory, blob: Blob, automatic: boolean): Promise<void> {
  const snapshot = JSON.parse(await blob.text()) as LegacyBackupImport
  const master = snapshot.data.authorMasterTxt
  if (typeof master !== 'string') throw new Error('当前服务端未提供作者主表，请先更新服务端')
  const fragments = snapshot.data.txtFragments ?? []
  const names = new Set<string>()
  const documents = fragments.map(fragment => {
    const name = fragment.filename || '作者清单.txt'
    if (!safeMaintenanceName(name) || !name.toLowerCase().endsWith('.txt')) throw new Error('作者片段文件名不安全')
    if (names.has(name.toLowerCase())) throw new Error('作者片段文件名重复')
    names.add(name.toLowerCase())
    return { name, content: fragment.content }
  })
  const backups = await selected.getDirectoryHandle('备份', { create: true })
  const authors = await selected.getDirectoryHandle('作者资料', { create: true })
  const previous = await existingFile(authors, '作者总表.txt')
  const masterChecksum = await existingFile(authors, '作者总表.txt.sha256')
  if (masterChecksum && masterChecksum.size > 1024) throw new Error('作者主表摘要文件过大')
  if (previous && previous.size > 64 * 1024 * 1024) throw new Error('原作者主表过大，请先移走保留')
  const previousText = previous ? await previous.text() : null
  const imported = documents.some(document => document.content === previousText)
  if (previous && masterChecksum && previousText !== master && !imported && (await masterChecksum.text()).trim() !== await sha256(previous)) throw new Error('作者主表已被外部编辑，请先导入主表后再写回')
  await selected.getDirectoryHandle('上传归档', { create: true })
  await writeGeneration(backups, '影库备份.json', blob, true, automatic)
  await writeGeneration(authors, '作者总表.txt', new Blob([master]))
  await writeFile(authors, '作者资料.md', maintenanceAuthorReport(snapshot))
  await writeFile(authors, '作者片段索引.json', JSON.stringify(['作者总表.txt']))
  await writeFile(selected, '目录说明.md', MAINTENANCE_README)
}

export async function readMaintenanceBackup(selected: Directory, generation: 0 | 1 | 2 = 0): Promise<File> {
  if (writing) throw new Error('维护目录正在更新，请稍后读取')
  const folder = await selected.getDirectoryHandle('备份')
  const name = ['影库备份.json', '影库备份（上次）.json', '影库备份（上上次）.json'][generation]
  const file = await (await folder.getFileHandle(name)).getFile()
  const checksum = await existingFile(folder, `${name}.sha256`)
  if (checksum && checksum.size > 1024) throw new Error('备份摘要文件过大')
  if (checksum && (await checksum.text()).trim() !== await sha256(file)) throw new Error('备份摘要不一致，文件可能未写完或已被替换，请重新导出或选择完整的外部备份')
  return file
}

async function existingFile(folder: FileSystemDirectoryHandle, name: string): Promise<File | null> {
  try { return await (await folder.getFileHandle(name)).getFile() }
  catch (error) {
    if (error instanceof DOMException && error.name === 'NotFoundError') return null
    throw error
  }
}

async function sha256(blob: Blob): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer())
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
}

async function writeGeneration(folder: FileSystemDirectoryHandle, name: string, blob: Blob, backup = false, protectExternal = false): Promise<void> {
  if (blob.size > 64 * 1024 * 1024) throw new Error('文件超过64MiB，请先处理容量')
  const previous = await existingFile(folder, name)
  if (previous && previous.size > 64 * 1024 * 1024) throw new Error('原文件超过64MiB，请先移走保留')
  const checksum = await existingFile(folder, `${name}.sha256`)
  if (checksum && checksum.size > 1024) throw new Error('摘要文件过大')
  if (protectExternal && previous && (!checksum || (await checksum.text()).trim() !== await sha256(previous))) {
    throw new Error('备份已被外部替换，请先导入该文件，再更新备份')
  }
  const same = previous && (backup
    ? JSON.stringify(JSON.parse(await previous.text()).data) === JSON.stringify(JSON.parse(await blob.text()).data)
    : await previous.text() === await blob.text())
  if (previous && !same) {
    const dot = name.lastIndexOf('.')
    const lastName = `${name.slice(0, dot)}（上次）${name.slice(dot)}`
    const olderName = `${name.slice(0, dot)}（上上次）${name.slice(dot)}`
    const last = await existingFile(folder, lastName)
    if (last) {
      if (last.size > 64 * 1024 * 1024) throw new Error('上次文件超过64MiB')
      if (await sha256(last) !== await sha256(previous)) await writeCheckedFile(folder, olderName, last)
    }
    await writeCheckedFile(folder, lastName, previous)
  }
  await writeCheckedFile(folder, name, blob)
}

async function writeCheckedFile(folder: FileSystemDirectoryHandle, name: string, blob: Blob): Promise<void> {
  await writeFile(folder, name, blob)
  await writeFile(folder, `${name}.sha256`, await sha256(blob))
}

/** Import protection shares the same bounded three-generation chain. */
export async function writePreImportSnapshot(selected: Directory, blob: Blob): Promise<void> {
  if (writing) throw new Error('维护目录正在更新，请稍后导入')
  writing = true
  try { await writeGeneration(await selected.getDirectoryHandle('备份', { create: true }), '影库备份.json', blob, true) }
  finally { writing = false }
}

export async function readMaintenanceTxt(selected: Directory): Promise<File[]> {
  const folder = await selected.getDirectoryHandle('作者资料')
  if (writing) throw new Error('维护目录正在更新，请稍后读取')
  let indexFile: File
  try { indexFile = await (await folder.getFileHandle('作者片段索引.json')).getFile() }
  catch (error) {
    if (!(error instanceof DOMException && error.name === 'NotFoundError')) throw error
    return readUnindexedTxt(folder)
  }
  if (indexFile.size > 1024 * 1024) throw new Error('作者片段索引超过1MiB')
  const names: unknown = JSON.parse(await indexFile.text())
  if (!Array.isArray(names) || !names.every(name => typeof name === 'string' && safeMaintenanceName(name) && name.toLowerCase().endsWith('.txt'))) throw new Error('作者片段索引格式不合法')
  return Promise.all(names.map(async name => (await folder.getFileHandle(name as string)).getFile()))
}

async function readUnindexedTxt(folder: FileSystemDirectoryHandle): Promise<File[]> {
  const files: File[] = []
  // Iteration is exposed by Chromium but omitted from some TypeScript DOM versions.
  const iterable = folder as FileSystemDirectoryHandle & { values(): AsyncIterable<FileSystemHandle> }
  for await (const entry of iterable.values()) {
    if (entry.kind === 'file' && entry.name.toLowerCase().endsWith('.txt') && !/（上(?:上)?次）\.txt$/u.test(entry.name)) {
      files.push(await (entry as FileSystemFileHandle).getFile())
    }
  }
  return files.sort((left, right) => left.name.localeCompare(right.name))
}

export function maintenanceAuthorReport(snapshot: LegacyBackupImport): string {
  const authors = snapshot.data.authors ?? []
  const files = new Map<string, string[]>()
  for (const ref of snapshot.data.authorMediaRefs ?? []) {
    const list = files.get(ref.authorId) ?? []
    list.push(ref.fileName)
    files.set(ref.authorId, list)
  }
  const lines = ['# 作者资料总表', '', `生成时间 ${new Date(snapshot.exportedAtMillis ?? Date.now()).toISOString()}`,
    `作者 ${authors.length} 位。历史入库时间仅代表记录创建，原始加入日期可能未记录。`,
    'TXT用于常规作者关联，完整资料、头像、COS及标签保存在v2备份。']
  lines.push('', '## 作者索引', '', '| 作者 | 内部ID | 关联文件 |', '| --- | --- | --- |')
  for (const author of authors) {
    const name = author.displayName.replaceAll('|', '\\|').replaceAll('\n', ' ')
    lines.push(`| ${name} | ${author.authorId} | ${files.get(author.authorId)?.length ?? 0} |`)
  }
  for (const author of authors) {
    lines.push('', `## ${author.displayName}`, '', `内部ID \`${author.authorId}\``)
    if (author.createdAtMillis != null) lines.push(`入库记录 ${new Date(author.createdAtMillis).toISOString()}`)
    if (author.profileJson) {
      const profile = JSON.parse(author.profileJson) as {
        status?: string; biography?: string; homepageUrl?: string; evidenceUrls?: string[]
        xProfile?: { url?: string; biography?: string; biographyZh?: string }
      }
      lines.push(`核验状态 ${profile.status ?? '未记录'}`)
      for (const value of [profile.biography, profile.homepageUrl]) if (value) lines.push('', value)
      if (profile.xProfile) {
        lines.push('', '### X资料')
        for (const value of [profile.xProfile.url, profile.xProfile.biography, profile.xProfile.biographyZh]) if (value) lines.push('', value)
      }
      if (profile.evidenceUrls?.length) lines.push('', '### 来源证据', '', ...profile.evidenceUrls.map(url => `- ${url}`))
    }
    lines.push('', '### 已关联文件', '', ...(files.get(author.authorId) ?? []).map(name => `- ${name}`))
  }
  return `${lines.join('\n')}\n`
}

const MAINTENANCE_README = `# 绮梦影库维护目录

- 作者资料保存作者总表.txt、作者总表（上次）.txt、作者总表（上上次）.txt，作者资料.md供阅读。
- 备份保存完整v2元数据、作者档案和离线头像，媒体原件需要另外同步。
- 备份只保留影库备份.json、影库备份（上次）.json、影库备份（上上次）.json，手动与自动共用三代。
- 导入前本机状态纳入三代轮换，导入完成后刷新最新一份。每份数据文件附带.sha256校验文件，请一起复制。
- 原始TXT片段保存在完整备份内部，目录不再额外输出原始片段和历史副本。
- Web打开并保持目录授权时自动写回，关闭浏览器后将在再次打开时补齐。
- 上传归档保存上传成功原件的副本，按库及目标目录归档。
- 导入前先同步媒体并扫描，再核对确认备份或TXT的导入范围。
`
