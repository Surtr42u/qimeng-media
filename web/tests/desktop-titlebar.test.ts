import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import { describe, expect, it } from 'vitest'

const script = readFileSync(new URL('../../desktop/src-tauri/src/titlebar.js', import.meta.url), 'utf8')

function click(action: string | null, title: string) {
  const calls: string[] = []
  const handlers = new Map<string, (event: unknown) => void>()
  const button = { getAttribute: (name: string) => name === 'data-qimeng-window-action' ? action : title }
  const window = { __TAURI__: { window: { getCurrentWindow: () => ({
    minimize: () => calls.push('minimize'), toggleMaximize: () => calls.push('maximize'), close: () => calls.push('close'),
  }) } } }
  vm.runInNewContext(script, { window, document: { addEventListener: (type: string, handler: (event: unknown) => void) => handlers.set(type, handler) } })
  handlers.get('click')?.({
    target: { closest: () => button }, preventDefault() {}, stopPropagation() {}, stopImmediatePropagation() {},
  })
  return calls
}

describe('Web 与桌面窗口动作兼容', () => {
  it('新动作独立于文案；未知动作不误用旧 title', () => {
    for (const action of ['minimize', 'maximize', 'close']) expect(click(action, '任意新文案')).toEqual([action])
    expect(click('unknown-action', '关闭')).toEqual([])
  })

  it('新桌面壳继续兼容尚未升级的 Web', () => {
    expect(click(null, '最小化')).toEqual(['minimize'])
    expect(click(null, '最大化')).toEqual(['maximize'])
    expect(click(null, '关闭')).toEqual(['close'])
    expect(click(null, '其他')).toEqual([])
  })

  it('共享 Web 保留旧桌面兼容属性并提供明确动作', () => {
    const source = readFileSync(new URL('../src/components/shell/TopBar.tsx', import.meta.url), 'utf8')
    expect(source).toContain('data-qimeng-titlebar')
    for (const [action, title] of [['minimize', '最小化'], ['maximize', '最大化'], ['close', '关闭']]) {
      expect(source).toContain(`className="win-btn" data-qimeng-window-action="${action}" title="${title}"`)
    }
  })
})
