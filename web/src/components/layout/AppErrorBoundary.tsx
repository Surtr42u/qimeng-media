import { Component, type CSSProperties, type ErrorInfo, type ReactNode } from 'react'
import { reportRenderError } from '@/lib/client-logs'
import { HOME_PATH } from '@/lib/route-keys'

interface AppErrorBoundaryProps {
  children: ReactNode
}

interface AppErrorBoundaryState {
  error: Error | null
}

/** 兜底页按钮共用样式：颜色全部走 --qm-* 设计 token（禁硬编码色值） */
const fallbackButtonStyle: CSSProperties = {
  padding: 'var(--qm-space-2) var(--qm-space-4)',
  borderRadius: 'var(--qm-radius)',
  border: '1px solid var(--qm-border)',
  fontSize: '0.9rem',
  cursor: 'pointer',
  transition: 'opacity var(--qm-duration-fast) var(--qm-ease-out)',
}

/**
 * 全局渲染期异常兜底（F1：此前项目无 ErrorBoundary，任何页面渲染期抛错
 * 会把整棵 React 树卸载成白屏——main 元素都不剩，用户无路可走）。
 *
 * 挂在应用根处（main.tsx，RouterProvider 外层）：路由树内任意渲染期异常
 * 沿 React 冒泡到此处兜底。用 class 组件——错误边界官方实现形态，React
 * Compiler 不编译 class（组件无 hooks，无 lint 语义差异）。
 *
 * 兜底页：全 --qm-* token（暗色模式自动跟随）、中文文案、「重试」清错误
 * 重挂原树、「返回首页」整页刷新跳转（router 可能已处于异常态，不走
 * 客户端导航；HOME_PATH 为路由键唯一来源）。错误详情仅展示 message 供
 * 排查，不展示堆栈（用户面向界面）。
 */
export class AppErrorBoundary extends Component<AppErrorBoundaryProps, AppErrorBoundaryState> {
  state: AppErrorBoundaryState = { error: null }

  static getDerivedStateFromError(error: Error): AppErrorBoundaryState {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // window.onerror（installClientLogs）兜不住被边界接住的渲染异常——它不再
    // 冒泡成 uncaught error。控制台留痕 + client-logs 旁路上报（审计 R12）：
    // reportRenderError 全程吞异常，兜底链路上绝不二次抛错（防递归）。
    console.error('[AppErrorBoundary] 渲染期异常', error, info.componentStack)
    reportRenderError(error, info.componentStack)
  }

  render() {
    const { error } = this.state
    if (!error) return this.props.children
    return (
      <div
        style={{
          minHeight: '100vh',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          gap: 'var(--qm-space-3)',
          padding: 'var(--qm-space-4)',
          background: 'var(--qm-bg)',
          color: 'var(--qm-text)',
          textAlign: 'center',
        }}
      >
        <h2 style={{ margin: 0, fontSize: '1.25rem' }}>页面出错了</h2>
        <p style={{ margin: 0, color: 'var(--qm-text-muted)', maxWidth: '32rem' }}>
          页面渲染时发生意外错误。你可以重试，或返回首页继续使用。
        </p>
        <p
          style={{
            margin: 0,
            color: 'var(--qm-text-muted)',
            fontSize: '0.8rem',
            maxWidth: '32rem',
            wordBreak: 'break-all',
          }}
        >
          {error.message}
        </p>
        <div style={{ display: 'flex', gap: 'var(--qm-space-2)' }}>
          <button
            type="button"
            onClick={() => this.setState({ error: null })}
            style={{ ...fallbackButtonStyle, background: 'var(--qm-surface-soft)', color: 'var(--qm-text)' }}
          >
            重试
          </button>
          <button
            type="button"
            onClick={() => window.location.assign(HOME_PATH)}
            style={{ ...fallbackButtonStyle, background: 'var(--qm-primary)', color: 'var(--qm-on-accent)', border: 'none' }}
          >
            返回首页
          </button>
        </div>
      </div>
    )
  }
}
