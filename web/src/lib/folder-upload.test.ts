import { describe, expect, it } from 'vitest'
import {
  collectDroppedFiles,
  extractDropEntries,
  uploadTargetFromRelativePath,
  type DirectoryEntryLike,
  type EntryLike,
  type FileEntryLike,
} from './folder-upload'

/** Fake File（vitest node 环境无 DOM File，构造 duck-typed 最小面） */
function fakeFile(name: string, size = 1): File {
  return { name, size } as unknown as File
}

function fakeFileEntry(name: string, file = fakeFile(name)): FileEntryLike {
  return { isFile: true, isDirectory: false, name, file: (ok) => ok(file) }
}

function fakeDirEntry(name: string, children: EntryLike[]): DirectoryEntryLike {
  let called = false
  return {
    isFile: false,
    isDirectory: true,
    name,
    // readEntries 分批契约：children 一批给完，之后必须回空数组收尾
    createReader: () => ({
      readEntries: (ok) => {
        const batch = called ? [] : children
        called = true
        ok(batch)
      },
    }),
  }
}

describe('extractDropEntries', () => {
  const asItemList = (items: unknown[]): DataTransferItemList =>
    ({ length: items.length, ...Object.fromEntries(items.map((it, i) => [i, it])) }) as unknown as DataTransferItemList

  it('逐条调 webkitGetAsEntry 收集非空 entry', () => {
    const a = fakeFileEntry('a.jpg')
    const list = asItemList([{ webkitGetAsEntry: () => a }, { webkitGetAsEntry: () => null }])
    expect(extractDropEntries(list)).toEqual([a])
  })

  it('webkitGetAsEntry 不存在（旧环境）/抛错：跳过该条目，返回空数组供回退 files', () => {
    const list = asItemList([{}, { webkitGetAsEntry: () => { throw new Error('boom') } }])
    expect(extractDropEntries(list)).toEqual([])
  })
})

describe('collectDroppedFiles', () => {
  it('单文件条目：relativePath = 文件名', async () => {
    const file = fakeFile('x.jpg')
    const dropped = await collectDroppedFiles(fakeFileEntry('x.jpg', file))
    expect(dropped).toEqual([{ file, relativePath: 'x.jpg' }])
  })

  it('目录递归：目录名进路径前缀；文件+文件夹混合拖拽各自成条', async () => {
    const root = fakeDirEntry('batch', [
      fakeFileEntry('a.jpg'),
      fakeDirEntry('sub', [fakeFileEntry('b.jpg')]),
    ])
    const dropped = await collectDroppedFiles(root)
    expect(dropped.map((d) => d.relativePath)).toEqual(['batch/a.jpg', 'batch/sub/b.jpg'])
  })

  it('readEntries 分批：循环读到空数组为止，不丢后续批次', async () => {
    const f1 = fakeFileEntry('1.jpg')
    const f2 = fakeFileEntry('2.jpg')
    // 模拟 readEntries 分批语义：第一批 1 个、第二批 1 个、之后恒空
    const batches: EntryLike[][] = [[f1], [f2], [], []]
    let call = 0
    const dir: DirectoryEntryLike = {
      isFile: false,
      isDirectory: true,
      name: 'd',
      createReader: () => ({
        readEntries: (ok) => ok(batches[Math.min(call++, batches.length - 1)]),
      }),
    }
    const dropped = await collectDroppedFiles(dir)
    expect(dropped.map((d) => d.relativePath)).toEqual(['d/1.jpg', 'd/2.jpg'])
  })

  it('文件读取失败：整批拒绝（调用方 catch 后整批报错，不静默丢文件）', async () => {
    const bad: FileEntryLike = {
      isFile: true,
      isDirectory: false,
      name: 'bad.jpg',
      file: (_ok, err) => err?.(new Error('unreadable')),
    }
    await expect(collectDroppedFiles(bad)).rejects.toThrow('unreadable')
  })
})

describe('uploadTargetFromRelativePath', () => {
  const cases: Array<{ path: string; batchDir: string; want: { dir: string; filename: string } | null }> = [
    { path: 'a/b/c.jpg', batchDir: '', want: { dir: 'a/b', filename: 'c.jpg' } },
    { path: 'c.jpg', batchDir: '', want: { dir: '', filename: 'c.jpg' } },
    { path: 'a/b/c.jpg', batchDir: 'root', want: { dir: 'root/a/b', filename: 'c.jpg' } },
    { path: 'sub/c.jpg', batchDir: 'root/deep', want: { dir: 'root/deep/sub', filename: 'c.jpg' } },
    { path: './a/./b.jpg', batchDir: '', want: { dir: 'a', filename: 'b.jpg' } },
    { path: 'a//b.jpg', batchDir: '', want: { dir: 'a', filename: 'b.jpg' } },
    { path: '../escape.jpg', batchDir: '', want: null },
    { path: 'a/../../escape.jpg', batchDir: '', want: null },
    { path: 'a/b.jpg', batchDir: '../escape', want: null },
    { path: '/abs/c.jpg', batchDir: '', want: null },
    { path: 'C:\\x\\y.jpg', batchDir: '', want: null },
    { path: 'a/', batchDir: '', want: null },
    { path: '', batchDir: '', want: null },
  ]
  for (const { path, batchDir, want } of cases) {
    it(`"${path}" + 批次目录 "${batchDir}" → ${JSON.stringify(want)}`, () => {
      expect(uploadTargetFromRelativePath(path, batchDir)).toEqual(want)
    })
  }
})
