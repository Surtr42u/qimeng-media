import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile, symlink, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { setTimeout as delay } from 'node:timers/promises'
import http from 'node:http'
import { fileURLToPath } from 'node:url'

// 云端隔离端口，仅运行临时合成Web目录；不访问本机真实服务。
const PORT = 19974
test('预览只托管显式Web根，拒绝编码穿越和链接逃逸，未知API不回退成成功', { timeout: 15000 }, async () => {
  const temp = await mkdtemp(path.join(tmpdir(), 'qimeng-preview-'))
  const dist = path.join(temp, 'web'); await mkdir(dist)
  await writeFile(path.join(dist, 'index.html'), '<html><head><title>绮梦影库</title></head><body>fixture</body></html>')
  await writeFile(path.join(temp, 'outside.txt'), 'synthetic-outside-root')
  await symlink(path.join(temp, 'outside.txt'), path.join(dist, 'escape.txt'))
  const child = spawn(process.execPath, [fileURLToPath(new URL('./server.mjs', import.meta.url)), '--dist', dist, '--port', String(PORT)], { stdio: ['ignore', 'ignore', 'pipe'], windowsHide: true })
  let stderr = ''; child.stderr.on('data', data => { stderr += data })
  const request = target => new Promise((resolve, reject) => {
    http.get({ host: '127.0.0.1', port: PORT, path: target }, res => { let body = ''; res.on('data', chunk => { body += chunk }); res.on('end', () => resolve({ status: res.statusCode, body, headers: res.headers })) }).on('error', reject)
  })
  try {
    let ready = false
    for (let attempt = 0; attempt < 50; attempt++) {
      if (child.exitCode !== null) throw new Error(`Synthetic server exited: ${stderr}`)
      try { ready = (await request('/__preview/health')).status === 200 } catch { /* 等待隔离进程监听 */ }
      if (ready) break
      await delay(50)
    }
    assert.ok(ready)
    assert.equal((await request('/app/albums')).status, 200)
    assert.equal((await request('/escape.txt')).status, 403)
    assert.equal((await request('/%2e%2e%2foutside.txt')).status, 403)
    assert.equal((await request('/api/v1/unknown')).status, 404)
    assert.equal((await request('/api/v1/assets?includeCos=true&limit=5')).headers['x-qimeng-preview'], 'synthetic-only')
  } finally {
    if (child.exitCode === null) { child.kill(); await once(child, 'exit') }
    assert.ok(path.resolve(temp).startsWith(path.resolve(tmpdir()) + path.sep) && path.basename(temp).startsWith('qimeng-preview-'))
    await rm(temp, { recursive: true, force: true })
  }
})
