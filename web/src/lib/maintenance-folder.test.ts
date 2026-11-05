import { describe, expect, it } from 'vitest'
import type { LegacyBackupImport } from '@/api/generated'
import { maintenanceAuthorReport, readMaintenanceTxt, safeMaintenanceName, writeMaintenanceSnapshot } from './maintenance-folder'

const snapshot: LegacyBackupImport = {
  format: 'qimeng_backup', schemaVersion: 2, appIdentifier: 'com.qimeng.media', exportedAtMillis: 1000,
  data: { viewEvents: [], authors: [{ authorId: 'synthetic', displayName: 'Synthetic',
    profileJson: JSON.stringify({ status: 'verified', xProfile: { biography: 'Original biography', biographyZh: '合成中文简介' } }) }],
  authorMediaRefs: [{ authorId: 'synthetic', recordKey: 'one', fileName: 'synthetic.png' }],
  txtFragments: [{ filename: 'authors.txt', content: '1  Synthetic\r\n作品\r\nsynthetic.png\r\n' }] },
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
  it('keeps the manual slot while writing automatic output and original TXT bytes', async () => {
    const root = new MemoryDirectory()
    const backups = await root.getDirectoryHandle('备份', { create: true })
    backups.files.set('qimeng_backup.json', 'old manual')
    const blob = new Blob([JSON.stringify(snapshot)])
    await writeMaintenanceSnapshot(root.handle(), blob, true)
    expect(backups.files.get('qimeng_backup.json')).toBe('old manual')
    expect(backups.files.get('qimeng_backup.auto.json')).toBe(blob)
    const authors = await root.getDirectoryHandle('作者资料')
    expect(authors.files.get('authors.txt')).toBe(snapshot.data.txtFragments![0].content)
    expect(authors.files.get('作者资料.md')).toContain('合成中文简介')
    authors.files.set('stale.txt', '1  Removed')
    expect((await readMaintenanceTxt(root.handle())).map(file => file.name)).toEqual(['authors.txt'])
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
})
