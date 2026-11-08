import { describe, expect, it, vi } from 'vitest'
import type { LegacyBackupImport } from '@/api/generated'
import {
  chooseMaintenanceFolder,
  findLegacyGenerationFiles,
  maintenanceAuthorReport,
  maintenanceFolderSupported,
  readMaintenanceBackup,
  readMaintenanceTxt,
  safeMaintenanceName,
  writeMaintenanceSnapshot,
  writePreImportSnapshot,
} from './maintenance-folder'
import {
  AUTHOR_INDEX_FILE_NAME,
  AUTHOR_REPORT_FILE_NAME,
  AUTHOR_SECTION,
  BACKUP_FILE_NAME,
  BACKUP_SECTION,
  GENERATION_FILES,
  MASTER_FILE_NAME,
  ROTATION_MARKER_FILE_NAME,
  generationFileKey,
  sha256,
} from './maintenance-generation'

// 维护目录选择只依赖 IndexedDB 句柄持久化；测试用内存桩替身，不碰真实浏览器存储。
vi.mock('@/lib/idb', () => ({
  openDatabase: async () => ({ close: () => undefined }),
  runTx: async (
    _db: unknown,
    _store: unknown,
    _mode: unknown,
    run: (store: { put: () => void; get: () => undefined }) => unknown,
  ) => run({ put: () => undefined, get: () => undefined }),
}))

const snapshot: LegacyBackupImport = {
  format: 'qimeng_backup', schemaVersion: 2, appIdentifier: 'com.qimeng.media', exportedAtMillis: 1000,
  data: { authorMasterTxt: '1  Synthetic\n作品\nsynthetic.png\n', viewEvents: [], authors: [{ authorId: 'synthetic', displayName: 'Synthetic',
    profileJson: JSON.stringify({ status: 'verified', xProfile: { biography: 'Original biography', biographyZh: '合成中文简介' } }) }],
  authorMediaRefs: [{ authorId: 'synthetic', recordKey: 'one', fileName: 'synthetic.png' }],
  txtFragments: [{ importedAtMillis: 0, filename: 'authors.txt', content: '1  Synthetic\r\n作品\r\nsynthetic.png\r\n' }] },
}

const blob = (index: number, time = index) => new Blob([JSON.stringify({ ...snapshot, exportedAtMillis: time,
  data: { ...snapshot.data, authorMasterTxt: `generation ${index}` } })])

interface MemoryState { failures: Set<string> }

class MemoryDirectory {
  files = new Map<string, Blob | string>()
  directories = new Map<string, MemoryDirectory>()
  private readonly state: MemoryState
  private readonly prefix: string
  constructor(state: MemoryState = { failures: new Set() }, prefix = '') {
    this.state = state
    this.prefix = prefix
  }
  /** 模拟写入中断：指定完整相对路径的写入（正文或摘要）抛错。 */
  failNextWrites(...paths: string[]): void {
    for (const path of paths) this.state.failures.add(path)
  }
  clearFailures(): void { this.state.failures.clear() }
  private pathOf(name: string): string { return this.prefix === '' ? name : `${this.prefix}/${name}` }
  async requestPermission(): Promise<PermissionState> { return 'granted' }
  async queryPermission(): Promise<PermissionState> { return 'granted' }
  async getDirectoryHandle(name: string, options?: { create?: boolean }): Promise<MemoryDirectory> {
    if (!this.directories.has(name)) {
      if (!options?.create) throw new DOMException('missing', 'NotFoundError')
      this.directories.set(name, new MemoryDirectory(this.state, this.pathOf(name)))
    }
    return this.directories.get(name)!
  }
  async getFileHandle(name: string, options?: { create?: boolean }) {
    const files = this.files
    const path = this.pathOf(name)
    const state = this.state
    if (!options?.create && !files.has(name)) throw new DOMException('missing', 'NotFoundError')
    return {
      name,
      kind: 'file' as const,
      createWritable: async () => ({
        write: async (content: Blob | string) => {
          if (state.failures.has(path)) throw new Error(`模拟写入失败：${path}`)
          files.set(name, content)
        },
        close: async () => undefined,
        abort: async () => undefined,
      }),
      getFile: async () => new File([files.get(name) ?? new Blob()], name),
    }
  }
  async removeEntry(name: string): Promise<void> {
    const path = this.pathOf(name)
    if (this.state.failures.has(path)) throw new Error(`模拟删除失败：${path}`)
    if (this.files.delete(name)) return
    if (this.directories.delete(name)) return
    throw new DOMException('missing', 'NotFoundError')
  }
  async *values(): AsyncIterable<FileSystemHandle> {
    for (const [name, content] of this.files) {
      yield { name, kind: 'file', getFile: async () => new File([content], name) } as unknown as FileSystemFileHandle
    }
  }
  handle() { return this as unknown as Parameters<typeof writeMaintenanceSnapshot>[0] }
}

type GenerationFolders = { authors: MemoryDirectory; backups: MemoryDirectory }

async function generation(root: MemoryDirectory, name: '当前' | '上次'): Promise<GenerationFolders> {
  const gen = await root.getDirectoryHandle(name, { create: true })
  return {
    authors: await gen.getDirectoryHandle(AUTHOR_SECTION, { create: true }),
    backups: await gen.getDirectoryHandle(BACKUP_SECTION, { create: true }),
  }
}
async function fileText(folder: MemoryDirectory, name: string): Promise<string> {
  return (await (await folder.getFileHandle(name)).getFile()).text()
}
async function digest(text: string): Promise<string> {
  const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return Array.from(new Uint8Array(hash), byte => byte.toString(16).padStart(2, '0')).join('')
}
async function seedChecked(folder: MemoryDirectory, name: string, text: string): Promise<void> {
  folder.files.set(name, text)
  folder.files.set(`${name}.sha256`, await digest(text))
}
async function generationDigests(root: MemoryDirectory, name: '当前' | '上次'): Promise<Record<string, string>> {
  const folders = await generation(root, name)
  const digests: Record<string, string> = {}
  for (const file of GENERATION_FILES) {
    const folder = file.section === 'authors' ? folders.authors : folders.backups
    const body = await (await folder.getFileHandle(file.name)).getFile()
    digests[generationFileKey(file)] = await sha256(body)
  }
  return digests
}
function markerFile(old: Record<string, string>, next: Record<string, string>): string {
  return JSON.stringify({ format: 'qimeng_maintenance_rotation', version: 1, old, next })
}
describe('两代维护目录', () => {
  it('首次导出生成 当前/上次 两代骨架，当前代可直接读取', async () => {
    const root = new MemoryDirectory()
    const first = blob(1)
    await writeMaintenanceSnapshot(root.handle(), first, true)
    const current = await generation(root, '当前')
    expect(await fileText(current.backups, BACKUP_FILE_NAME)).toBe(await first.text())
    expect(await fileText(current.authors, MASTER_FILE_NAME)).toBe('generation 1')
    expect(await fileText(current.authors, AUTHOR_REPORT_FILE_NAME)).toContain('Synthetic')
    expect(current.authors.files.has(`${MASTER_FILE_NAME}.sha256`)).toBe(true)
    expect(current.backups.files.has(`${BACKUP_FILE_NAME}.sha256`)).toBe(true)
    const previous = await generation(root, '上次')
    expect(previous.authors.files.size).toBe(0)
    expect(previous.backups.files.size).toBe(0)
    expect(root.files.has(ROTATION_MARKER_FILE_NAME)).toBe(false)
    expect(root.files.get('目录说明.md')).toContain('两代轮换')
    expect(root.directories.has('上传归档')).toBe(true)
    expect((await readMaintenanceTxt(root.handle())).map(file => file.name)).toEqual([MASTER_FILE_NAME])
    expect(await (await readMaintenanceBackup(root.handle())).text()).toBe(await first.text())
  })

  it('内容变化先把当前整代提交为上次，再写新当前', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1), true)
    await writeMaintenanceSnapshot(root.handle(), blob(2))
    const current = await generation(root, '当前')
    const previous = await generation(root, '上次')
    expect(await fileText(current.authors, MASTER_FILE_NAME)).toBe('generation 2')
    expect(await fileText(previous.authors, MASTER_FILE_NAME)).toBe('generation 1')
    expect(await fileText(previous.authors, AUTHOR_REPORT_FILE_NAME)).toContain('Synthetic')
    expect(await fileText(previous.authors, AUTHOR_INDEX_FILE_NAME)).toBe(`["${MASTER_FILE_NAME}"]`)
    expect(await fileText(previous.backups, BACKUP_FILE_NAME)).toBe(await blob(1).text())
    expect(await fileText(current.authors, `${MASTER_FILE_NAME}.sha256`)).toBe(await digest('generation 2'))
    expect(await fileText(previous.authors, `${MASTER_FILE_NAME}.sha256`)).toBe(await digest('generation 1'))
    expect(await (await readMaintenanceBackup(root.handle(), 1)).text()).toBe(await blob(1).text())
    expect(root.files.has(ROTATION_MARKER_FILE_NAME)).toBe(false)
  })

  it('相同数据重导出只刷新当前、不轮换上次（且不重复轮换）', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    await writeMaintenanceSnapshot(root.handle(), blob(1, 100))
    let previous = await generation(root, '上次')
    expect(previous.authors.files.size).toBe(0)
    await writeMaintenanceSnapshot(root.handle(), blob(2))
    const previousBackup = previous.backups.files.get(BACKUP_FILE_NAME)
    await writeMaintenanceSnapshot(root.handle(), blob(2, 200), true)
    previous = await generation(root, '上次')
    const current = await generation(root, '当前')
    expect(await fileText(previous.authors, MASTER_FILE_NAME)).toBe('generation 1')
    expect(await fileText(current.authors, MASTER_FILE_NAME)).toBe('generation 2')
    expect(await fileText(current.backups, BACKUP_FILE_NAME)).toBe(await blob(2, 200).text())
    expect(previous.backups.files.get(BACKUP_FILE_NAME)).toBe(previousBackup)
    expect(root.files.has(ROTATION_MARKER_FILE_NAME)).toBe(false)
  })

  it('当前代摘要不符时拒绝覆盖并保留现场', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    const current = await generation(root, '当前')
    current.authors.files.set(MASTER_FILE_NAME, 'external edit')
    await expect(writeMaintenanceSnapshot(root.handle(), blob(2))).rejects.toThrow('摘要不符')
    expect(await fileText(current.authors, MASTER_FILE_NAME)).toBe('external edit')
    const previous = await generation(root, '上次')
    expect(previous.authors.files.size).toBe(0)
    expect(root.files.has(ROTATION_MARKER_FILE_NAME)).toBe(false)
  })

  it('当前代被外部改写且缺摘要时拒绝覆盖并保留现场', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    const current = await generation(root, '当前')
    current.authors.files.delete(`${MASTER_FILE_NAME}.sha256`)
    current.authors.files.set(MASTER_FILE_NAME, 'external edit')
    await expect(writeMaintenanceSnapshot(root.handle(), blob(2))).rejects.toThrow('缺少同名摘要')
    expect(await fileText(current.authors, MASTER_FILE_NAME)).toBe('external edit')
    expect(root.files.has(ROTATION_MARKER_FILE_NAME)).toBe(false)
  })

  it('上次目标摘要不符时拒绝轮换并保留现场', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    await writeMaintenanceSnapshot(root.handle(), blob(2))
    const previous = await generation(root, '上次')
    previous.backups.files.set(BACKUP_FILE_NAME, '{"pending":"external"}')
    await expect(writeMaintenanceSnapshot(root.handle(), blob(3))).rejects.toThrow('上次代')
    const current = await generation(root, '当前')
    expect(await fileText(current.authors, MASTER_FILE_NAME)).toBe('generation 2')
    expect(await fileText(current.backups, BACKUP_FILE_NAME)).toBe(await blob(2).text())
    expect(previous.backups.files.get(BACKUP_FILE_NAME)).toBe('{"pending":"external"}')
  })

  it('外部替换待导入备份时自动写回暂停，且不覆盖现场', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    const current = await generation(root, '当前')
    current.backups.files.set(BACKUP_FILE_NAME, '{"pending":"external"}')
    await expect(writeMaintenanceSnapshot(root.handle(), blob(1), true)).rejects.toThrow('外部替换')
    expect(current.backups.files.get(BACKUP_FILE_NAME)).toBe('{"pending":"external"}')
  })

  it('相同数据自动写回可修复中断留下的缺失摘要（正文未变即安全）', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    const current = await generation(root, '当前')
    current.authors.files.delete(`${MASTER_FILE_NAME}.sha256`)
    await writeMaintenanceSnapshot(root.handle(), blob(1), true)
    expect(await fileText(current.authors, `${MASTER_FILE_NAME}.sha256`)).toBe(await digest('generation 1'))
  })

  it('写当前备份摘要中断：状态文件留下断点，重试同数据自愈且不重复轮换上次代', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    root.failNextWrites(`当前/${BACKUP_SECTION}/${BACKUP_FILE_NAME}.sha256`)
    await expect(writeMaintenanceSnapshot(root.handle(), blob(2))).rejects.toThrow('模拟写入失败')
    expect(root.files.has(ROTATION_MARKER_FILE_NAME)).toBe(true)
    const previousBefore = await generation(root, '上次')
    const previousBackupBefore = previousBefore.backups.files.get(BACKUP_FILE_NAME)
    expect(await fileText(previousBefore.backups, BACKUP_FILE_NAME)).toBe(await blob(1).text())
    root.clearFailures()
    await writeMaintenanceSnapshot(root.handle(), blob(2))
    const current = await generation(root, '当前')
    const previous = await generation(root, '上次')
    expect(await fileText(current.backups, BACKUP_FILE_NAME)).toBe(await blob(2).text())
    expect(await fileText(current.authors, MASTER_FILE_NAME)).toBe('generation 2')
    expect(await fileText(current.backups, `${BACKUP_FILE_NAME}.sha256`)).toBe(await sha256(blob(2)))
    expect(previous.backups.files.get(BACKUP_FILE_NAME)).toBe(previousBackupBefore)
    expect(root.files.has(ROTATION_MARKER_FILE_NAME)).toBe(false)
  })

  it('换导出时刻（同语义、不同字节）在摘要中断后重试也能自愈', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    root.failNextWrites(`当前/${BACKUP_SECTION}/${BACKUP_FILE_NAME}.sha256`)
    await expect(writeMaintenanceSnapshot(root.handle(), blob(2))).rejects.toThrow('模拟写入失败')
    root.clearFailures()
    const reexported = blob(2, 999)
    await writeMaintenanceSnapshot(root.handle(), reexported)
    const current = await generation(root, '当前')
    expect(await fileText(current.backups, BACKUP_FILE_NAME)).toBe(await reexported.text())
    expect(await fileText(current.backups, `${BACKUP_FILE_NAME}.sha256`)).toBe(await sha256(reexported))
    expect(root.files.has(ROTATION_MARKER_FILE_NAME)).toBe(false)
  })

  it('轮换复制中断（上次备份摘要未写）：重试补摘要并完成轮换，而不是报摘要不符', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    root.failNextWrites(`${'上次'}/${BACKUP_SECTION}/${BACKUP_FILE_NAME}.sha256`)
    await expect(writeMaintenanceSnapshot(root.handle(), blob(2))).rejects.toThrow('模拟写入失败')
    expect(root.files.has(ROTATION_MARKER_FILE_NAME)).toBe(true)
    const partialPrevious = await generation(root, '上次')
    expect(await fileText(partialPrevious.backups, BACKUP_FILE_NAME)).toBe(await blob(1).text())
    expect(partialPrevious.backups.files.has(`${BACKUP_FILE_NAME}.sha256`)).toBe(false)
    root.clearFailures()
    await writeMaintenanceSnapshot(root.handle(), blob(2))
    const previous = await generation(root, '上次')
    const current = await generation(root, '当前')
    expect(await fileText(previous.backups, `${BACKUP_FILE_NAME}.sha256`)).toBe(await sha256(blob(1)))
    expect(await fileText(previous.authors, MASTER_FILE_NAME)).toBe('generation 1')
    expect(await fileText(current.authors, MASTER_FILE_NAME)).toBe('generation 2')
    expect(root.files.has(ROTATION_MARKER_FILE_NAME)).toBe(false)
  })

  it('轮换完成、写新当前前中断：重试续写当前代，不把旧当前再次轮换', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    root.failNextWrites(`当前/${BACKUP_SECTION}/${BACKUP_FILE_NAME}`)
    await expect(writeMaintenanceSnapshot(root.handle(), blob(2))).rejects.toThrow('模拟写入失败')
    const previousBefore = await generation(root, '上次')
    const previousBackupBefore = previousBefore.backups.files.get(BACKUP_FILE_NAME)
    expect(await fileText(previousBefore.backups, BACKUP_FILE_NAME)).toBe(await blob(1).text())
    root.clearFailures()
    await writeMaintenanceSnapshot(root.handle(), blob(2))
    const previous = await generation(root, '上次')
    const current = await generation(root, '当前')
    expect(previous.backups.files.get(BACKUP_FILE_NAME)).toBe(previousBackupBefore)
    expect(await fileText(current.backups, BACKUP_FILE_NAME)).toBe(await blob(2).text())
    expect(await fileText(current.authors, MASTER_FILE_NAME)).toBe('generation 2')
    expect(root.files.has(ROTATION_MARKER_FILE_NAME)).toBe(false)
  })

  it('中断后换了一份新快照：保留上次代，当前代按新快照重写（不把半成品当上次代）', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    root.failNextWrites(`当前/${BACKUP_SECTION}/${BACKUP_FILE_NAME}.sha256`)
    await expect(writeMaintenanceSnapshot(root.handle(), blob(2))).rejects.toThrow('模拟写入失败')
    root.clearFailures()
    await writeMaintenanceSnapshot(root.handle(), blob(3))
    const previous = await generation(root, '上次')
    const current = await generation(root, '当前')
    expect(await fileText(previous.authors, MASTER_FILE_NAME)).toBe('generation 1')
    expect(await fileText(previous.backups, BACKUP_FILE_NAME)).toBe(await blob(1).text())
    expect(await fileText(current.authors, MASTER_FILE_NAME)).toBe('generation 3')
    expect(await fileText(current.backups, BACKUP_FILE_NAME)).toBe(await blob(3).text())
    expect(root.files.has(ROTATION_MARKER_FILE_NAME)).toBe(false)
  })

  it('仅残留状态文件（两代都已写完）：清除标记后按正常流程刷新，不再轮换', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    await writeMaintenanceSnapshot(root.handle(), blob(2))
    const previousBefore = await generation(root, '上次')
    const previousBackupBefore = previousBefore.backups.files.get(BACKUP_FILE_NAME)
    const oldDigests = await generationDigests(root, '上次')
    const nextDigests = await generationDigests(root, '当前')
    root.files.set(ROTATION_MARKER_FILE_NAME, markerFile(oldDigests, nextDigests))
    await writeMaintenanceSnapshot(root.handle(), blob(2, 500))
    const previous = await generation(root, '上次')
    const current = await generation(root, '当前')
    expect(previous.backups.files.get(BACKUP_FILE_NAME)).toBe(previousBackupBefore)
    expect(await fileText(current.authors, MASTER_FILE_NAME)).toBe('generation 2')
    expect(await fileText(current.backups, BACKUP_FILE_NAME)).toBe(await blob(2, 500).text())
    expect(root.files.has(ROTATION_MARKER_FILE_NAME)).toBe(false)
  })

  it('状态文件损坏时停止写入并保留现场', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    root.files.set(ROTATION_MARKER_FILE_NAME, 'not json')
    await expect(writeMaintenanceSnapshot(root.handle(), blob(2))).rejects.toThrow('轮换状态文件无法解析')
    expect(root.files.get(ROTATION_MARKER_FILE_NAME)).toBe('not json')
    expect(await fileText((await generation(root, '当前')).authors, MASTER_FILE_NAME)).toBe('generation 1')
  })

  it('导入前快照轮换中途中断：重试补摘要并写入当前备份，作者资料保持原代', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    root.failNextWrites(`${'上次'}/${BACKUP_SECTION}/${BACKUP_FILE_NAME}.sha256`)
    await expect(writePreImportSnapshot(root.handle(), blob(2))).rejects.toThrow('模拟写入失败')
    root.clearFailures()
    await writePreImportSnapshot(root.handle(), blob(2))
    const current = await generation(root, '当前')
    const previous = await generation(root, '上次')
    expect(await fileText(current.backups, BACKUP_FILE_NAME)).toBe(await blob(2).text())
    expect(await fileText(current.backups, `${BACKUP_FILE_NAME}.sha256`)).toBe(await sha256(blob(2)))
    expect(await fileText(previous.backups, `${BACKUP_FILE_NAME}.sha256`)).toBe(await sha256(blob(1)))
    expect(await fileText(previous.backups, BACKUP_FILE_NAME)).toBe(await blob(1).text())
    expect(await fileText(current.authors, MASTER_FILE_NAME)).toBe('generation 1')
  })

  it('导入前快照写当前备份摘要中断：重试以语义相同的新导出修复', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    root.failNextWrites(`当前/${BACKUP_SECTION}/${BACKUP_FILE_NAME}.sha256`)
    await expect(writePreImportSnapshot(root.handle(), blob(2))).rejects.toThrow('模拟写入失败')
    const previousBefore = await generation(root, '上次')
    const previousBackupBefore = previousBefore.backups.files.get(BACKUP_FILE_NAME)
    root.clearFailures()
    const reexported = blob(2, 777)
    await writePreImportSnapshot(root.handle(), reexported)
    const current = await generation(root, '当前')
    const previous = await generation(root, '上次')
    expect(previous.backups.files.get(BACKUP_FILE_NAME)).toBe(previousBackupBefore)
    expect(await fileText(current.backups, BACKUP_FILE_NAME)).toBe(await reexported.text())
    expect(await fileText(current.backups, `${BACKUP_FILE_NAME}.sha256`)).toBe(await sha256(reexported))
  })

  it('两代布局下“上上次”入口给出明确说明', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    await expect(readMaintenanceBackup(root.handle(), 2)).rejects.toThrow('两代布局')
  })

  it('备份摘要不一致时读取拒绝', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    const current = await generation(root, '当前')
    current.backups.files.set(BACKUP_FILE_NAME, '{}')
    await expect(readMaintenanceBackup(root.handle())).rejects.toThrow('摘要不一致')
  })

  it('旧三代同目录文件只读兼容、不删除，并可列出提示', async () => {
    const root = new MemoryDirectory()
    const legacyAuthors = await root.getDirectoryHandle(AUTHOR_SECTION, { create: true })
    const legacyBackups = await root.getDirectoryHandle(BACKUP_SECTION, { create: true })
    await seedChecked(legacyAuthors, MASTER_FILE_NAME, 'legacy current')
    await seedChecked(legacyAuthors, '作者总表（上次）.txt', 'legacy previous')
    await seedChecked(legacyAuthors, '作者总表（上上次）.txt', 'legacy older')
    await seedChecked(legacyBackups, '影库备份.json', '{"data":{"authorMasterTxt":"legacy current"}}')
    await seedChecked(legacyBackups, '影库备份（上次）.json', '{"data":{"authorMasterTxt":"legacy previous"}}')
    await seedChecked(legacyBackups, '影库备份（上上次）.json', '{"data":{"authorMasterTxt":"legacy older"}}')
    expect((await readMaintenanceTxt(root.handle())).map(file => file.name)).toEqual([MASTER_FILE_NAME])
    expect(await (await readMaintenanceBackup(root.handle(), 0)).text()).toContain('legacy current')
    expect(await (await readMaintenanceBackup(root.handle(), 1)).text()).toContain('legacy previous')
    expect(await (await readMaintenanceBackup(root.handle(), 2)).text()).toContain('legacy older')
    expect(await findLegacyGenerationFiles(root.handle())).toEqual([
      `${AUTHOR_SECTION}/${MASTER_FILE_NAME}`,
      `${AUTHOR_SECTION}/作者总表（上次）.txt`,
      `${AUTHOR_SECTION}/作者总表（上上次）.txt`,
      `${BACKUP_SECTION}/${BACKUP_FILE_NAME}`,
      `${BACKUP_SECTION}/影库备份（上次）.json`,
      `${BACKUP_SECTION}/影库备份（上上次）.json`,
    ])
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    expect(legacyAuthors.files.get('作者总表（上上次）.txt')).toBe('legacy older')
    expect(legacyBackups.files.has('影库备份（上上次）.json')).toBe(true)
    expect(await findLegacyGenerationFiles(root.handle())).toHaveLength(6)
  })

  it('作者片段文件名不安全或重复时在创建目录前拒绝', async () => {
    const root = new MemoryDirectory()
    for (const filename of ['../bad.txt', 'folder/bad.txt', 'folder\\bad.txt']) {
      const bad = { ...snapshot, data: { ...snapshot.data, txtFragments: [{ filename, content: 'bad' }] } }
      await expect(writeMaintenanceSnapshot(root.handle(), new Blob([JSON.stringify(bad)]))).rejects.toThrow('不安全')
    }
    const duplicate = { ...snapshot, data: { ...snapshot.data, txtFragments: [
      { filename: 'authors.txt', content: 'one' }, { filename: 'AUTHORS.TXT', content: 'two' },
    ] } }
    await expect(writeMaintenanceSnapshot(root.handle(), new Blob([JSON.stringify(duplicate)]))).rejects.toThrow('重复')
    expect(root.directories.size).toBe(0)
    expect(safeMaintenanceName('..')).toBe(false)
    expect(safeMaintenanceName('正常作者清单.txt')).toBe(true)
  })

  it('渲染当前关联与简介的阅读版作者资料', () => {
    const report = maintenanceAuthorReport(snapshot)
    expect(report).toContain('内部ID `synthetic`')
    expect(report).toContain('Original biography')
    expect(report).toContain('- synthetic.png')
  })

  it('导入前保存写入当前备份代并参与两代轮换，作者资料保持原代', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    await writePreImportSnapshot(root.handle(), blob(2))
    const current = await generation(root, '当前')
    const previous = await generation(root, '上次')
    expect(await fileText(current.backups, BACKUP_FILE_NAME)).toBe(await blob(2).text())
    expect(await fileText(current.authors, MASTER_FILE_NAME)).toBe('generation 1')
    expect(await fileText(previous.backups, BACKUP_FILE_NAME)).toBe(await blob(1).text())
    expect(previous.authors.files.size).toBe(0)
    await writeMaintenanceSnapshot(root.handle(), blob(3))
    const rotated = await generation(root, '上次')
    expect(await fileText(rotated.backups, BACKUP_FILE_NAME)).toBe(await blob(2).text())
    expect(await fileText(rotated.authors, MASTER_FILE_NAME)).toBe('generation 1')
    expect(await fileText(current.authors, MASTER_FILE_NAME)).toBe('generation 3')
  })

  it('选择目录时创建两代骨架、上传归档与目录说明', async () => {
    const root = new MemoryDirectory()
    const scope = globalThis as unknown as Record<string, unknown>
    const previousWindow = scope.window
    const previousLocation = scope.location
    scope.window = { showDirectoryPicker: async () => root }
    scope.location = { origin: 'http://test.invalid' }
    try {
      expect(maintenanceFolderSupported()).toBe(true)
      await chooseMaintenanceFolder()
      for (const name of ['当前', '上次'] as const) {
        const gen = await root.getDirectoryHandle(name)
        expect(gen.directories.has(AUTHOR_SECTION)).toBe(true)
        expect(gen.directories.has(BACKUP_SECTION)).toBe(true)
      }
      expect(root.directories.has('上传归档')).toBe(true)
      expect(String(root.files.get('目录说明.md'))).toContain('两代轮换')
    } finally {
      scope.window = previousWindow
      scope.location = previousLocation
    }
  })
})
