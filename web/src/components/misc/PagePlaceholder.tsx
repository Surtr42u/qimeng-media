import { Construction } from 'lucide-react'

/** 占位页（M2 基建骨架）：后续并行任务按路由填充实现，本组件是临时占位 */
export function PagePlaceholder({ title, description }: { title: string; description: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-[var(--qm-space-3)] px-6 py-24 text-center">
      <Construction className="size-8 text-[var(--qm-text-muted)]" aria-hidden />
      <h1 className="text-lg font-bold">{title}</h1>
      <p className="max-w-md text-sm text-[var(--qm-text-muted)]">{description}</p>
    </div>
  )
}
