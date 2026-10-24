import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import ts from 'typescript'
import { describe, expect, it } from 'vitest'

const srcRoot = fileURLToPath(new URL('../src/', import.meta.url))

// 使用已有 TypeScript 解析器检查语法，允许 type-only SDK 引用；不靠字符串
// 匹配注释或文案。这里只防直接请求/依赖漂移（含运行时 re-export 与方括号
// 成员调用等绕过写法），不承诺证明整个调用链无 IO——barrel 与包装层需要
// 源依赖审查，本测试只锁定直接语法边界。
function violations(source: string, name: string): string[] {
  const file = ts.createSourceFile(name, source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX)
  const errors: string[] = []
  const apiPath = (specifier: string) => {
    const resolved = specifier.startsWith('@/')
      ? path.resolve(srcRoot, specifier.slice(2))
      : path.resolve(path.dirname(name), specifier)
    return resolved === path.join(srcRoot, 'api') || resolved.startsWith(path.join(srcRoot, 'api') + path.sep)
  }
  // 运行时值引用 API 模块（import / import-equals / re-export 三种形态）才禁止；
  // type-only 引用（协议类型）放行。
  const runtimeApiReference = (specifier: string, typeOnly: boolean) =>
    !typeOnly && (apiPath(specifier) || specifier === 'axios')
  const visit = (node: ts.Node) => {
    if (ts.isImportDeclaration(node) && ts.isStringLiteral(node.moduleSpecifier)) {
      const clause = node.importClause
      const bindings = clause?.namedBindings
      const typeOnly = clause?.isTypeOnly || (!clause?.name && bindings &&
        ts.isNamedImports(bindings) && bindings.elements.length > 0 && bindings.elements.every(e => e.isTypeOnly))
      if (runtimeApiReference(node.moduleSpecifier.text, !!typeOnly)) {
        errors.push('UI 禁止运行时 API 导入')
      }
    }
    // import x = require('...')：与普通 import 同口径（Q5 门禁扩充）
    if (ts.isImportEqualsDeclaration(node)) {
      const reference = node.moduleReference
      if (ts.isExternalModuleReference(reference) && ts.isStringLiteral(reference.expression) &&
        runtimeApiReference(reference.expression.text, node.isTypeOnly)) {
        errors.push('UI 禁止运行时 API 导入')
      }
    }
    // 运行时 re-export：export { client } / export * / export * as ns from API
    // 的值导出禁止；export type / 全 type-only 具名导出放行（Q5 门禁扩充）
    if (ts.isExportDeclaration(node) && node.moduleSpecifier && ts.isStringLiteral(node.moduleSpecifier)) {
      const clause = node.exportClause
      const typeOnly = node.isTypeOnly || (
        clause && ts.isNamedExports(clause) &&
        clause.elements.length > 0 && clause.elements.every(element => element.isTypeOnly)
      )
      if (runtimeApiReference(node.moduleSpecifier.text, !!typeOnly)) {
        errors.push('UI 禁止运行时 API 重导出')
      }
    }
    if (ts.isCallExpression(node) || ts.isNewExpression(node)) {
      const expression = node.expression
      // 字符串方括号成员（window['fetch']、globalThis['XMLHttpRequest']）
      // 与标识符/属性访问同口径解析（Q5 门禁扩充）
      const called = ts.isIdentifier(expression)
        ? expression.text
        : ts.isPropertyAccessExpression(expression)
          ? expression.name.text
          : ts.isElementAccessExpression(expression) &&
              expression.argumentExpression &&
              (ts.isStringLiteral(expression.argumentExpression) ||
               ts.isNoSubstitutionTemplateLiteral(expression.argumentExpression))
            ? expression.argumentExpression.text
            : ''
      const dynamicApi = expression.kind === ts.SyntaxKind.ImportKeyword && ts.isCallExpression(node) &&
        (!node.arguments[0] || !ts.isStringLiteral(node.arguments[0]) || apiPath(node.arguments[0].text))
      if (['fetch', 'XMLHttpRequest', 'axios', 'require'].includes(called) || dynamicApi) {
        errors.push('UI 禁止直接请求或动态绕过静态依赖')
      }
    }
    ts.forEachChild(node, visit)
  }
  visit(file)
  return errors
}

function sources(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const name = path.join(dir, entry.name)
    return entry.isDirectory() ? sources(name) : /\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name) ? [name] : []
  })
}

describe('UI 数据访问边界', () => {
  it('拒绝运行时 SDK、相对路径、动态请求、值重导出，允许纯类型及 hooks', () => {
    const name = path.join(srcRoot, 'pages', 'Synthetic.tsx')
    for (const code of [
      "import { client } from '@/api/generated'", "import '../api/generated'",
      "import { type Asset, client } from '@/api/generated'",
      "import asset = require('@/api/generated/client')",
      "window.fetch('/api/v1/assets')", "new XMLHttpRequest()", "import('@/api/generated')",
      "export { client } from '@/api/generated'", "export * from '../api/generated'",
      "export * as api from '@/api/generated'", "export { type Asset, client } from '@/api/generated'",
      "window['fetch']('/api/v1/assets')", "new globalThis['XMLHttpRequest']()",
    ]) expect(violations(code, name), code).not.toEqual([])
    for (const code of [
      "import type { Asset } from '@/api/generated'", "import { type Asset } from '@/api/generated'",
      "import { useAssets } from '@/hooks/use-assets'", "const label = 'fetch /api/'",
      "const Detail = import('../pages/DetailPage')",
      "export type { Asset } from '@/api/generated'", "export { type Asset } from '@/api/generated'",
      "export type * from '@/api/generated'", "export { useAssets } from '@/hooks/use-assets'",
    ]) expect(violations(code, name), code).toEqual([])
  })

  it('所有页面和渲染组件遵守边界', () => {
    const errors = ['components', 'pages'].flatMap(dir => sources(path.join(srcRoot, dir)))
      .flatMap(name => violations(readFileSync(name, 'utf8'), name).map(error => `${path.relative(srcRoot, name)}: ${error}`))
    expect(errors).toEqual([])
  })
})
