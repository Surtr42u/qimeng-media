/**
 * 上传落库名纯规则（上传暂存区逐项「作品名」编辑消费；行为由 upload-naming.test.ts 锁定）。
 *
 * 口径与 Android 端 UploadNaming 一致（三端同规则，非搬运实现）：暂存条目只允许
 * 编辑「基名」，扩展名锁定不可改——完整落库名一律经 composeUploadName 拼装，
 * 禁止调用点散拼（扩展名约定不得被破坏：服务端匹配引擎按扩展名区分图片/视频，
 * REQ-上传指定作者与来源 §3.3 第 3 条）。
 */

/** 文件名去掉扩展名后的基名（".jpg" → ""；".hidden" → ".hidden" 整体为基名） */
export function baseNameOf(fileName: string): string {
  const idx = fileName.lastIndexOf('.')
  return idx <= 0 ? fileName : fileName.slice(0, idx)
}

/** 文件名的扩展名（含点；无扩展名/点前缀隐藏文件返回空串） */
export function extensionOf(fileName: string): string {
  const idx = fileName.lastIndexOf('.')
  return idx <= 0 ? '' : fileName.slice(idx)
}

/**
 * 基名 + 锁定扩展名 → 完整落库名。基名 trim 后为空返回空串（调用方回退
 * 原展示名，与暂存条目 effectiveUploadName 的回退口径配套）；扩展名为空则只取基名。
 */
export function composeUploadName(base: string, extension: string): string {
  const trimmed = base.trim()
  if (trimmed === '') return ''
  const ext = extension.trim()
  return ext === '' ? trimmed : trimmed + ext
}
