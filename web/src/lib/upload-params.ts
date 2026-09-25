/**
 * 上传 query 参数构造（纯函数；hooks/use-upload.ts 消费，裸 XHR 场景）。
 *
 * 为什么抽成纯函数：协议参数组（libraryId/dir/filename，api/openapi.yaml
 * POST /assets/upload query）的组装口径单一来源——多端各自拼、或散落在
 * XHR 回调里手抄都会漂移；行为由 upload-params.test.ts 锁定（AI_README
 * 「测试锁定」）。
 *
 * 口径（2026-09-25 协议批）：上传三参数挂靠（authorId/authorName/source）
 * 已退役——作者/来源的编辑归资产编辑页（PUT /assets/{assetId}/authors +
 * PUT /authors/{authorId}/sources），上传只负责把文件放进目标目录。
 */

/** 组装上传 query（三个必填项；dir 空 = 库根，协议语义原样透传） */
export function buildUploadQuery(
  libraryId: string,
  dir: string,
  filename: string,
): URLSearchParams {
  return new URLSearchParams({ libraryId, dir, filename })
}
