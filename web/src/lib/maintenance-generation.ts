/**
 * 两代维护目录的写入原语、整代轮换与中断恢复（maintenance-folder.ts 的写入编排共用）。
 *
 * 为什么需要单独的状态文件：File System Access 没有目录级原子提交，一次导出要写
 * 「备份 + 作者主表 + 报告 + 索引」多个文件。顺序固定为：
 *   1. 先写 .轮换状态.json（记录旧当前代摘要 old 与本次目标摘要 next）
 *   2. 把旧当前代整代复制为上次代
 *   3. 写新当前代（每个文件先正文、后摘要）
 *   4. 删除状态文件
 * 任一步中断都会留下状态文件；下次写入据此判定「轮换是否已完成、当前代处于旧/半新/
 * 新」，从断点继续而不是把半成品当成当前代复制为上次代。状态文件只有 SHA-256 与
 * 目录名，不含用户数据。
 *
 * 旧三代同目录布局的读取兼容与提示留在 maintenance-folder.ts，本模块只负责新布局写入。
 */

export type MaintenanceDirectory = FileSystemDirectoryHandle & {
  requestPermission(options: { mode: 'readwrite' }): Promise<PermissionState>
  queryPermission(options: { mode: 'readwrite' }): Promise<PermissionState>
}

/** 两代目录名（与服务端 mirror「当前/上次」约定一致）。 */
export const CURRENT_GENERATION = '当前'
export const PREVIOUS_GENERATION = '上次'
export const AUTHOR_SECTION = '作者资料'
export const BACKUP_SECTION = '备份'
export const ARCHIVE_SECTION = '上传归档'
export const BACKUP_FILE_NAME = '影库备份.json'
export const MASTER_FILE_NAME = '作者总表.txt'
export const AUTHOR_REPORT_FILE_NAME = '作者资料.md'
export const AUTHOR_INDEX_FILE_NAME = '作者片段索引.json'

/** 单文件上限：完整 v2 备份/作者主表的既有 64MiB 预算（docs/api/backup.md）。 */
export const GENERATION_FILE_LIMIT_BYTES = 64 * 1024 * 1024
/** .sha256 文件上限：外部写入超长内容时拒绝读取，避免摘要比较被拖垮。 */
export const CHECKSUM_FILE_LIMIT_BYTES = 1024

/** 轮换状态文件名（维护根下；正常完成后删除，中断时残留供下次自愈）。 */
export const ROTATION_MARKER_FILE_NAME = '.两代轮换状态.json'
const ROTATION_MARKER_FORMAT = 'qimeng_maintenance_rotation'
const ROTATION_MARKER_VERSION = 1
const ROTATION_MARKER_SIZE_LIMIT_BYTES = 64 * 1024

export type SectionId = 'authors' | 'backups'

export interface GenerationFile {
  section: SectionId
  name: string
  /** 是否带同名 .sha256（作者主表与完整备份） */
  checksummed: boolean
}

export const SECTION_NAMES: Record<SectionId, string> = {
  authors: AUTHOR_SECTION,
  backups: BACKUP_SECTION,
}

/** 「整代」包含的文件；报告/索引是派生只读展示，不做摘要。 */
export const GENERATION_FILES: readonly GenerationFile[] = [
  { section: 'authors', name: MASTER_FILE_NAME, checksummed: true },
  { section: 'authors', name: AUTHOR_REPORT_FILE_NAME, checksummed: false },
  { section: 'authors', name: AUTHOR_INDEX_FILE_NAME, checksummed: false },
  { section: 'backups', name: BACKUP_FILE_NAME, checksummed: true },
]

/** 单文件内容摘要表：键 = 「分区/文件名」，值为正文 SHA-256（小写十六进制）。 */
export type FileDigests = Record<string, string>

export function generationFileKey(file: GenerationFile): string {
  return `${file.section}/${file.name}`
}

function generationFileFromKey(key: string): GenerationFile | null {
  const index = key.indexOf('/')
  if (index <= 0) return null
  const section = key.slice(0, index)
  const name = key.slice(index + 1)
  if (section !== 'authors' && section !== 'backups') return null
  return GENERATION_FILES.find(file => file.section === section && file.name === name) ?? null
}

export function safeMaintenanceName(name: string): boolean {
  if (name.trim() === '' || name === '.' || name === '..') return false
  // 逐字符判定而非正则控制字符类：同一口径（拒绝路径分隔符/冒号/控制字符），
  // 也避免 lint 对控制字符正则的误报。
  for (const char of name) {
    if (char === '/' || char === '\\' || char === ':' || char.charCodeAt(0) <= 0x1f) return false
  }
  return true
}

export function isNotFound(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'NotFoundError'
}

export async function writeFile(directory: FileSystemDirectoryHandle, name: string, content: Blob | string): Promise<void> {
  if (!safeMaintenanceName(name)) throw new Error('维护文件名不安全')
  const file = await directory.getFileHandle(name, { create: true })
  // createWritable 默认整文件替换（临时文件 + close 时原子换入），不在原文件上追加；
  // 正文先写、摘要最后写，配合状态文件判定中断点。
  const writer = await file.createWritable()
  try { await writer.write(content); await writer.close() }
  catch (error) {
    try { await writer.abort() }
    catch (abortError) { throw new AggregateError([error, abortError], '维护文件写入及撤回均失败') }
    throw error
  }
}

export async function existingFile(folder: FileSystemDirectoryHandle, name: string): Promise<File | null> {
  try { return await (await folder.getFileHandle(name)).getFile() }
  catch (error) {
    if (isNotFound(error)) return null
    throw error
  }
}

export async function optionalDirectory(parent: FileSystemDirectoryHandle, name: string): Promise<FileSystemDirectoryHandle | null> {
  try { return await parent.getDirectoryHandle(name) }
  catch (error) {
    if (isNotFound(error)) return null
    throw error
  }
}

export async function generationDirectory(
  parent: FileSystemDirectoryHandle,
  generation: string,
  section: SectionId,
  create = false,
): Promise<FileSystemDirectoryHandle> {
  const generationHandle = await parent.getDirectoryHandle(generation, { create })
  return generationHandle.getDirectoryHandle(SECTION_NAMES[section], { create })
}

export async function optionalGenerationDirectory(
  parent: FileSystemDirectoryHandle,
  generation: string,
  section: SectionId,
): Promise<FileSystemDirectoryHandle | null> {
  const generationHandle = await optionalDirectory(parent, generation)
  return generationHandle ? optionalDirectory(generationHandle, SECTION_NAMES[section]) : null
}

export async function sha256(blob: Blob): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer())
  return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
}

/** 写当前代文件：正文与摘要成对写入，正文先、摘要后。
 *  allowRepair：调用方已证明正文内容与本次相同（相同数据重导出），允许修复过期/缺失摘要；
 *  allowedDigests：中断恢复放行集合（正文摘要等于中断现场记录值），其余不符仍拒绝覆盖。 */
export async function writeGenerationFile(
  folder: FileSystemDirectoryHandle,
  name: string,
  blob: Blob,
  options: { checksummed: boolean; automatic: boolean; allowRepair?: boolean; allowedDigests?: ReadonlySet<string> },
): Promise<void> {
  if (blob.size > GENERATION_FILE_LIMIT_BYTES) throw new Error('文件超过64MiB，请先处理容量')
  const existing = await existingFile(folder, name)
  if (existing) {
    if (existing.size > GENERATION_FILE_LIMIT_BYTES) throw new Error('原文件超过64MiB，请先移走保留')
    if (options.checksummed) {
      const existingSha = await sha256(existing)
      const desiredSha = await sha256(blob)
      const checksum = await existingFile(folder, `${name}.sha256`)
      const tolerated = options.allowRepair === true || options.allowedDigests?.has(existingSha) === true || existingSha === desiredSha
      if (!checksum) {
        if (options.automatic && !tolerated) {
          throw new Error(`当前代「${name}」缺少同名摘要，自动写回已暂停；请手动导出一次或核对文件`)
        }
      } else {
        if (checksum.size > CHECKSUM_FILE_LIMIT_BYTES) throw new Error('摘要文件过大')
        if ((await checksum.text()).trim() !== existingSha && !tolerated) {
          throw new Error(`当前代「${name}」摘要不符，文件可能已被外部替换或未写完；已停止写入并保留现场`)
        }
      }
    }
  }
  await writeFile(folder, name, blob)
  if (options.checksummed) await writeFile(folder, `${name}.sha256`, await sha256(blob))
}

/** 当前代文件核对：有摘要必须相符；缺摘要/只有摘要都视为现场不完整，停止轮换。 */
export async function readVerifiedGenerationFile(folder: FileSystemDirectoryHandle, name: string, checksummed: boolean): Promise<File | null> {
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

/** 读取某代正文摘要表（严格校验摘要配对）；缺摘要/摘要不符即拒绝，避免半成品被当完整代。 */
export async function readGenerationDigests(
  selected: FileSystemDirectoryHandle,
  generation: string = CURRENT_GENERATION,
): Promise<FileDigests> {
  const digests: FileDigests = {}
  for (const file of GENERATION_FILES) {
    const folder = await optionalGenerationDirectory(selected, generation, file.section)
    if (!folder) continue
    const body = await readVerifiedGenerationFile(folder, file.name, file.checksummed)
    if (body) digests[generationFileKey(file)] = await sha256(body)
  }
  return digests
}

/** 该代是否仍与记录摘要一致（逐文件按正文 SHA-256 比较；不要求 .sha256 存在，恢复时另行修复）。 */
export async function matchesGeneration(
  selected: FileSystemDirectoryHandle,
  generation: string,
  expected: FileDigests,
): Promise<boolean> {
  for (const [key, digest] of Object.entries(expected)) {
    const file = generationFileFromKey(key)
    if (!file) return false
    const folder = await optionalGenerationDirectory(selected, generation, file.section)
    if (!folder) return false
    const body = await existingFile(folder, file.name)
    if (!body || await sha256(body) !== digest) return false
  }
  return true
}

/** 补齐某代缺失/过期的 .sha256（仅用于正文摘要与记录一致的恢复现场，绝不改正文）。 */
export async function repairGenerationChecksums(
  selected: FileSystemDirectoryHandle,
  generation: string,
  expected: FileDigests,
): Promise<void> {
  for (const [key, digest] of Object.entries(expected)) {
    const file = generationFileFromKey(key)
    if (!file || !file.checksummed) continue
    const folder = await optionalGenerationDirectory(selected, generation, file.section)
    if (!folder) continue
    const body = await existingFile(folder, file.name)
    if (!body || await sha256(body) !== digest) continue
    const checksum = await existingFile(folder, `${file.name}.sha256`)
    if (checksum && (await checksum.text()).trim() === digest) continue
    await writeFile(folder, `${file.name}.sha256`, digest)
  }
}

interface RotationStep {
  folder: FileSystemDirectoryHandle
  name: string
  checksummed: boolean
  expectedSha: string
  body?: File
  action: 'skip' | 'repair' | 'copy'
}

export interface RotationPlan {
  /** 旧当前代正文摘要（写入状态文件、供中断恢复核对） */
  old: FileDigests
  steps: RotationStep[]
}

/**
 * 轮换前只读核对：当前代必须完整（摘要配对）、上次目标要么是旧一代（可覆盖）、
 * 要么已是本次要写入的同一内容（跳过/只补摘要）。任何不可判定状态都拒绝，不写正文。
 */
export async function prepareRotation(selected: FileSystemDirectoryHandle): Promise<RotationPlan> {
  const old: FileDigests = {}
  const steps: RotationStep[] = []
  for (const file of GENERATION_FILES) {
    const current = await optionalGenerationDirectory(selected, CURRENT_GENERATION, file.section)
    if (!current) continue
    const body = await readVerifiedGenerationFile(current, file.name, file.checksummed)
    if (!body) continue
    const bodySha = await sha256(body)
    old[generationFileKey(file)] = bodySha
    const previous = await generationDirectory(selected, PREVIOUS_GENERATION, file.section, true)
    const peer = await existingFile(previous, file.name)
    if (peer) {
      const peerSha = await sha256(peer)
      if (peerSha === bodySha) {
        const checksum = file.checksummed ? await existingFile(previous, `${file.name}.sha256`) : null
        if (checksum && checksum.size > CHECKSUM_FILE_LIMIT_BYTES) throw new Error('摘要文件过大')
        const checksumOk = checksum !== null && (await checksum.text()).trim() === peerSha
        steps.push({ folder: previous, name: file.name, checksummed: file.checksummed, expectedSha: bodySha, action: checksumOk ? 'skip' : 'repair' })
        continue
      }
      if (file.checksummed) {
        const checksum = await existingFile(previous, `${file.name}.sha256`)
        if (!checksum) throw new Error(`上次代「${file.name}」缺少同名摘要，无法确认来历；已停止轮换并保留现场，请核对或移走该文件`)
        if (checksum.size > CHECKSUM_FILE_LIMIT_BYTES) throw new Error('摘要文件过大')
        if ((await checksum.text()).trim() !== peerSha) {
          throw new Error(`上次代「${file.name}」摘要不符，拒绝覆盖并保留现场，请核对或移走该文件`)
        }
      }
    } else if (file.checksummed && await existingFile(previous, `${file.name}.sha256`)) {
      throw new Error(`上次代「${file.name}」只有摘要没有正文，已停止轮换并保留现场`)
    }
    steps.push({ folder: previous, name: file.name, checksummed: file.checksummed, expectedSha: bodySha, body, action: 'copy' })
  }
  return { old, steps }
}

/** 执行轮换：已在位的同内容跳过（幂等，不重复轮换），缺摘要只补摘要，其余先正文后摘要。 */
export async function applyRotation(plan: RotationPlan): Promise<void> {
  for (const step of plan.steps) {
    if (step.action === 'skip') continue
    if (step.action === 'repair') {
      await writeFile(step.folder, `${step.name}.sha256`, step.expectedSha)
      continue
    }
    if (step.body) await writeFile(step.folder, step.name, step.body)
    if (step.checksummed) await writeFile(step.folder, `${step.name}.sha256`, step.expectedSha)
  }
}

/** 单文件轮换（导入前备份只轮换备份文件，沿用同样的幂等/修复口径）。 */
export async function rotateFileToPrevious(
  previous: FileSystemDirectoryHandle,
  name: string,
  body: File,
  expectedSha: string,
  checksummed = true,
): Promise<void> {
  const peer = await existingFile(previous, name)
  if (peer) {
    const peerSha = await sha256(peer)
    if (peerSha === expectedSha) {
      const checksum = checksummed ? await existingFile(previous, `${name}.sha256`) : null
      if (!checksum || (await checksum.text()).trim() !== peerSha) await writeFile(previous, `${name}.sha256`, expectedSha)
      return
    }
    if (checksummed) {
      const checksum = await existingFile(previous, `${name}.sha256`)
      if (!checksum) throw new Error(`上次代「${name}」缺少同名摘要，无法确认来历；已停止轮换并保留现场，请核对或移走该文件`)
      if (checksum.size > CHECKSUM_FILE_LIMIT_BYTES) throw new Error('摘要文件过大')
      if ((await checksum.text()).trim() !== peerSha) {
        throw new Error(`上次代「${name}」摘要不符，拒绝覆盖并保留现场，请核对或移走该文件`)
      }
    }
  } else if (checksummed && await existingFile(previous, `${name}.sha256`)) {
    throw new Error(`上次代「${name}」只有摘要没有正文，已停止轮换并保留现场`)
  }
  await writeFile(previous, name, body)
  if (checksummed) await writeFile(previous, `${name}.sha256`, expectedSha)
}

export interface RotationMarker {
  format: typeof ROTATION_MARKER_FORMAT
  version: typeof ROTATION_MARKER_VERSION
  /** 轮换前旧当前代正文摘要 */
  old: FileDigests
  /** 本次要写入的新当前代正文摘要 */
  next: FileDigests
}

function isDigests(value: unknown): value is FileDigests {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return false
  return Object.entries(value as Record<string, unknown>).every(([key, digest]) =>
    generationFileFromKey(key) !== null && typeof digest === 'string' && /^[0-9a-f]{64}$/u.test(digest),
  )
}

export async function readRotationMarker(selected: FileSystemDirectoryHandle): Promise<RotationMarker | null> {
  const file = await existingFile(selected, ROTATION_MARKER_FILE_NAME)
  if (!file) return null
  if (file.size > ROTATION_MARKER_SIZE_LIMIT_BYTES) {
    throw new Error('两代轮换状态文件异常，已停止写入并保留现场；请核对或移走该文件后重试')
  }
  try {
    const raw = JSON.parse(await file.text()) as Partial<RotationMarker>
    if (raw.format !== ROTATION_MARKER_FORMAT || raw.version !== ROTATION_MARKER_VERSION || !isDigests(raw.old) || !isDigests(raw.next)) throw new Error('invalid marker')
    return { format: ROTATION_MARKER_FORMAT, version: ROTATION_MARKER_VERSION, old: raw.old, next: raw.next }
  } catch {
    throw new Error('两代轮换状态文件无法解析，已停止写入并保留现场；如确认是中断写入残留，可移走该文件后重试')
  }
}

/** 状态文件构造单源（format/version 字面量只在模块内出现）。 */
export function rotationMarker(old: FileDigests, next: FileDigests): RotationMarker {
  return { format: ROTATION_MARKER_FORMAT, version: ROTATION_MARKER_VERSION, old, next }
}

export async function writeRotationMarker(selected: FileSystemDirectoryHandle, marker: RotationMarker): Promise<void> {
  await writeFile(selected, ROTATION_MARKER_FILE_NAME, JSON.stringify(marker))
}

export async function clearRotationMarker(selected: FileSystemDirectoryHandle): Promise<void> {
  try { await selected.removeEntry(ROTATION_MARKER_FILE_NAME) }
  catch (error) { if (!isNotFound(error)) throw error }
}

/** 由写入内容构造摘要表（摘要必须与实际写出的同一份内容计算，避免时间戳等差异）。 */
export async function digestsForWrites(
  writes: ReadonlyArray<{ file: GenerationFile; content: Blob | string }>,
): Promise<FileDigests> {
  const digests: FileDigests = {}
  for (const write of writes) {
    const blob = typeof write.content === 'string' ? new Blob([write.content]) : write.content
    digests[generationFileKey(write.file)] = await sha256(blob)
  }
  return digests
}