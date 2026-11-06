import { describe, expect, it } from 'vitest'
import type { LegacyBackupImport } from '@/api/generated'
import { maintenanceAuthorReport, readMaintenanceBackup, readMaintenanceTxt, safeMaintenanceName, writeMaintenanceSnapshot, writePreImportSnapshot } from './maintenance-folder'

const snapshot: LegacyBackupImport = {
  format: 'qimeng_backup', schemaVersion: 2, appIdentifier: 'com.qimeng.media', exportedAtMillis: 1000,
  data: { authorMasterTxt: '1  Synthetic\n作品\nsynthetic.png\n', viewEvents: [], authors: [{ authorId: 'synthetic', displayName: 'Synthetic',
    profileJson: JSON.stringify({ status: 'verified', xProfile: { biography: 'Original biography', biographyZh: '合成中文简介' } }) }],
  authorMediaRefs: [{ authorId: 'synthetic', recordKey: 'one', fileName: 'synthetic.png' }],
  txtFragments: [{ importedAtMillis: 0, filename: 'authors.txt', content: '1  Synthetic\r\n作品\r\nsynthetic.png\r\n' }] },
}

class MemoryDirectory {
  files = new Map<string, Blob | string>()
  directories = new Map<string, MemoryDirectory>()
  async requestPermission() { return 'granted' }
  async getDirectoryHandle(name: string, options?: { create?: boolean }): Promise<MemoryDirectory> {
    if (!this.directories.has(name)) {
      if (!options?.create) throw new DOMException('missing', 'NotFoundError')
      this.directories.set(name, new MemoryDirectory())
    }
    return this.directories.get(name)!
  }
  async getFileHandle(name: string, options?: { create?: boolean }) {
    if (!options?.create && !this.files.has(name)) throw new DOMException('missing', 'NotFoundError')
    return {
      createWritable: async () => ({ write: async (content: Blob | string) => { this.files.set(name, content) }, close: async () => {}, abort: async () => {} }),
      getFile: async () => new File([this.files.get(name)!], name),
    }
  }
  handle() { return this as unknown as Parameters<typeof writeMaintenanceSnapshot>[0] }
}

describe('unified maintenance snapshot', () => {
  it('writes a readable current bundle without raw fragment directories', async () => {
    const root = new MemoryDirectory()
    const backups = await root.getDirectoryHandle('备份', { create: true })
    const blob = new Blob([JSON.stringify(snapshot)])
    await writeMaintenanceSnapshot(root.handle(), blob, true)
    expect(backups.files.get('影库备份.json')).toBe(blob)
    const authors = await root.getDirectoryHandle('作者资料')
    expect(authors.directories.has('原始片段')).toBe(false)
    expect(await (await (await authors.getFileHandle('作者总表.txt')).getFile()).text()).toBe(snapshot.data.authorMasterTxt)
    expect(authors.files.get('作者资料.md')).toContain('合成中文简介')
    authors.files.set('stale.txt', '1  Removed')
    expect((await readMaintenanceTxt(root.handle())).map(file => file.name)).toEqual(['作者总表.txt'])
  })

  it('rejects escaping and duplicate names before replacing a backup', async () => {
    const root = new MemoryDirectory()
    for (const filename of ['../bad.txt', 'folder/bad.txt', 'folder\\bad.txt']) {
      const bad = { ...snapshot, data: { ...snapshot.data, txtFragments: [{ filename, content: 'bad' }] } }
      await expect(writeMaintenanceSnapshot(root.handle(), new Blob([JSON.stringify(bad)]))).rejects.toThrow('不安全')
    }
    expect(root.directories.size).toBe(0)
    expect(safeMaintenanceName('..')).toBe(false)
    expect(safeMaintenanceName('正常作者清单.txt')).toBe(true)
  })

  it('renders current associations and profile text in the readable report', () => {
    const report = maintenanceAuthorReport(snapshot)
    expect(report).toContain('内部ID `synthetic`')
    expect(report).toContain('Original biography')
    expect(report).toContain('- synthetic.png')
  })

  it('checks backup bytes and shares import protection with the same three-generation chain', async () => {
    const root = new MemoryDirectory()
    const blob = new Blob([JSON.stringify(snapshot)])
    await writeMaintenanceSnapshot(root.handle(), blob)
    const backups = await root.getDirectoryHandle('备份')
    expect(await (await readMaintenanceBackup(root.handle())).text()).toBe(await blob.text())
    await writePreImportSnapshot(root.handle(), blob)
    expect(backups.files.has('影库备份.json.sha256')).toBe(true)
    expect(backups.files.has('qimeng_backup.before-import.json')).toBe(false)
    expect(backups.files.size).toBe(2)
    backups.files.set('影库备份.json', '{}')
    await expect(readMaintenanceBackup(root.handle())).rejects.toThrow('摘要不一致')
  })
  it('keeps only latest, last and older data across manual, automatic and pre-import writes', async () => {
    const root = new MemoryDirectory()
    const blob = (index: number, time = index) => new Blob([JSON.stringify({ ...snapshot, exportedAtMillis: time,
      data: { ...snapshot.data, authorMasterTxt: `generation ${index}` } })])
    for (let index = 1; index <= 6; index++) await writeMaintenanceSnapshot(root.handle(), blob(index), index % 2 === 0)
    await writeMaintenanceSnapshot(root.handle(), blob(6, 100), true)
    await writePreImportSnapshot(root.handle(), blob(6, 200))
    const backups = await root.getDirectoryHandle('备份')
    const authors = await root.getDirectoryHandle('作者资料')
    expect(backups.files.size).toBe(6)
    for (const [index, suffix] of [[6, ''], [5, '（上次）'], [4, '（上上次）']] as const) {
      const file = await (await backups.getFileHandle(`影库备份${suffix}.json`)).getFile()
      expect(JSON.parse(await file.text()).data.authorMasterTxt).toBe(`generation ${index}`)
      expect(await (await (await authors.getFileHandle(`作者总表${suffix}.txt`)).getFile()).text()).toBe(`generation ${index}`)
    }
    expect(JSON.parse(await (await readMaintenanceBackup(root.handle(), 2)).text()).data.authorMasterTxt).toBe('generation 4')
  })

  it('protects an externally replaced pending import from automatic output', async () => {
    const root = new MemoryDirectory()
    const blob = new Blob([JSON.stringify(snapshot)])
    await writeMaintenanceSnapshot(root.handle(), blob)
    const backups = await root.getDirectoryHandle('备份')
    backups.files.set('影库备份.json', '{"pending":"external"}')
    await expect(writeMaintenanceSnapshot(root.handle(), blob, true)).rejects.toThrow('外部替换')
    expect(backups.files.get('影库备份.json')).toBe('{"pending":"external"}')
  })

})
