/**
 * 服务端 API 错误码 → 用户可读中文文案（页面级共享）。
 *
 * 【归属待定】M2 任务约定：src/lib、src/hooks 为禁止改动目录，本模块暂放 pages/_shared，
 * 待主 AI 集成时确认归属（候选 src/lib/error-messages.ts）后整体平移——此注释即待办标记。
 *
 * 同步责任：错误码与 server/internal/httpapi/*.go 的 writeErr 调用一一对应
 * （openapi.yaml Error schema 的 code 字段）；服务端新增/修改错误码必须同步本映射，反之亦然。
 */

/** 错误码 → 中文文案（取服务端 writeErr 的中文 message 语义，前端不展示服务端原文的兜底场景） */
export const ERROR_CODE_MESSAGES: Record<string, string> = {
  /** 参数错误（库注册/目录/移动/上传通用） */
  INVALID_PARAM: '参数不合法',
  /** 库注册：目录不存在或不可访问（不区分"不存在/无权限"，服务端不泄露文件系统细节） */
  PATH_NOT_FOUND: '目录不存在或不可访问',
  /** 库注册：库根与数据目录任一方向嵌套都拒绝（防扫描器自噬） */
  DATA_DIR_CONFLICT: '库目录不能包含也不能位于服务端数据目录内',
  /** 库注册：UNIQUE(root_path) 冲突 */
  CONFLICT: '该目录已注册为库',
  /** 资源不存在（库/资产/回收站条目通用） */
  NOT_FOUND: '资源不存在',
  /** 服务端故障兜底 */
  INTERNAL: '服务端内部错误',
  /** 扫描：扫描器未装配（占位实现，M2 后置） */
  SCANNER_UNAVAILABLE: '扫描器尚未装配',
  /** 扫描：该库已有扫描在运行 */
  SCAN_IN_PROGRESS: '该库扫描进行中',
  /** 移动/重命名：目标位置同名 */
  TARGET_EXISTS: '目标位置已有同名文件',
  /** 上传白名单四关：大小上限 */
  UPLOAD_TOO_LARGE: '文件超过大小上限',
  /** 上传白名单四关：扩展名 */
  INVALID_EXTENSION: '扩展名不在白名单',
  /** 上传白名单四关：内容与扩展名不符 */
  MIME_MISMATCH: '文件内容与扩展名不符',
  /** 上传/移动：文件名不合法 */
  INVALID_FILENAME: '文件名不合法',
  /** 回收站：meta 损坏无法恢复 */
  INVALID_META: '回收站元数据不合法，无法恢复',
  /** 上传：流读取失败 */
  INVALID_BODY: '上传流读取失败',
  /** 上传：libraryId 缺失或未注册 */
  LIBRARY_NOT_FOUND: 'libraryId 必填且指向已注册库',
  /** 回收站/文件操作：库内文件已不在（被外部移动） */
  FILE_MISSING: '库内文件不存在（可能已被外部移动）',
}

/** 未命中映射时的兜底文案（面向用户，不暴露内部细节） */
export const FALLBACK_ERROR_TEXT = '操作失败，请稍后重试'

/**
 * 从 SDK 抛出的 unknown 错误提取用户可读文案。
 *
 * 错误来源三种（client.gen.ts request 逻辑）：服务端 JSON {code,message}（writeErr 产物）、
 * 原生 3xx/4xx 文本、网络异常（fetch failed 等原生 Error）。前两种优先走错误码映射；
 * 原生英文错误对用户无价值，统一回兜底文案——不原样透传（SECURITY：不泄露内部细节）。
 */
export function apiErrorText(error: unknown): string {
  if (error == null) return FALLBACK_ERROR_TEXT
  if (typeof error === 'string' && error) return error
  const record = typeof error === 'object' ? (error as Record<string, unknown>) : null
  const code = record?.['code']
  if (typeof code === 'string') {
    return ERROR_CODE_MESSAGES[code] ?? FALLBACK_ERROR_TEXT
  }
  const message = record?.['message']
  if (typeof message === 'string' && message) {
    // 网络层原生错误（浏览器英文原文）对用户无可读性，兜底处理
    if (message === 'fetch failed' || message === 'Network Error') return FALLBACK_ERROR_TEXT
    return message
  }
  return FALLBACK_ERROR_TEXT
}
