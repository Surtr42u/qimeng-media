import { Button } from '@/components/ui/button'

/**
 * M0 骨架验证页：证明 Tailwind v4（shadcn 主题变量）→ shadcn/ui → TanStack Query 整条链路可用。
 *
 * 纪律提醒（ADR-0008）：页面/组件只做渲染，禁止出现 fetch/axios/api 调用，
 * 数据获取一律放 src/hooks 的 TanStack Query hooks；颜色只用 var(--qm-*)（见 tokens.css）。
 */
export default function App() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-[var(--qm-space-3)] bg-background text-foreground">
      <h1 className="text-3xl font-bold" style={{ color: 'var(--qm-primary)' }}>
        qimeng-web 骨架就绪
      </h1>
      <p className="text-sm text-muted-foreground">
        Vite + React + TypeScript(strict) + Tailwind CSS v4 + shadcn/ui +
        TanStack Query
      </p>
      {/* shadcn Button 能正常渲染并带主题样式 = Tailwind 与组件链路打通 */}
      <Button>shadcn Button</Button>
    </div>
  )
}
