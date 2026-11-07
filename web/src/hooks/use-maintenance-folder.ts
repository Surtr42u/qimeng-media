import { useEffect, useState, useSyncExternalStore } from 'react'
import { useMutation } from '@tanstack/react-query'
import { fetchBackupBlob } from '@/hooks/use-backup'
import { chooseMaintenanceFolder, findLegacyGenerationFiles, maintenanceFolderStore, maintenanceFolderSupported, writeMaintenanceSnapshot } from '@/lib/maintenance-folder'

export function useMaintenanceFolder() {
  const root = useSyncExternalStore(maintenanceFolderStore.subscribe, maintenanceFolderStore.snapshot)
  const syncError = useSyncExternalStore(maintenanceFolderStore.subscribe, maintenanceFolderStore.errorSnapshot)
  const [loadError, setLoadError] = useState<unknown>(null)
  const [loading, setLoading] = useState(maintenanceFolderSupported)
  const [legacyFiles, setLegacyFiles] = useState<string[]>([])
  useEffect(() => {
    if (maintenanceFolderSupported()) void maintenanceFolderStore.load().catch(setLoadError).finally(() => setLoading(false))
  }, [])
  // 旧三代布局文件只做只读探测与提示，绝不删除；未授权时读取失败按「无提示」处理，
  // 授权问题由目录卡/写回错误的既有通道呈现。
  useEffect(() => {
    if (!root) return
    let cancelled = false
    void findLegacyGenerationFiles(root)
      .then(files => { if (!cancelled) setLegacyFiles(files) })
      .catch(() => { if (!cancelled) setLegacyFiles([]) })
    return () => { cancelled = true }
  }, [root])
  const choose = useMutation({ mutationFn: async () => {
    const selected = await chooseMaintenanceFolder()
    await writeMaintenanceSnapshot(selected, await fetchBackupBlob(), true)
    setLoadError(null)
  } })
  return { root, choose, loadError: loadError ?? syncError, loading, supported: maintenanceFolderSupported(), legacyFiles }
}
