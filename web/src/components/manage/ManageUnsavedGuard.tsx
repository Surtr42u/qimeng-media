import { useEffect } from 'react'
import { useBlocker } from 'react-router'
import { ConfirmDialog } from '@/components/ui/confirm-dialog'

/** 同页多个独立表单只注册一个路由拦截，防止多个 blocker 互相覆盖。 */
export function ManageUnsavedGuard({ dirty }: { dirty: boolean }) {
  const blocker = useBlocker(({ currentLocation, nextLocation }) => dirty && currentLocation.pathname !== nextLocation.pathname)
  useEffect(() => {
    if (!dirty) return
    const onBeforeUnload = (event: BeforeUnloadEvent) => event.preventDefault()
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => window.removeEventListener('beforeunload', onBeforeUnload)
  }, [dirty])
  return <ConfirmDialog open={blocker.state === 'blocked'} title="放弃未保存的修改？" description="来源词表或镜像配置仍有本页草稿，离开后将丢弃；已保存的数据不会被删除。" confirmText="放弃并离开" cancelText="继续编辑" keepOpenOnConfirm onConfirm={() => blocker.proceed?.()} onOpenChange={open => { if (!open) blocker.reset?.() }} />
}
