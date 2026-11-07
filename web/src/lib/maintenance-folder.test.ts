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

class MemoryDirectory {
  files = new Map<string, Blob | string>()
  directories = new Map<string, MemoryDirectory>()
  async requestPermission(): Promise<PermissionState> { return 'granted' }
  async queryPermission(): Promise<PermissionState> { return 'granted' }
  async getDirectoryHandle(name: string, options?: { create?: boolean }): Promise<MemoryDirectory> {
    if (!this.directories.has(name)) {
      if (!options?.create) throw new DOMException('missing', 'NotFoundError')
      this.directories.set(name, new MemoryDirectory())
    }
    return this.directories.get(name)!
  }
  async getFileHandle(name: string, options?: { create?: boolean }) {
    const files = this.files
    if (!options?.create && !files.has(name)) throw new DOMException('missing', 'NotFoundError')
    return {
      name,
      kind: 'file' as const,
      createWritable: async () => ({
        write: async (content: Blob | string) => { files.set(name, content) },
        close: async () => undefined,
        abort: async () => undefined,
      }),
      getFile: async () => new File([files.get(name) ?? new Blob()], name),
    }
  }
  async *values(): AsyncIterable<FileSystemHandle> {
    for (const [name, content] of this.files) {
      yield { name, kind: 'file', getFile: async () => new File([content], name) } as unknown as FileSystemFileHandle
    }
  }
  handle() { return this as unknown as Parameters<typeof writeMaintenanceSnapshot>[0] }
}

async function generation(root: MemoryDirectory, name: '当前' | '上次') {
  const gen = await root.getDirectoryHandle(name, { create: true })
  return {
    authors: await gen.getDirectoryHandle('作者资料', { create: true }),
    backups: await gen.getDirectoryHandle('备份', { create: true }),
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

describe('两代维护目录', () => {
  it('首次导出生成 当前/上次 两代骨架，当前代可直接读取', async () => {
    const root = new MemoryDirectory()
    const first = blob(1)
    await writeMaintenanceSnapshot(root.handle(), first, true)
    const current = await generation(root, '当前')
    expect(await fileText(current.backups, '影库备份.json')).toBe(await first.text())
    expect(await fileText(current.authors, '作者总表.txt')).toBe('generation 1')
    expect(await fileText(current.authors, '作者资料.md')).toContain('Synthetic')
    expect(current.authors.files.has('作者总表.txt.sha256')).toBe(true)
    expect(current.backups.files.has('影库备份.json.sha256')).toBe(true)
    const previous = await generation(root, '上次')
    expect(previous.authors.files.size).toBe(0)
    expect(previous.backups.files.size).toBe(0)
    expect(root.files.get('目录说明.md')).toContain('两代轮换')
    expect(root.directories.has('上传归档')).toBe(true)
    expect((await readMaintenanceTxt(root.handle())).map(file => file.name)).toEqual(['作者总表.txt'])
    expect(await (await readMaintenanceBackup(root.handle())).text()).toBe(await first.text())
  })

  it('内容变化先把当前整代提交为上次，再写新当前', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1), true)
    await writeMaintenanceSnapshot(root.handle(), blob(2))
    const current = await generation(root, '当前')
    const previous = await generation(root, '上次')
    expect(await fileText(current.authors, '作者总表.txt')).toBe('generation 2')
    expect(await fileText(previous.authors, '作者总表.txt')).toBe('generation 1')
    expect(await fileText(previous.authors, '作者资料.md')).toContain('Synthetic')
    expect(await fileText(previous.authors, '作者片段索引.json')).toBe('["作者总表.txt"]')
    expect(await fileText(previous.backups, '影库备份.json')).toBe(await blob(1).text())
    const [currentSha, previousSha] = [await fileText(current.authors, '作者总表.txt.sha256'), await fileText(previous.authors, '作者总表.txt.sha256')]
    expect(currentSha).toBe(await digest('generation 2'))
    expect(previousSha).toBe(await digest('generation 1'))
    expect((await readMaintenanceBackup(root.handle(), 1)).size).toBeGreaterThan(0)
    expect(await (await readMaintenanceBackup(root.handle(), 1)).text()).toBe(await blob(1).text())
  })

  it('相同数据重导出只刷新当前、不轮换上次', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    await writeMaintenanceSnapshot(root.handle(), blob(1, 100))
    let previous = await generation(root, '上次')
    expect(previous.authors.files.size).toBe(0)
    await writeMaintenanceSnapshot(root.handle(), blob(2))
    await writeMaintenanceSnapshot(root.handle(), blob(2, 200), true)
    previous = await generation(root, '上次')
    const current = await generation(root, '当前')
    expect(await fileText(previous.authors, '作者总表.txt')).toBe('generation 1')
    expect(await fileText(current.authors, '作者总表.txt')).toBe('generation 2')
    expect(await fileText(current.backups, '影库备份.json')).toBe(await blob(2, 200).text())
  })

  it('当前代摘要不符时拒绝覆盖并保留现场', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    const current = await generation(root, '当前')
    current.authors.files.set('作者总表.txt', 'external edit')
    await expect(writeMaintenanceSnapshot(root.handle(), blob(2))).rejects.toThrow('摘要不符')
    expect(await fileText(current.authors, '作者总表.txt')).toBe('external edit')
    const previous = await generation(root, '上次')
    expect(previous.authors.files.size).toBe(0)
  })

  it('上次目标摘要不符时拒绝轮换并保留现场', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    await writeMaintenanceSnapshot(root.handle(), blob(2))
    const previous = await generation(root, '上次')
    previous.backups.files.set('影库备份.json', '{"pending":"external"}')
    await expect(writeMaintenanceSnapshot(root.handle(), blob(3))).rejects.toThrow('上次代')
    const current = await generation(root, '当前')
    expect(await fileText(current.authors, '作者总表.txt')).toBe('generation 2')
    expect(await fileText(current.backups, '影库备份.json')).toBe(await blob(2).text())
    expect(previous.backups.files.get('影库备份.json')).toBe('{"pending":"external"}')
  })

  it('外部替换待导入备份时自动写回暂停，且不覆盖现场', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    const current = await generation(root, '当前')
    current.backups.files.set('影库备份.json', '{"pending":"external"}')
    await expect(writeMaintenanceSnapshot(root.handle(), blob(1), true)).rejects.toThrow('外部替换')
    expect(current.backups.files.get('影库备份.json')).toBe('{"pending":"external"}')
  })

  it('缺摘要的当前代只允许手动导出修复，自动写回暂停', async () => {
    const root = new MemoryDirectory()
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    const current = await generation(root, '当前')
    current.authors.files.delete('作者总表.txt.sha256')
    await expect(writeMaintenanceSnapshot(root.handle(), blob(1), true)).rejects.toThrow('自动写回已暂停')
    await writeMaintenanceSnapshot(root.handle(), blob(1))
    expect(await fileText(current.authors, '作者总表.txt.sha256')).toBe(await digest('generation 1'))
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
    current.backups.files.set('影库备份.json', '{}')
    await expect(readMaintenanceBackup(root.handle())).rejects.toThrow('摘要不一致')
  })

  it('旧三代同目录文件只读兼容、不删除，并可列出提示', async () => {
    const root = new MemoryDirectory()
    const legacyAuthors = await root.getDirectoryHandle('作者资料', { create: true })
    const legacyBackups = await root.getDirectoryHandle('备份', { create: true })
    await seedChecked(legacyAuthors, '作者总表.txt', 'legacy current')
    await seedChecked(legacyAuthors, '作者总表（上次）.txt', 'legacy previous')
    await seedChecked(legacyAuthors, '作者总表（上上次）.txt', 'legacy older')
    await seedChecked(legacyBackups, '影库备份.json', '{"data":{"authorMasterTxt":"legacy current"}}')
    await seedChecked(legacyBackups, '影库备份（上次）.json', '{"data":{"authorMasterTxt":"legacy previous"}}')
    await seedChecked(legacyBackups, '影库备份（上上次）.json', '{"data":{"authorMasterTxt":"legacy older"}}')
    expect((await readMaintenanceTxt(root.handle())).map(file => file.name)).toEqual(['作者总表.txt'])
    expect(await (await readMaintenanceBackup(root.handle(), 0)).text()).toContain('legacy current')
    expect(await (await readMaintenanceBackup(root.handle(), 1)).text()).toContain('legacy previous')
    expect(await (await readMaintenanceBackup(root.handle(), 2)).text()).toContain('legacy older')
    expect(await findLegacyGenerationFiles(root.handle())).toEqual([
      '作者资料/作者总表.txt',
      '作者资料/作者总表（上次）.txt',
      '作者资料/作者总表（上上次）.txt',
      '备份/影库备份.json',
      '备份/影库备份（上次）.json',
      '备份/影库备份（上上次）.json',
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
    expect(await fileText(current.backups, '影库备份.json')).toBe(await blob(2).text())
    expect(await fileText(current.authors, '作者总表.txt')).toBe('generation 1')
    expect(await fileText(previous.backups, '影库备份.json')).toBe(await blob(1).text())
    expect(previous.authors.files.size).toBe(0)
    await writeMaintenanceSnapshot(root.handle(), blob(3))
    const rotated = await generation(root, '上次')
    expect(await fileText(rotated.backups, '影库备份.json')).toBe(await blob(2).text())
    expect(await fileText(rotated.authors, '作者总表.txt')).toBe('generation 1')
    expect(await fileText(current.authors, '作者总表.txt')).toBe('generation 3')
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
        expect(gen.directories.has('作者资料')).toBe(true)
        expect(gen.directories.has('备份')).toBe(true)
      }
      expect(root.directories.has('上传归档')).toBe(true)
      expect(String(root.files.get('目录说明.md'))).toContain('两代轮换')
    } finally {
      scope.window = previousWindow
      scope.location = previousLocation
    }
  })
})
