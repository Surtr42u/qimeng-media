import type { LegacyBackupImport } from '@/api/generated'
import { openDatabase, runTx } from '@/lib/idb'
import {
  ARCHIVE_SECTION,
  AUTHOR_INDEX_FILE_NAME,
  AUTHOR_REPORT_FILE_NAME,
  AUTHOR_SECTION,
  BACKUP_FILE_NAME,
  BACKUP_SECTION,
  CHECKSUM_FILE_LIMIT_BYTES,
  CURRENT_GENERATION,
  GENERATION_FILE_LIMIT_BYTES,
  MASTER_FILE_NAME,
  PREVIOUS_GENERATION,
  applyRotation,
  clearRotationMarker,
  digestsForWrites,
  existingFile,
  generationDirectory,
  isNotFound,
  matchesGeneration,
  optionalDirectory,
  optionalGenerationDirectory,
  prepareRotation,
  readRotationMarker,
  readVerifiedGenerationFile,
  repairGenerationChecksums,
  rotateFileToPrevious,
  rotationMarker,
  safeMaintenanceName,
  sha256,
  writeFile,
  writeGenerationFile,
  writeRotationMarker,
  type GenerationFile,
  type MaintenanceDirectory,
} from './maintenance-generation'

export { safeMaintenanceName } from './maintenance-generation'

type Directory = MaintenanceDirectory

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
 * 写入顺序与中断恢复：先写轮换状态文件（旧当前代摘要 + 本次目标摘要），再整代轮换，
 * 最后写新当前代（先正文后摘要）并删除状态文件；中断后下次写入按状态文件续跑，
 * 不会把半成品当前代当「上次」覆盖，也不会重复轮换已相同的上次代。写入原语与恢复
 * 判定集中在 maintenance-generation.ts。
 *
 * 旧三代同目录布局（同一目录内「（上次）/（上上次）」后缀）只做读取兼容与提示：
 * 不自动删除、不迁移、不写回旧位置；用户需要时按目录说明手动处理。
 */

const README_FILE_NAME = '目录说明.md'

/** 旧三代同目录文件名（读取兼容与「仅提示」清单；从不用于新写入）。 */
const LEGACY_MASTER_NAMES = ['作者总表.txt', '作者总表（上次）.txt', '作者总表（上上次）.txt'] as const
const LEGACY_BACKUP_NAMES = ['影库备份.json', '影库备份（上次）.json', '影库备份（上上次）.json'] as const
/** 未建索引时扫描目录：跳过旧代后缀文件，只取当前文件。 */
const LEGACY_MASTER_PATTERN = /（上(?:上)?次）\.txt$/u

interface SnapshotWrite {
  file: GenerationFile
  content: Blob | string
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

/** 创建两代目录骨架与上传归档目录；重复调用幂等。 */
async function ensureLayout(selected: FileSystemDirectoryHandle): Promise<void> {
  for (const generation of [CURRENT_GENERATION, PREVIOUS_GENERATION]) {
    for (const section of ['authors', 'backups'] as const) await generationDirectory(selected, generation, section, true)
  }
  await selected.getDirectoryHandle(ARCHIVE_SECTION, { create: true })
}

/** Current backup and author documents come from the same consistent snapshot. */
export async function writeMaintenanceSnapshot(selected: Directory, blob: Blob, automatic = false): Promise<void> {
  if (writing) throw new Error('维护目录正在更新，请稍后重试')
  writing = true
  try { await writeSnapshot(selected, blob, automatic) }
  finally { writing = false }
}

async function writeSnapshot(selected: Directory, blob: Blob, automatic: boolean): Promise<void> {
  const backupText = await blob.text()
  const snapshot = JSON.parse(backupText) as LegacyBackupImport
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
  // 写入内容只构造一次：摘要与正文必须来自同一份报告文本（报告含导出时间，重复生成会漂移）。
  const reportText = maintenanceAuthorReport(snapshot)
  const writes: SnapshotWrite[] = [
    { file: { section: 'backups', name: BACKUP_FILE_NAME, checksummed: true }, content: blob },
    { file: { section: 'authors', name: MASTER_FILE_NAME, checksummed: true }, content: new Blob([master]) },
    { file: { section: 'authors', name: AUTHOR_REPORT_FILE_NAME, checksummed: false }, content: reportText },
    { file: { section: 'authors', name: AUTHOR_INDEX_FILE_NAME, checksummed: false }, content: JSON.stringify([MASTER_FILE_NAME]) },
  ]
  const next = await digestsForWrites(writes)
  await ensureLayout(selected)
  if (await recoverInterruptedSnapshot(selected, writes, automatic)) {
    await writeFile(selected, README_FILE_NAME, MAINTENANCE_README)
    return
  }
  const currentAuthors = await generationDirectory(selected, CURRENT_GENERATION, 'authors', true)
  const currentBackups = await generationDirectory(selected, CURRENT_GENERATION, 'backups', true)
  const currentMaster = await existingFile(currentAuthors, MASTER_FILE_NAME)
  const currentBackup = await existingFile(currentBackups, BACKUP_FILE_NAME)
  if (currentMaster && currentMaster.size > GENERATION_FILE_LIMIT_BYTES) throw new Error('原作者主表过大，请先移走保留')
  if (currentBackup && currentBackup.size > GENERATION_FILE_LIMIT_BYTES) throw new Error('原备份超过64MiB，请先移走保留')
  const hasCurrent = currentMaster !== null || currentBackup !== null
  const masterChanged = currentMaster === null ? true : (await currentMaster.text()) !== master
  const backupChanged = currentBackup === null ? true : !sameBackupData(await currentBackup.text(), backupText)
  if (hasCurrent && (masterChanged || backupChanged)) {
    // 先只读核对（当前代完整、上次目标可判定），再写状态文件、最后才动文件。
    const plan = await prepareRotation(selected)
    await writeRotationMarker(selected, rotationMarker(plan.old, next))
    await applyRotation(plan)
    await writeCurrentGeneration(selected, writes, { automatic })
    await clearRotationMarker(selected)
  } else {
    // 相同数据只刷新当前代；allowRepair 用于修复此前刷新中断留下的过期摘要。
    await writeCurrentGeneration(selected, writes, { automatic, allowRepair: true })
  }
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

function contentBlob(content: Blob | string): Blob {
  return typeof content === 'string' ? new Blob([content]) : content
}

async function writeCurrentGeneration(
  selected: FileSystemDirectoryHandle,
  writes: readonly SnapshotWrite[],
  options: { automatic: boolean; allowRepair?: boolean; allowedDigests?: ReadonlySet<string> },
): Promise<void> {
  for (const write of writes) {
    const folder = await generationDirectory(selected, CURRENT_GENERATION, write.file.section, true)
    await writeGenerationFile(folder, write.file.name, contentBlob(write.content), {
      checksummed: write.file.checksummed,
      automatic: options.automatic,
      allowRepair: options.allowRepair,
      allowedDigests: options.allowedDigests,
    })
  }
}

/**
 * 中断恢复：存在轮换状态文件时，按两代现状决定续跑点。
 * - 上次代已等于状态记录的旧当前代：轮换已完成，只需补齐摘要并按本次快照重写当前代；
 *   若当前代还已等于本次目标（上次轮换和新当前都写完了，仅状态文件残留），清标记后
 *   交回正常流程（内容不同会走正常轮换）。
 * - 上次代未完成轮换：当前代必须仍是完整旧代 → 幂等补齐轮换，再写当前代。
 * 任何不可判定的组合都拒绝写入并保留现场。
 */
async function recoverInterruptedSnapshot(
  selected: FileSystemDirectoryHandle,
  writes: readonly SnapshotWrite[],
  automatic: boolean,
): Promise<boolean> {
  const marker = await readRotationMarker(selected)
  if (!marker) return false
  const allowedDigests = new Set([...Object.values(marker.old), ...Object.values(marker.next)])
  if (await matchesGeneration(selected, PREVIOUS_GENERATION, marker.old)) {
    // 上次代是安全的上一次已提交代：先把中断轮换可能漏写的摘要补回。
    await repairGenerationChecksums(selected, PREVIOUS_GENERATION, marker.old)
    if (await matchesGeneration(selected, CURRENT_GENERATION, marker.next)) {
      await clearRotationMarker(selected)
      return false
    }
    await writeCurrentGeneration(selected, writes, { automatic, allowedDigests })
    await clearRotationMarker(selected)
    return true
  }
  if (!await matchesGeneration(selected, CURRENT_GENERATION, marker.old)) {
    throw new Error('检测到上次写入中断且「当前」「上次」都不是可判定的完整代，已停止写入并保留现场；请核对两代目录后重试')
  }
  const plan = await prepareRotation(selected)
  await applyRotation(plan)
  await writeCurrentGeneration(selected, writes, { automatic, allowedDigests })
  await clearRotationMarker(selected)
  return true
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
    const backupText = await blob.text()
    const current = await generationDirectory(selected, CURRENT_GENERATION, 'backups', true)
    const previous = await generationDirectory(selected, PREVIOUS_GENERATION, 'backups', true)
    const existing = await existingFile(current, BACKUP_FILE_NAME)
    const existingSha = existing ? await sha256(existing) : null
    const desiredSha = await sha256(blob)
    const changed = existing !== null && existingSha !== desiredSha && !sameBackupData(await existing.text(), backupText)
    if (!changed) {
      // 首次写入/相同数据/中断后正文已是本次内容：只刷新当前代，摘要可安全修复。
      await writeGenerationFile(current, BACKUP_FILE_NAME, blob, { checksummed: true, automatic: false, allowRepair: true })
      return
    }
    const body = await readVerifiedGenerationFile(current, BACKUP_FILE_NAME, true)
    if (!body) throw new Error('当前备份正文缺失，已停止写入并保留现场')
    const bodySha = await sha256(body)
    if (existingSha !== null && bodySha !== existingSha) throw new Error('当前备份读取结果不一致，已停止写入并保留现场')
    await rotateFileToPrevious(previous, BACKUP_FILE_NAME, body, bodySha)
    await writeGenerationFile(current, BACKUP_FILE_NAME, blob, { checksummed: true, automatic: false })
  } finally { writing = false }
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
- 写入中断（浏览器关闭、磁盘写入失败等）后，下次更新会按根目录的「.两代轮换状态.json」续跑；该文件正常完成后自动删除，请勿手动修改。
- 旧三代布局留下的 作者总表（上次）/（上上次）、影库备份（上次）/（上上次）等文件不会自动删除；确认不再需要后可按目录说明手动清理。
- 作者资料.md是当前阅读版，作者片段索引.json供程序定位主表。原始TXT保存在完整备份内部，目录不额外堆积片段。
- 自动保存发现备份被外部替换会暂停，请先在界面导入待恢复文件，再更新备份。
- 确认导入前保存本机现状，导入完成后将合并结果导出为最新一份。恢复仍按v2合并规则执行。
- 完整备份不含媒体原件。上传归档沿用原有按库归档和同名保护规则。
- Web在页面打开且目录授权有效时自动写回，关闭后将在下次打开补齐。
`
