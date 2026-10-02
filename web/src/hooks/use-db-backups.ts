/**
 * 备份热备 hooks（维护页「库文件快照」卡消费，任务Q 批B；铁律 7：组件不直接调 API）。
 *
 * 数据源：GET /api/v1/backups（快照列表 + 调度摘要一次往返）、POST（手动触发，
 * 409 = 已有快照进行中）、DELETE /backups/{name}（删除单份）。
 * 下载走裸 fetch 而非生成 SDK（与 use-backup 导出同款旁路约定）：快照是
 * 二进制库文件，SDK 会把它当文本解析，裸 fetch 拿原始 Blob 原样落盘保证
 * 逐字节一致（鉴权复用 getAuthHeaders）。
 *
 * 命名区分：use-backup.ts 是旧版 JSON 备份导入/导出（DOMAIN_RULES §10），
 * 本文件是库文件快照（VACUUM INTO），两套并存勿混。
 */

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  deleteApiV1BackupsByName,
  getApiV1Backups,
  postApiV1Backups,
  putApiV1BackupsSchedule,
  type BackupInfo,
  type BackupSchedule,
} from '@/api/generated'
import { getAuthHeaders, unwrapSdkResult } from '@/lib/api-client'
import { downloadBlob } from '@/lib/download'

/** 查询键（无跨 hook 失效需求，按卫生约束第 2 条留在本文件） */
export const BACKUPS_QUERY_KEY = ['api/v1/backups'] as const

/** 下载端点路径模板（协议 GET /api/v1/backups/{name}/file；仅此一处消费） */
const BACKUP_FILE_PATH = '/api/v1/backups'

/** 快照列表 + 调度摘要（维护页一次往返） */
export function useDbBackups() {
  return useQuery({
    queryKey: BACKUPS_QUERY_KEY,
    queryFn: () => unwrapSdkResult(getApiV1Backups()),
  })
}

/** 手动触发一次快照；409（已有快照进行中）由调用方按错误信息提示 */
export function useCreateDbBackup() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => unwrapSdkResult(postApiV1Backups()),
    onSuccess: () => qc.invalidateQueries({ queryKey: BACKUPS_QUERY_KEY }),
  })
}

/** 删除单份快照（二次确认在 UI 层） */
export function useDeleteDbBackup() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (name: string) =>
      unwrapSdkResult(deleteApiV1BackupsByName({ path: { name } })),
    onSuccess: () => qc.invalidateQueries({ queryKey: BACKUPS_QUERY_KEY }),
  })
}

/**
 * 修改调度参数（PUT /backups/schedule，2026-10-03 热生效批）：服务端先
 * 持久化（kv_settings）再热生效，无需重启； onSuccess 回传生效值供提示。
 * 范围校验在 lib/backup.ts（铁律 7），本 hook 只管提交与缓存失效。
 */
export function useUpdateDbBackupSchedule() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (schedule: BackupSchedule) =>
      unwrapSdkResult(putApiV1BackupsSchedule({ body: schedule })),
    onSuccess: () => qc.invalidateQueries({ queryKey: BACKUPS_QUERY_KEY }),
  })
}

/** 下载单份快照：GET 原始字节 → 触发浏览器下载；onSuccess 回传快照信息供提示 */
export function useDownloadDbBackup() {
  return useMutation({
    mutationFn: async (info: BackupInfo): Promise<BackupInfo> => {
      const resp = await fetch(`${BACKUP_FILE_PATH}/${info.name}/file`, {
        headers: getAuthHeaders(),
      })
      if (!resp.ok) {
        throw new Error(`下载失败（HTTP ${resp.status}）`)
      }
      const blob = await resp.blob()
      downloadBlob(blob, info.name)
      return info
    },
  })
}
