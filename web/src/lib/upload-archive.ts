import { maintenanceFolderStore, maintenanceFolderSupported, safeMaintenanceName } from '@/lib/maintenance-folder'

type ArchiveTarget = { folder: ReturnType<typeof maintenanceFolderStore.snapshot>; error?: unknown }

export function captureUploadArchive(): Promise<ArchiveTarget> {
  if (!maintenanceFolderSupported()) return Promise.resolve({ folder: null })
  const selected = maintenanceFolderStore.snapshot()
  if (selected) return Promise.resolve({ folder: selected })
  // Resolve failures into the captured target, avoiding an unhandled rejection while
  // a large upload is still in flight. The queue reports it after upload success.
  return maintenanceFolderStore.load().then(() => ({ folder: maintenanceFolderStore.snapshot() }))
    .catch(error => ({ folder: null, error }))
}

/** Upload success remains committed even if this separate local copy fails. */
export async function archiveUploadedFile(
  selected: NonNullable<ArchiveTarget['folder']>,
  file: File,
  libraryName: string,
  directory: string,
  filename: string,
): Promise<string> {
  const segments = [libraryName, ...directory.split('/').filter(Boolean)]
  if (![...segments, filename].every(safeMaintenanceName)) throw new Error('归档路径包含不安全名称')
  let folder = await selected.getDirectoryHandle('上传归档', { create: true })
  for (const segment of segments) folder = await folder.getDirectoryHandle(segment, { create: true })
  try {
    await folder.getFileHandle(filename)
    throw new Error('归档中已有同名文件，请核对后另行保存原件')
  } catch (error) {
    if (!(error instanceof DOMException && error.name === 'NotFoundError')) throw error
  }
  const target = await folder.getFileHandle(filename, { create: true })
  const writer = await target.createWritable()
  try { await writer.write(file); await writer.close() }
  catch (error) { await writer.abort().catch(() => undefined); throw error }
  return '已保存原件副本到维护目录的上传归档'
}
