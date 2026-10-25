/** 与 server/internal/httpapi/errors.go maxJSONBody、Android AuthorTxtReader 同步。 */
export const AUTHOR_TXT_REQUEST_MAX_BYTES = 1 << 20
const TOO_LARGE = '作者 TXT 请求超过 1 MiB 上限（含文件名与 JSON 转义），请选择较小的片段'

/** 浏览器 File 大小可在读取前检查；JSON 的转义开销在传输层再按真实字节检查。 */
export function validateAuthorTxtFile(file: File): void {
  if (!/\.txt$/i.test(file.name)) throw new Error('仅支持 .txt 作者清单文件')
  if (file.size > AUTHOR_TXT_REQUEST_MAX_BYTES) throw new Error(TOO_LARGE)
}

export function validateAuthorTxtRequest(body: { filename?: string; content: string; conflictResolution?: 'keep' | 'remove' }): void {
  if (new TextEncoder().encode(JSON.stringify(body)).byteLength > AUTHOR_TXT_REQUEST_MAX_BYTES) {
    throw new Error(TOO_LARGE)
  }
}
