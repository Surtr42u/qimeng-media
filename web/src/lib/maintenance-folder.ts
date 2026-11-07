import type { LegacyBackupImport } from '@/api/generated'
import { openDatabase, runTx } from '@/lib/idb'

type Directory = FileSystemDirectoryHandle & {
  requestPermission(options: { mode: 'readwrite' }): Promise<PermissionState>
  queryPermission(options: { mode: 'readwrite' }): Promise<PermissionState>
}

/**
 * 维护目录两代布局（与 server/internal/authorattach/mirror 的「当前/上次」约定一致，
 * 依据 docs/AI_MAINTENANCE_P0.md §4.4 与 docs/AI_PLATFORM_TASKBOOK.md §9.4）：
 *
 *   维护根/目录说明.md
 *   维护根/当前/作者资料/  作者总表.txt(+.sha256)、作者资料.md、作者片段索引.json
 *   维护根/当前/备份/      影库备份.json(+.sha256)
 *   维护根/上次/作者资料/  上一代同名文件
 *   维护根/上次/备份/      上一代完整备份
 *   维护根/上传归档/       上传原件副本（不参与轮换）
 *
 * 轮换语义：内容变化时先把「当前」整代提交为「上次」，再写新「当前」；相同数据重导
 * 出只刷新当前、不挤掉上次；每代独立 .sha256；正文/摘要改用 createWritable 的
 * 临时文件+原子替换写入（File System Access 规范保证 close 时整文件替换）。
 * 摘要不符、缺摘要或不可读时拒绝覆盖并保留现场——绝不删除或覆盖来历不明文件。
 *
 * 旧三代同目录布局（同一目录内「（上次）/（上上次）」后缀）只做读取兼容与提示：
 * 不自动删除、不迁移、不写回旧位置；用户需要时按目录说明手动处理。
 */

const CURRENT_GENERATION = '当前'
const PREVIOUS_GENERATION = '上次'
const AUTHOR_SECTION = '作者资料'
const BACKUP_SECTION = '备份'
const ARCHIVE_SECTION = '上传归档'
const README_FILE_NAME = '目录说明.md'
const BACKUP_FILE_NAME = '影库备份.json'
const MASTER_FILE_NAME = '作者总表.txt'
const AUTHOR_REPORT_FILE_NAME = '作者资料.md'
const AUTHOR_INDEX_FILE_NAME = '作者片段索引.json'

/** 单文件上限：完整 v2 备份/作者主表的既有 64MiB 预算（docs/api/backup.md）。 */
const GENERATION_FILE_LIMIT_BYTES = 64 * 1024 * 1024
/** .sha256 文件上限：外部写入超长内容时拒绝读取，避免摘要比较被拖垮。 */
const CHECKSUM_FILE_LIMIT_BYTES = 1024

/** 旧三代同目录文件名（读取兼容与「仅提示」清单；从不用于新写入）。 */
const LEGACY_MASTER_NAMES = ['作者总表.txt', '作者总表（上次）.txt', '作者总表（上上次）.txt'] as const
const LEGACY_BACKUP_NAMES = ['影库备份.json', '影库备份（上次）.json', '影库备份（上上次）.json'] as const
/** 未建索引时扫描目录：跳过旧代后缀文件，只取当前文件。 */
const LEGACY_MASTER_PATTERN = /（上(?:上)?次）\.txt$/u

type SectionId = 'authors' | 'backups'

interface GenerationFile {
  section: SectionId
  name: string
  /** 是否带同名 .sha256（作者主表与完整备份） */
  checksummed: boolean
}

const SECTION_NAMES: Record<SectionId, string> = {
  authors: AUTHOR_SECTION,
  backups: BACKUP_SECTION,
}

/** 「整代」包含的文件；报告/索引是派生只读展示，不做摘要。 */
const GENERATION_FILES: readonly GenerationFile[] = [
  { section: 'authors', name: MASTER_FILE_NAME, checksummed: true },
  { section: 'authors', name: AUTHOR_REPORT_FILE_NAME, checksummed: false },
  { section: 'authors', name: AUTHOR_INDEX_FILE_NAME, checksummed: false },
  { section: 'backups', name: BACKUP_FILE_NAME, checksummed: true },
]

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
  await ensureLayout(selected)
  await writeFile(selected, README_FILE_NAME, MAINTENANCE_README)
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

/** 创建两代目录骨架与上传归档目录；重复调用幂等。 */
async function ensureLayout(selected: FileSystemDirectoryHandle): Promise<void> {
  for (const generation of [CURRENT_GENERATION, PREVIOUS_GENERATION]) {
    for (const section of ['authors', 'backups'] as const) await generationDirectory(selected, generation, section, true)
  }
  await selected.getDirectoryHandle(ARCHIVE_SECTION, { create: true })
}

async function writeFile(directory: FileSystemDirectoryHandle, name: string, content: Blob | string): Promise<void> {
  if (!safeMaintenanceName(name)) throw new Error('维护文件名不安全')
  const file = await directory.getFileHandle(name, { create: true })
  // createWritable 默认整文件替换（临时文件 + close 时原子换入），不在原文件上追加。
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
  for (const fragment of fragments) {
    const name = fragment.filename || '作者清单.txt'
    if (!safeMaintenanceName(name) || !name.toLowerCase().endsWith('.txt')) throw new Error('作者片段文件名不安全')
    const key = name.toLowerCase()
    if (names.has(key)) throw new Error('作者片段文件名重复')
    names.add(key)
  }
  await ensureLayout(selected)
  const currentAuthors = await generationDirectory(selected, CURRENT_GENERATION, 'authors', true)
  const currentBackups = await generationDirectory(selected, CURRENT_GENERATION, 'backups', true)
  const currentMaster = await existingFile(currentAuthors, MASTER_FILE_NAME)
  const currentBackup = await existingFile(currentBackups, BACKUP_FILE_NAME)
  if (currentMaster && currentMaster.size > GENERATION_FILE_LIMIT_BYTES) throw new Error('原作者主表过大，请先移走保留')
  if (currentBackup && currentBackup.size > GENERATION_FILE_LIMIT_BYTES) throw new Error('原备份超过64MiB，请先移走保留')
  const hasCurrent = currentMaster !== null || currentBackup !== null
  const masterChanged = currentMaster === null ? true : (await currentMaster.text()) !== master
  const backupChanged = currentBackup === null ? true : !sameBackupData(await currentBackup.text(), await blob.text())
  if (hasCurrent && (masterChanged || backupChanged)) await rotateGeneration(selected)
  await writeGenerationFile(currentBackups, BACKUP_FILE_NAME, blob, { checksummed: true, automatic })
  await writeGenerationFile(currentAuthors, MASTER_FILE_NAME, new Blob([master]), { checksummed: true, automatic })
  await writeFile(currentAuthors, AUTHOR_REPORT_FILE_NAME, maintenanceAuthorReport(snapshot))
  await writeFile(currentAuthors, AUTHOR_INDEX_FILE_NAME, JSON.stringify([MASTER_FILE_NAME]))
  await writeFile(selected, README_FILE_NAME, MAINTENANCE_README)
}

/** 备份内容是否等价：导出时刻/字段序不算变化（沿用旧版 data 语义比较）。 */
function sameBackupData(previousText: string, nextText: string): boolean {
  try {
    const previous = JSON.parse(previousText) as LegacyBackupImport
    const next = JSON.parse(nextText) as LegacyBackupImport
    return JSON.stringify(previous.data) === JSON.stringify(next.data)
  } catch {
    throw new Error('当前代备份无法解析，已停止写入并保留现场，请核对备份文件')
  }
}

/**
 * 把「当前」整代提交为「上次」：先只读核对全部文件（当前代摘要/上次目标可写性），
 * 全部通过后才开始复制，避免半代轮换。上次目标来历不明（缺摘要/摘要不符）时拒绝覆盖。
 */
async function rotateGeneration(selected: FileSystemDirectoryHandle): Promise<void> {
  const plan: Array<{ previous: FileSystemDirectoryHandle; name: string; body: File; checksummed: boolean }> = []
  for (const file of GENERATION_FILES) {
    const current = await optionalGenerationDirectory(selected, CURRENT_GENERATION, file.section)
    if (!current) continue
    const body = await verifiedGenerationFile(current, file.name, file.checksummed)
    if (!body) continue
    const previous = await generationDirectory(selected, PREVIOUS_GENERATION, file.section, true)
    await assertPreviousOverwritable(previous, file.name, file.checksummed)
    plan.push({ previous, name: file.name, body, checksummed: file.checksummed })
  }
  for (const item of plan) {
    await writeFile(item.previous, item.name, item.body)
    if (item.checksummed) await writeFile(item.previous, `${item.name}.sha256`, await sha256(item.body))
  }
}

/** 当前代文件核对：有摘要必须相符；缺摘要/只有摘要都视为现场不完整，停止轮换。 */
async function verifiedGenerationFile(folder: FileSystemDirectoryHandle, name: string, checksummed: boolean): Promise<File | null> {
  const body = await existingFile(folder, name)
  if (!body) {
    if (checksummed && await existingFile(folder, `${name}.sha256`)) throw new Error(`当前代「${name}」只有摘要没有正文，已停止轮换并保留现场`)
    return null
  }
  if (body.size > GENERATION_FILE_LIMIT_BYTES) throw new Error(`当前代「${name}」超过64MiB，已停止轮换`)
  if (!checksummed) return body
  const checksum = await existingFile(folder, `${name}.sha256`)
  if (!checksum) throw new Error(`当前代「${name}」缺少同名摘要，无法确认内容；已停止轮换并保留现场`)
  if (checksum.size > CHECKSUM_FILE_LIMIT_BYTES) throw new Error('摘要文件过大')
  if ((await checksum.text()).trim() !== await sha256(body)) {
    throw new Error(`当前代「${name}」摘要不符，文件可能已被外部替换或未写完；已停止轮换并保留现场`)
  }
  return body
}

/** 上次目标可写性：摘要不符/缺摘要/不可读时拒绝覆盖，绝不覆盖来历不明文件。 */
async function assertPreviousOverwritable(folder: FileSystemDirectoryHandle, name: string, checksummed: boolean): Promise<void> {
  const body = await existingFile(folder, name)
  if (!body) {
    if (checksummed && await existingFile(folder, `${name}.sha256`)) throw new Error(`上次代「${name}」只有摘要没有正文，已停止轮换并保留现场`)
    return
  }
  if (body.size > GENERATION_FILE_LIMIT_BYTES) throw new Error(`上次代「${name}」超过64MiB，已停止轮换`)
  if (!checksummed) return
  const checksum = await existingFile(folder, `${name}.sha256`)
  if (!checksum) throw new Error(`上次代「${name}」缺少同名摘要，无法确认来历；已停止轮换并保留现场，请核对或移走该文件`)
  if (checksum.size > CHECKSUM_FILE_LIMIT_BYTES) throw new Error('摘要文件过大')
  if ((await checksum.text()).trim() !== await sha256(body)) {
    throw new Error(`上次代「${name}」摘要不符，拒绝覆盖并保留现场，请核对或移走该文件`)
  }
}

/**
 * 写当前代文件：正文与摘要成对写入（每个文件独立原子替换）。
 * 已存在且摘要不符时，仅当正文恰好等于本次待写内容才按摘要过期修复；否则拒绝覆盖。
 */
async function writeGenerationFile(
  folder: FileSystemDirectoryHandle,
  name: string,
  blob: Blob,
  options: { checksummed: boolean; automatic: boolean },
): Promise<void> {
  if (blob.size > GENERATION_FILE_LIMIT_BYTES) throw new Error('文件超过64MiB，请先处理容量')
  const existing = await existingFile(folder, name)
  if (existing) {
    if (existing.size > GENERATION_FILE_LIMIT_BYTES) throw new Error('原文件超过64MiB，请先移走保留')
    if (options.checksummed) {
      const checksum = await existingFile(folder, `${name}.sha256`)
      if (!checksum) {
        if (options.automatic) throw new Error(`当前代「${name}」缺少同名摘要，自动写回已暂停；请手动导出一次或核对文件`)
      } else {
        if (checksum.size > CHECKSUM_FILE_LIMIT_BYTES) throw new Error('摘要文件过大')
        const actual = await sha256(existing)
        if ((await checksum.text()).trim() !== actual && actual !== await sha256(blob)) {
          throw new Error(`当前代「${name}」摘要不符，文件可能已被外部替换或未写完；已停止写入并保留现场`)
        }
      }
    }
  }
  await writeFile(folder, name, blob)
  if (options.checksummed) await writeFile(folder, `${name}.sha256`, await sha256(blob))
}

export async function readMaintenanceBackup(selected: Directory, generation: 0 | 1 | 2 = 0): Promise<File> {
  if (writing) throw new Error('维护目录正在更新，请稍后读取')
  if (generation < 2) {
    const folder = await optionalGenerationDirectory(selected, generation === 0 ? CURRENT_GENERATION : PREVIOUS_GENERATION, 'backups')
    if (folder && await existingFile(folder, BACKUP_FILE_NAME)) return readBackupFile(folder, BACKUP_FILE_NAME)
  }
  // 迁移前旧三代同目录布局：只读兼容（旧文件不自动删除）
  const legacy = await optionalDirectory(selected, BACKUP_SECTION)
  if (legacy) {
    const legacyName = LEGACY_BACKUP_NAMES[generation]
    if (await existingFile(legacy, legacyName)) return readBackupFile(legacy, legacyName)
  }
  const modernLayout = await optionalDirectory(selected, CURRENT_GENERATION)
  if (generation === 2 && modernLayout) {
    throw new Error('当前目录使用两代布局，仅保留「当前」与「上次」；旧三代文件未自动删除，可在根目录「备份」中核对')
  }
  throw new DOMException('未找到备份文件', 'NotFoundError')
}

async function readBackupFile(folder: FileSystemDirectoryHandle, name: string): Promise<File> {
  const file = await (await folder.getFileHandle(name)).getFile()
  const checksum = await existingFile(folder, `${name}.sha256`)
  if (checksum && checksum.size > CHECKSUM_FILE_LIMIT_BYTES) throw new Error('备份摘要文件过大')
  if (checksum && (await checksum.text()).trim() !== await sha256(file)) {
    throw new Error('备份摘要不一致，文件可能未写完或已被替换，请重新导出或选择完整的外部备份')
  }
  return file
}

/** 列出旧三代布局残留文件（仅提示，绝不删除）；返回值形如「备份/影库备份（上次）.json」。 */
export async function findLegacyGenerationFiles(selected: Directory): Promise<string[]> {
  const found: string[] = []
  const authors = await optionalDirectory(selected, AUTHOR_SECTION)
  if (authors) for (const name of LEGACY_MASTER_NAMES) if (await existingFile(authors, name)) found.push(`${AUTHOR_SECTION}/${name}`)
  const backups = await optionalDirectory(selected, BACKUP_SECTION)
  if (backups) for (const name of LEGACY_BACKUP_NAMES) if (await existingFile(backups, name)) found.push(`${BACKUP_SECTION}/${name}`)
  return found
}

/** Import protection writes the current backup generation (same rotation rules as manual export). */
export async function writePreImportSnapshot(selected: Directory, blob: Blob): Promise<void> {
  if (writing) throw new Error('维护目录正在更新，请稍后导入')
  writing = true
  try {
    await ensureLayout(selected)
    const current = await generationDirectory(selected, CURRENT_GENERATION, 'backups', true)
    const previous = await generationDirectory(selected, PREVIOUS_GENERATION, 'backups', true)
    const existing = await existingFile(current, BACKUP_FILE_NAME)
    const changed = existing === null ? false : !sameBackupData(await existing.text(), await blob.text())
    if (changed) {
      const body = await verifiedGenerationFile(current, BACKUP_FILE_NAME, true)
      await assertPreviousOverwritable(previous, BACKUP_FILE_NAME, true)
      if (body) {
        await writeFile(previous, BACKUP_FILE_NAME, body)
        await writeFile(previous, `${BACKUP_FILE_NAME}.sha256`, await sha256(body))
      }
    }
    await writeGenerationFile(current, BACKUP_FILE_NAME, blob, { checksummed: true, automatic: false })
  } finally { writing = false }
}

export async function readMaintenanceTxt(selected: Directory): Promise<File[]> {
  if (writing) throw new Error('维护目录正在更新，请稍后读取')
  const current = await optionalGenerationDirectory(selected, CURRENT_GENERATION, 'authors')
  if (current) {
    const files = await readTxtFolder(current)
    if (files.length > 0) return files
  }
  const legacy = await optionalDirectory(selected, AUTHOR_SECTION)
  if (legacy) return readTxtFolder(legacy)
  if (current) return []
  throw new DOMException('未找到作者资料目录', 'NotFoundError')
}

async function readTxtFolder(folder: FileSystemDirectoryHandle): Promise<File[]> {
  let indexFile: File
  try { indexFile = await (await folder.getFileHandle(AUTHOR_INDEX_FILE_NAME)).getFile() }
  catch (error) {
    if (!isNotFound(error)) throw error
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
    if (entry.kind === 'file' && entry.name.toLowerCase().endsWith('.txt') && !LEGACY_MASTER_PATTERN.test(entry.name)) {
      files.push(await (entry as FileSystemFileHandle).getFile())
    }
  }
  return files.sort((left, right) => left.name.localeCompare(right.name))
}

async function sha256(blob: Blob): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer())
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
}

function isNotFound(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'NotFoundError'
}

async function existingFile(folder: FileSystemDirectoryHandle, name: string): Promise<File | null> {
  try { return await (await folder.getFileHandle(name)).getFile() }
  catch (error) {
    if (isNotFound(error)) return null
    throw error
  }
}

async function optionalDirectory(parent: FileSystemDirectoryHandle, name: string): Promise<FileSystemDirectoryHandle | null> {
  try { return await parent.getDirectoryHandle(name) }
  catch (error) {
    if (isNotFound(error)) return null
    throw error
  }
}

async function generationDirectory(
  parent: FileSystemDirectoryHandle,
  generation: string,
  section: SectionId,
  create = false,
): Promise<FileSystemDirectoryHandle> {
  const generationHandle = await parent.getDirectoryHandle(generation, { create })
  return generationHandle.getDirectoryHandle(SECTION_NAMES[section], { create })
}

async function optionalGenerationDirectory(
  parent: FileSystemDirectoryHandle,
  generation: string,
  section: SectionId,
): Promise<FileSystemDirectoryHandle | null> {
  const generationHandle = await optionalDirectory(parent, generation)
  return generationHandle ? optionalDirectory(generationHandle, SECTION_NAMES[section]) : null
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

const MAINTENANCE_README = `# 影库资料维护

这里统一保存作者资料、数据备份和上传归档。换设备时，先同步媒体，再导入完整备份。

## 文件夹一览

| 位置 | 你会用到的内容 | 日常操作 |
| --- | --- | --- |
| 当前/作者资料 | 作者总表.txt、作者资料.md、作者片段索引.json 及主表 .sha256 | TXT维护作者与作品关联，Markdown阅读简介、来源和作品清单 |
| 当前/备份 | 影库备份.json 及 .sha256 | 迁移作者、头像、标签、浏览记录和推荐偏好 |
| 上次/作者资料、上次/备份 | 内容变化前的上一代同名文件 | 需要时按整代回退核对 |
| 上传归档 | 按媒体库和目标目录保存的原件副本 | 查看已经上传成功的文件 |

## 两代轮换

- 内容变化时先把「当前」整代提交为「上次」，再写新的「当前」；数据没变只刷新当前，不挤掉上次。
- 每代各自带同名 .sha256 摘要；摘要不符或文件不可读时停止写入并保留现场，请先核对文件再更新。
- 旧三代布局留下的 作者总表（上次）/（上上次）、影库备份（上次）/（上上次）等文件不会自动删除；确认不再需要后可按目录说明手动清理。
- 作者资料.md是当前阅读版，作者片段索引.json供程序定位主表。原始TXT保存在完整备份内部，目录不额外堆积片段。
- 自动保存发现备份被外部替换会暂停，请先在界面导入待恢复文件，再更新备份。
- 确认导入前保存本机现状，导入完成后将合并结果导出为最新一份。恢复仍按v2合并规则执行。
- 完整备份不含媒体原件。上传归档沿用原有按库归档和同名保护规则。
- Web在页面打开且目录授权有效时自动写回，关闭后将在下次打开补齐。
`
