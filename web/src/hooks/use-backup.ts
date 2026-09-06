/**
 * 备份导入/导出 hooks（维护页文件管理的备份卡，DOMAIN_RULES §10）。
 *
 * 导出走裸 fetch 而非生成 SDK：SDK 响应是解析后的对象，重新序列化才能落成
 * 文件（字段序/数字精度存在二次加工风险）；裸 fetch 拿原始字节 Blob 原样
 * 下载，保证导出文件与协议响应逐字节一致（鉴权复用 getAuthHeaders，与
 * use-upload/sse 同一旁路约定）。
 *
 * 导入复用生成 SDK 的 postApiV1ImportQimengBackup（JSON body 协议）；成功后
 * 失效资产/作者/标签/推荐/统计各根键——迁移写入面横跨这些域，整族失效最稳。
 */

import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  postApiV1ImportQimengBackup,
  type LegacyBackupImport,
  type LegacyImportResult,
} from '@/api/generated'
import { getAuthHeaders, unwrapSdkResult } from '@/lib/api-client'
import {
  ASSETS_QUERY_KEY,
  AUTHORS_QUERY_KEY,
  HISTORY_QUERY_KEY,
  RANKINGS_QUERY_KEY,
  RECOMMENDATIONS_QUERY_KEY,
  STATS_QUERY_KEY,
  TAGS_QUERY_KEY,
} from '@/lib/query-keys'

/** 导出端点路径（协议 GET /api/v1/export/qimeng-backup；仅此一处消费） */
const EXPORT_BACKUP_PATH = '/api/v1/export/qimeng-backup'

/** 导出下载的文件名（旧版固定约定，DATA_MIGRATION_SPEC §2） */
const BACKUP_FILE_NAME = 'qimeng_backup.json'

/** 导出备份：GET 原始字节 → 触发浏览器下载；onSuccess 回传文件大小供提示 */
export function useExportQimengBackup() {
  return useMutation({
    mutationFn: async (): Promise<{ sizeBytes: number }> => {
      const resp = await fetch(EXPORT_BACKUP_PATH, { headers: getAuthHeaders() })
      if (!resp.ok) {
        throw new Error(`导出失败（HTTP ${resp.status}）`)
      }
      const blob = await resp.blob()
      const url = URL.createObjectURL(blob)
      try {
        const a = document.createElement('a')
        a.href = url
        a.download = BACKUP_FILE_NAME
        document.body.appendChild(a)
        a.click()
        a.remove()
      } finally {
        URL.revokeObjectURL(url)
      }
      return { sizeBytes: blob.size }
    },
  })
}

/** 导入恢复：备份载荷 POST 到迁移端点（幂等，重复导入不翻倍） */
export function useImportQimengBackup() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (payload: LegacyBackupImport): Promise<LegacyImportResult> =>
      unwrapSdkResult(postApiV1ImportQimengBackup({ body: payload })),
    onSuccess: () => {
      // 迁移写入面横跨多域：资产（含收藏/点赞详情）/作者/标签/推荐/统计/排行/
      // 观看历史整族失效。stats 族此前手写 ['api/v1/stats'] 与 use-stats 的
      // ['api/v1/stats/overview'] 形态错配致失效落空，2026-09-06 收敛进
      // query-keys 单一来源后根键命中全部子键。
      for (const root of [
        ASSETS_QUERY_KEY,
        AUTHORS_QUERY_KEY,
        TAGS_QUERY_KEY,
        RECOMMENDATIONS_QUERY_KEY,
        STATS_QUERY_KEY,
        RANKINGS_QUERY_KEY,
        HISTORY_QUERY_KEY,
      ]) {
        void qc.invalidateQueries({ queryKey: root })
      }
    },
  })
}
