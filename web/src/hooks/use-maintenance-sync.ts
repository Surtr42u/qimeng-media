import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useSSEEvents } from '@/hooks/use-sse-events'
import { fetchBackupBlob } from '@/hooks/use-backup'
import { maintenanceFolderStore, maintenanceFolderSupported, writeMaintenanceSnapshot } from '@/lib/maintenance-folder'

/** One authenticated, coalesced writer for local and other-client changes. */
export function useMaintenanceSync(): void {
  const queryClient = useQueryClient()
  useEffect(() => {
    if (!maintenanceFolderSupported()) return
    let timer: ReturnType<typeof setTimeout> | undefined
    let running = false
    let dirty = false
    let stopped = false
    async function sync() {
      if (stopped || running) return
      const selected = maintenanceFolderStore.snapshot()
      if (!selected) return
      running = true
      dirty = false
      try {
        if (await selected.queryPermission({ mode: 'readwrite' }) !== 'granted') throw new Error('维护目录需要重新授权，请在资料维护中选择目录')
        const blob = await fetchBackupBlob()
        if (!stopped && selected === maintenanceFolderStore.snapshot()) await writeMaintenanceSnapshot(selected, blob, true)
        maintenanceFolderStore.setError(null)
      } catch (error) {
        maintenanceFolderStore.setError(error instanceof Error ? error.message : '维护目录写回失败，请重新导出')
      } finally {
        running = false
        if (dirty && !stopped) schedule()
      }
    }
    function schedule() {
      dirty = true
      clearTimeout(timer)
      timer = setTimeout(() => { void sync() }, 1500)
    }
    const unsubscribe = queryClient.getMutationCache().subscribe(event => {
      if (event.type === 'updated' && event.action.type === 'success') schedule()
    })
    const onChange = () => schedule()
    window.addEventListener('qimeng-maintenance-changed', onChange)
    window.addEventListener('focus', onChange)
    void maintenanceFolderStore.load().then(schedule).catch(error => maintenanceFolderStore.setError(String(error)))
    return () => {
      stopped = true
      clearTimeout(timer)
      unsubscribe()
      window.removeEventListener('qimeng-maintenance-changed', onChange)
      window.removeEventListener('focus', onChange)
    }
  }, [queryClient])
  useSSEEvents({
    onLibraryChanged: () => window.dispatchEvent(new Event('qimeng-maintenance-changed')),
    onOpen: () => window.dispatchEvent(new Event('qimeng-maintenance-changed')),
  })
}
