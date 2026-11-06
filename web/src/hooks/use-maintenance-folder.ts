import { useEffect, useState, useSyncExternalStore } from 'react'
import { useMutation } from '@tanstack/react-query'
import { fetchBackupBlob } from '@/hooks/use-backup'
import { chooseMaintenanceFolder, maintenanceFolderStore, maintenanceFolderSupported, writeMaintenanceSnapshot } from '@/lib/maintenance-folder'

export function useMaintenanceFolder() {
  const root = useSyncExternalStore(maintenanceFolderStore.subscribe, maintenanceFolderStore.snapshot)
  const syncError = useSyncExternalStore(maintenanceFolderStore.subscribe, maintenanceFolderStore.errorSnapshot)
  const [loadError, setLoadError] = useState<unknown>(null)
  const [loading, setLoading] = useState(maintenanceFolderSupported)
  useEffect(() => {
    if (maintenanceFolderSupported()) void maintenanceFolderStore.load().catch(setLoadError).finally(() => setLoading(false))
  }, [])
  const choose = useMutation({ mutationFn: async () => {
    const selected = await chooseMaintenanceFolder()
    await writeMaintenanceSnapshot(selected, await fetchBackupBlob(), true)
    setLoadError(null)
  } })
  return { root, choose, loadError: loadError ?? syncError, loading, supported: maintenanceFolderSupported() }
}
