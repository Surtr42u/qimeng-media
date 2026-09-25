/**
 * 拖拽目录上传纯逻辑（上传卡 onDrop 消费；2026-09-25 用户拍板：无
 * webkitdirectory 选择按钮，拖拽是文件夹上传的唯一入口）。
 *
 * 两块职责：
 * 1. entry 遍历——DataTransferItem.webkitGetAsEntry() 拿到的文件/目录 entry
 *    递归展开成 {file, relativePath} 列表（文件+文件夹混合拖拽都支持）；
 *    webkitGetAsEntry 不存在的环境返回空列表，调用方回退 e.dataTransfer.files。
 * 2. 路径换算——relativePath → {dir, filename}：目录部分拼到批次 targetDir
 *    下、文件名取末段；拒绝 `..` 段与绝对路径（库内路径安全，返回 null 由
 *    调用方拦截该条目）。
 *
 * 行为由 folder-upload.test.ts 锁定（Fake 条目用普通对象实现接口，vitest
 * node 环境零依赖）。
 */

/** webkitGetAsEntry 的最小结构面（只声明本库消费的字段；测试用普通对象即可实现） */
export interface FileEntryLike {
  isFile: true
  isDirectory: false
  name: string
  file: (success: (file: File) => void, error?: (err: unknown) => void) => void
}

export interface DirectoryEntryLike {
  isFile: false
  isDirectory: true
  name: string
  createReader: () => {
    readEntries: (
      success: (entries: EntryLike[]) => void,
      error?: (err: unknown) => void,
    ) => void
  }
}

export type EntryLike = FileEntryLike | DirectoryEntryLike

/** 拖拽收集产物：文件句柄 + 拖入时的相对路径（含目录部分；单文件拖入=文件名） */
export interface DroppedFile {
  file: File
  relativePath: string
}

/**
 * 同步提取拖拽条目的 webkit entry。必须在 drop 事件回调内同步调用——
 * DataTransferItem 在事件派发结束后即失效，异步再取会全为 null。
 * 兼容守卫：webkitGetAsEntry 不存在/调用抛错/返回 null（非文件条目，
 * 如拖文本）一律跳过；全部跳过时返回空数组，调用方据此回退 files 列表。
 */
export function extractDropEntries(items: DataTransferItemList): EntryLike[] {
  const out: EntryLike[] = []
  for (let i = 0; i < items.length; i++) {
    const item = items[i]
    if (!item) continue
    // 运行时存在性检查（TS DOM 类型声明了该方法，但旧浏览器/非文件条目场景仍需守卫）
    const getter = (item as { webkitGetAsEntry?: unknown }).webkitGetAsEntry
    if (typeof getter !== 'function') continue
    try {
      const entry = (getter as () => EntryLike | null).call(item)
      if (entry) out.push(entry)
    } catch {
      // 单条提取失败不影响其余条目
    }
  }
  return out
}

/** FileEntry.file 回调 → Promise（error 回调以拒绝收尾，调用方整批报错） */
function entryToFile(entry: FileEntryLike): Promise<File> {
  return new Promise((resolve, reject) => {
    entry.file(resolve, (err) => reject(err instanceof Error ? err : new Error(String(err))))
  })
}

/**
 * 读完一个目录的全部子条目。readEntries 每次最多返回一批（规范未定上限，
 * 常见 100），必须循环调用直到返回空数组——只读一批会静默丢文件。
 */
function readAllEntries(dir: DirectoryEntryLike): Promise<EntryLike[]> {
  const reader = dir.createReader()
  return new Promise((resolve, reject) => {
    const all: EntryLike[] = []
    const step = (): void => {
      reader.readEntries((batch) => {
        if (batch.length === 0) {
          resolve(all)
          return
        }
        all.push(...batch)
        step()
      }, (err) => reject(err instanceof Error ? err : new Error(String(err))))
    }
    step()
  })
}

/**
 * 递归收集单个入口（文件直接收；目录递归展开，目录名进 relativePath）。
 * 任一条目读取失败整体拒绝——调用方（上传卡）catch 后 toast，宁可整批
 * 不入队也不静默丢文件。
 */
export async function collectDroppedFiles(entry: EntryLike, parentPath = ''): Promise<DroppedFile[]> {
  const path = parentPath === '' ? entry.name : `${parentPath}/${entry.name}`
  if (entry.isDirectory) {
    const children = await readAllEntries(entry)
    const nested = await Promise.all(children.map((child) => collectDroppedFiles(child, path)))
    return nested.flat()
  }
  if (entry.isFile) {
    return [{ file: await entryToFile(entry), relativePath: path }]
  }
  return []
}

/** 上传目标（协议 POST /assets/upload 的 dir/filename 两个 query 值） */
export interface UploadTarget {
  /** 库内相对目录（'' = 库根；已含批次目标目录前缀） */
  dir: string
  /** 末段文件名 */
  filename: string
}

/**
 * relativePath → 上传目标（纯函数；路径安全闸门）。
 * - 目录部分（末段前的全部段）依次拼到批次 dir（batchDir，'' = 库根）之下；
 * - 文件名取末段；
 * - 拒绝并返回 null：空串、以 `/`/盘符/`\\` 开头的绝对路径、任何含 `..`
 *   的段（防目录穿越逃出 Library 根）、无文件名的目录形路径。反斜杠先归一
 *   为 `/`（Windows 路径形态兜底）。
 */
export function uploadTargetFromRelativePath(
  relativePath: string,
  batchDir: string,
): UploadTarget | null {
  if (relativePath === '') return null
  const normalized = relativePath.replace(/\\/g, '/')
  if (normalized.startsWith('/') || /^[a-zA-Z]:/.test(normalized)) return null

  const segments = normalized.split('/')
  const filename = segments.pop() ?? ''
  if (filename === '' || filename === '.' || filename === '..') return null

  const dirSegments: string[] = []
  for (const seg of [...batchDir.split('/'), ...segments]) {
    if (seg === '' || seg === '.') continue
    if (seg === '..') return null
    dirSegments.push(seg)
  }
  return { dir: dirSegments.join('/'), filename }
}
