/** 独立预览服务器：Node标准库HTTP/静态文件能力；无数据库、代理或应用运行时依赖。
 * 夹具与工具固定复用。已有统一预览平台能覆盖合成API与隔离边界时整件退役。
 */
import http from 'node:http'
import { readFile, realpath, stat } from 'node:fs/promises'
import path from 'node:path'
import { parseArgs } from 'node:util'
import { createPreviewApi } from './api.mjs'
import { coverSvg, FIXTURE_COUNT } from './fixtures/catalog.mjs'

const PREVIEW_PORT = 19474
const JSON_LIMIT_BYTES = 1024 ** 2
const { values } = parseArgs({ options: { dist: { type: 'string' }, port: { type: 'string', default: String(PREVIEW_PORT) } } })
if (!values.dist) throw new Error('必须通过 --dist 指定已下载的云端Web产物目录')
const root = await realpath(values.dist)
const indexFile = await realpath(path.join(root, 'index.html'))
if (!indexFile.startsWith(root + path.sep) || !(await stat(indexFile)).isFile()) throw new Error('Web产物缺少安全的index.html')
const port = Number(values.port)
if (!Number.isInteger(port) || port < 1024 || port > 65535 || port === 8420) throw new Error('预览端口非法或与真实服务冲突')
const api = createPreviewApi()
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.webmanifest': 'application/manifest+json' }
const within = target => target === root || target.startsWith(root + path.sep)
const send = (res, body, status = 200, headers = {}) => { res.writeHead(status, { 'Content-Type': 'application/json', ...headers }); res.end(body === undefined ? undefined : JSON.stringify(body)) }

async function readBody(req) {
  const chunks = []; let size = 0
  for await (const chunk of req) { size += chunk.length; if (size > JSON_LIMIT_BYTES) throw new Error('Request too large'); chunks.push(chunk) }
  return chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : {}
}

http.createServer(async (req, res) => {
  res.setHeader('Cache-Control', 'no-store')
  res.setHeader('X-Qimeng-Preview', 'synthetic-only')
  try {
    const url = new URL(req.url, 'http://127.0.0.1')
    if (url.pathname === '/__preview/health') return send(res, { mode: 'synthetic', fixtureCount: FIXTURE_COUNT })
    if (url.pathname === '/preview/cover.svg') { res.writeHead(200, { 'Content-Type': mime['.svg'] }); return res.end(coverSvg(Number(url.searchParams.get('v')) || 0)) }
    if (url.pathname === '/api/v1/events' && req.method === 'GET') {
      res.writeHead(200, { 'Content-Type': 'text/event-stream' }); res.write(': synthetic preview\n\n')
      const heartbeat = setInterval(() => res.write(': preview\n\n'), 20_000)
      res.on('close', () => clearInterval(heartbeat)); return
    }
    if (url.pathname.startsWith('/api/')) {
      const result = api(url.pathname, req.method, url.searchParams, await readBody(req))
      return send(res, result.body, result.status, result.headers)
    }
    if (req.method !== 'GET' && req.method !== 'HEAD') return send(res, { code: 'PREVIEW_METHOD', message: '虚拟预览不写入磁盘' }, 405)
    if (url.pathname === '/sw.js') { res.writeHead(200, { 'Content-Type': mime['.js'] }); return res.end('self.addEventListener("activate",()=>self.registration.unregister());') }
    const candidate = path.resolve(root, '.' + decodeURIComponent(url.pathname))
    if (!within(candidate)) return send(res, { code: 'PREVIEW_PATH', message: '不可访问预览目录之外的文件' }, 403)
    let target
    try { target = await realpath(candidate) } catch (error) { if (error.code !== 'ENOENT' && error.code !== 'ENOTDIR') throw error; target = indexFile }
    if (!within(target)) return send(res, { code: 'PREVIEW_PATH', message: '不可访问预览目录之外的文件' }, 403)
    if (!(await stat(target)).isFile()) target = indexFile
    let content = await readFile(target)
    if (target.endsWith('index.html')) {
      const theme = url.searchParams.get('theme') === 'dark' ? 'dark' : 'light'
      content = Buffer.from(content.toString().replace('<head>', `<head><script>localStorage.setItem('qimeng_token','preview-session');localStorage.setItem('qimeng_theme',${JSON.stringify(theme)});</script><style>body::after{content:'虚拟数据预览 · 不连接真实媒体库';position:fixed;bottom:8px;left:92px;padding:4px 10px;border-radius:999px;background:var(--qm-surface-strong);color:var(--qm-text-muted);font:11px var(--qm-font);z-index:99999;pointer-events:none}</style>`).replace('<title>绮梦影库</title>', '<title>绮梦影库 · 虚拟数据预览</title>'))
    }
    res.writeHead(200, { 'Content-Type': mime[path.extname(target)] ?? 'application/octet-stream' }); res.end(req.method === 'HEAD' ? undefined : content)
  } catch { if (!res.headersSent) send(res, { code: 'PREVIEW_INVALID', message: '虚拟预览请求无效' }, 400); else res.end() }
}).listen(port, '127.0.0.1', () => console.log(`Synthetic UI preview: http://127.0.0.1:${port} (${FIXTURE_COUNT} fixture assets)`))
