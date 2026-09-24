/**
 * 上传 query 参数构造（纯函数；hooks/use-upload.ts 消费，裸 XHR 场景）。
 *
 * 为什么抽成纯函数：协议参数组（libraryId/dir/filename + 可选挂靠
 * authorId/authorName/source[]，api/openapi.yaml POST /assets/upload query）
 * 的组装口径单一来源——Android 端各自拼、或散落在 XHR 回调里手抄都会
 * 漂移；行为由 upload-params.test.ts 锁定（AI_README「测试锁定」）。
 *
 * 口径：
 * - authorId 与 authorName 协议互斥（同传 400 INVALID_PARAM）——本函数
 *   防御性只发 authorId（点选联想项的确定身份优先），调用方
 *   （UploadCard enqueue 快照）也保证不同时给出；
 * - source 是数组 query 同名重复传多值（与 GET /assets 的 source 数组
 *   同款传法）；协议规定仅指定作者时合法，无挂靠时忽略 sources。
 */

/** 挂靠快照（入队时定格；authorId=点选联想项，authorName=回车新建） */
export interface UploadAuthorAttach {
  authorId?: string
  authorName?: string
}

/** 组装上传 query（空串/缺省的可选项不 append，保持无参旧行为字节不变） */
export function buildUploadQuery(
  libraryId: string,
  dir: string,
  filename: string,
  author?: UploadAuthorAttach | null,
  sources?: readonly string[],
): URLSearchParams {
  const params = new URLSearchParams({ libraryId, dir, filename })
  if (author?.authorId) {
    params.append('authorId', author.authorId)
  } else if (author?.authorName) {
    params.append('authorName', author.authorName)
  } else {
    // 无挂靠：sources 协议非法（400），忽略——「留空 = 行为与现状完全一致」
    return params
  }
  for (const s of sources ?? []) {
    if (s !== '') params.append('source', s)
  }
  return params
}
