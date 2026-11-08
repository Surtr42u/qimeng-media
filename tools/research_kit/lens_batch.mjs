#!/usr/bin/env node
// tools/research_kit/lens_batch.mjs
//
// Low-token batch Google Lens reverse-image search over a local headless Edge/Chrome
// driven directly through CDP. Node 24+ standard library only (global fetch/WebSocket);
// no npm dependencies. One browser, one page, strict single-flight.
//
// Security/privacy:
// - No HTTP listener and no token in this tool; it only talks to the local browser's
//   loopback debugging port (random, loopback only).
// - The page text is used in memory for heuristics only and is never written to disk;
//   results are bounded title/domain summaries.
// - It does not bypass captchas, consent walls or login pages: any risk signal stops
//   the run and writes a cooldown marker for the ignored cache directory.
// - Caches/results live under an ignored directory (default build/research-kit.local/).
//   Real media never enters the repository.
//
// Usage: node tools/research_kit/lens_batch.mjs --jobs jobs.jsonl [options]
// See tools/research_kit/README.md for the contract and token budget.

import crypto from 'node:crypto';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';

const TOOL = 'lens_batch';
const VERSION = '1.0.0';
const MAX_FILE_BYTES = 20 * 1024 * 1024;
const MAX_HEAD_BYTES = 1024 * 1024;
const ALLOWED_EXT = new Set(['.jpg', '.jpeg', '.png', '.webp']);
const BLOCK_COOLDOWN_MS = 60 * 60 * 1000;
const EXIT = { ok: 0, partial: 1, usage: 2 };

const USAGE = [
  'lens_batch ' + VERSION + ' - batch Google Lens via headless Edge/CDP (Node 24+, no npm)',
  '',
  'Usage:',
  '  node tools/research_kit/lens_batch.mjs --jobs <jobs.jsonl> [options]',
  '',
  'Input jobs (JSONL, one object per line; relative paths resolve from the current directory):',
  '  {"id":"job-001","file":"build/research-kit.local/frames/example.webp","rid":"optional-note"}',
  '',
  'Output results (JSONL, append + resume):',
  '  {"id":"job-001","status":"ok","topTitles":[{"title":"...","domain":"x.com"}],',
  '   "topDomains":["x.com"],"citesCount":2,"resultUrl":"...","ts":"...","frameSha256":"..."}',
  '',
  'Options:',
  '  --jobs FILE            jobs JSONL (required)',
  '  --out FILE             results JSONL; default <cache>/lens/results.jsonl',
  '  --cache-dir DIR        default $QIMENG_RESEARCH_KIT_CACHE or build/research-kit.local/cache',
  '  --min-interval-ms N    wait between job starts (default 8000)',
  '  --timeout-ms N         per-job budget (default 120000)',
  '  --attempts N           attempts per job, 1..3 (default 2; blocked/timeout never retried)',
  '  --limit N              process at most N pending jobs (0 = all)',
  '  --max-dim N            warn when the image is larger than N px (default 1600)',
  '  --max-age-hours N      cache TTL for successful frame results (default 720)',
  '  --edge PATH            Edge/Chrome executable; default $QIMENG_RESEARCH_KIT_EDGE,',
  '                         then $QIMENG_RESEARCH_SIDECAR_EDGE, then platform defaults',
  '  --profile DIR          browser profile; default <cache>/lens/profile',
  '  --reset-profile        delete the profile before starting (cookies are sensitive)',
  '  --ignore-block-cooldown  ignore the blocked marker (do not do this after a captcha)',
  '  --retry-errors         retry ids whose recorded status was not ok',
  '  --no-cache             ignore and do not write the frame cache',
  '  --verbose              per-job progress on stderr',
  '  --pretty               indent the stdout summary',
  '  --help                 show this text',
  '',
  'Exit codes: 0 all processed jobs ok; 1 partial/blocked/timeout; 2 usage or environment error.',
  'Images should be pre-scaled to <=1600px WebP; this tool warns but does not re-encode.',
  '',
].join('\n');

class LensError extends Error {
  constructor(status, code, extra) {
    super(code);
    this.name = 'LensError';
    this.status = status;
    this.code = code;
    this.extra = extra || {};
  }
}

class KitError extends Error {
  constructor(exitCode, message, summary) {
    super(message);
    this.name = 'KitError';
    this.exitCode = exitCode;
    this.summary = summary || null;
  }
}

const sleepFor = (ms) => new Promise((resolve) => setTimeout(resolve, Math.max(0, ms)));
const nowIso = () => new Date().toISOString();

function clip(value, limit) {
  return String(value === undefined || value === null ? '' : value)
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, limit);
}

function parseInteger(raw, name, min, max, errors) {
  const value = Number(raw);
  if (!Number.isInteger(value) || value < min || value > max) {
    errors.push(name + ' must be an integer between ' + min + ' and ' + max);
    return null;
  }
  return value;
}

function parseArgs(argv) {
  const opts = {
    jobs: '',
    out: '',
    cacheDir: '',
    minIntervalMs: 8000,
    timeoutMs: 120000,
    attempts: 2,
    limit: 0,
    maxDim: 1600,
    maxAgeHours: 720,
    edge: '',
    profile: '',
    resetProfile: false,
    ignoreCooldown: false,
    retryErrors: false,
    noCache: false,
    verbose: false,
    pretty: false,
    help: false,
  };
  const errors = [];
  for (let index = 0; index < argv.length; index += 1) {
    const raw = argv[index];
    if (!raw.startsWith('--')) {
      errors.push('unexpected argument: ' + raw);
      continue;
    }
    const eq = raw.indexOf('=');
    const name = eq >= 0 ? raw.slice(0, eq) : raw;
    const inline = eq >= 0 ? raw.slice(eq + 1) : null;
    const value = () => {
      if (inline !== null) return inline;
      const next = argv[index + 1];
      if (next === undefined || next.startsWith('--')) {
        errors.push(name + ' requires a value');
        return '';
      }
      index += 1;
      return next;
    };
    switch (name) {
      case '--jobs': opts.jobs = value(); break;
      case '--out': opts.out = value(); break;
      case '--cache-dir': opts.cacheDir = value(); break;
      case '--min-interval-ms': opts.minIntervalMs = parseInteger(value(), name, 0, 600000, errors); break;
      case '--timeout-ms': opts.timeoutMs = parseInteger(value(), name, 20000, 600000, errors); break;
      case '--attempts': opts.attempts = parseInteger(value(), name, 1, 3, errors); break;
      case '--limit': opts.limit = parseInteger(value(), name, 0, 100000, errors); break;
      case '--max-dim': opts.maxDim = parseInteger(value(), name, 0, 100000, errors); break;
      case '--max-age-hours': opts.maxAgeHours = parseInteger(value(), name, 0, 87600, errors); break;
      case '--edge': opts.edge = value(); break;
      case '--profile': opts.profile = value(); break;
      case '--reset-profile': opts.resetProfile = true; break;
      case '--ignore-block-cooldown': opts.ignoreCooldown = true; break;
      case '--retry-errors': opts.retryErrors = true; break;
      case '--no-cache': opts.noCache = true; break;
      case '--verbose': opts.verbose = true; break;
      case '--pretty': opts.pretty = true; break;
      case '--help': case '-h': opts.help = true; break;
      default: errors.push('unknown option: ' + name); break;
    }
  }
  return { opts, errors };
}

function findRepoRoot() {
  let current = path.resolve(process.cwd());
  for (let depth = 0; depth < 8; depth += 1) {
    if (fs.existsSync(path.join(current, 'AGENTS.md')) && fs.existsSync(path.join(current, 'tools'))) return current;
    const parent = path.dirname(current);
    if (parent === current) break;
    current = parent;
  }
  return null;
}

function displayPath(target) {
  const relative = path.relative(process.cwd(), target);
  return relative && !relative.startsWith('..') && !path.isAbsolute(relative) ? relative : target;
}

function defaultCacheDir() {
  const env = (process.env.QIMENG_RESEARCH_KIT_CACHE || '').trim();
  if (env) return path.resolve(env);
  const root = findRepoRoot();
  return path.join(root || process.cwd(), 'build', 'research-kit.local', 'cache');
}

function defaultEdgePath() {
  const explicit = (process.env.QIMENG_RESEARCH_KIT_EDGE || '').trim();
  if (explicit) return explicit;
  const sidecar = (process.env.QIMENG_RESEARCH_SIDECAR_EDGE || '').trim();
  if (sidecar) return sidecar;
  const candidates = process.platform === 'win32'
    ? [
        'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
        'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
        'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
        'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
      ]
    : [
        '/usr/bin/microsoft-edge',
        '/usr/bin/microsoft-edge-stable',
        '/usr/bin/google-chrome',
        '/usr/bin/google-chrome-stable',
        '/usr/bin/chromium',
        '/usr/bin/chromium-browser',
      ];
  return candidates.find((candidate) => fs.existsSync(candidate)) || candidates[0];
}

function ensureNodeVersion() {
  const major = Number((process.versions.node || '0').split('.')[0]);
  if (!Number.isFinite(major) || major < 24) {
    throw new KitError(EXIT.usage, 'lens_batch requires Node.js 24 or newer (global fetch/WebSocket)');
  }
}

function readJobs(filePath) {
  let text;
  try {
    text = fs.readFileSync(filePath, 'utf8').replace(/^\uFEFF/, '');
  } catch (error) {
    throw new KitError(EXIT.usage, 'cannot read jobs file: ' + (error && error.code ? error.code : 'read_error'));
  }
  const jobs = [];
  const seen = new Set();
  let bad = 0;
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    let item;
    try {
      item = JSON.parse(line);
    } catch {
      bad += 1;
      continue;
    }
    if (!item || typeof item !== 'object' || Array.isArray(item)) { bad += 1; continue; }
    const id = typeof item.id === 'number' ? String(item.id) : item.id;
    if (typeof id !== 'string' || !id.trim() || id.length > 200) { bad += 1; continue; }
    if (typeof item.file !== 'string' || !item.file.trim()) { bad += 1; continue; }
    if (seen.has(id.trim())) {
      throw new KitError(EXIT.usage, 'duplicate job id in jobs file: ' + id.trim());
    }
    seen.add(id.trim());
    jobs.push({ id: id.trim(), file: item.file.trim(), rid: typeof item.rid === 'string' ? item.rid.slice(0, 200) : '' });
  }
  if (!jobs.length) throw new KitError(EXIT.usage, 'jobs file contains no valid job');
  return { jobs, bad };
}

function readExistingIds(outPath, retryErrors) {
  const done = new Set();
  if (!fs.existsSync(outPath)) return done;
  let text = '';
  try {
    text = fs.readFileSync(outPath, 'utf8');
  } catch {
    throw new KitError(EXIT.usage, 'cannot read existing results file: ' + outPath);
  }
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    try {
      const item = JSON.parse(line);
      if (!item || typeof item.id !== 'string') continue;
      if (retryErrors && item.status !== 'ok' && item.status !== 'skipped') continue;
      done.add(item.id);
    } catch {
      // A truncated trailing line is expected after a crash; it is simply not resumed.
    }
  }
  return done;
}

function appendResult(outPath, record) {
  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.appendFileSync(outPath, JSON.stringify(record) + '\n', 'utf8');
}

function readImageInfo(buffer) {
  if (!buffer || buffer.length < 12) return null;
  if (buffer[0] === 0x89 && buffer.toString('ascii', 1, 4) === 'PNG') {
    if (buffer.length >= 24) return { format: 'png', width: buffer.readUInt32BE(16), height: buffer.readUInt32BE(20) };
    return { format: 'png' };
  }
  if (buffer[0] === 0xff && buffer[1] === 0xd8) {
    let offset = 2;
    while (offset + 9 < buffer.length) {
      if (buffer[offset] !== 0xff) { offset += 1; continue; }
      const marker = buffer[offset + 1];
      if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) { offset += 2; continue; }
      if (marker === 0xd9 || marker === 0xda) break;
      const length = buffer.readUInt16BE(offset + 2);
      if (length < 2) break;
      if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
        return { format: 'jpeg', height: buffer.readUInt16BE(offset + 5), width: buffer.readUInt16BE(offset + 7) };
      }
      offset += 2 + length;
    }
    return { format: 'jpeg' };
  }
  if (buffer.toString('ascii', 0, 4) === 'RIFF' && buffer.toString('ascii', 8, 12) === 'WEBP') {
    const kind = buffer.toString('ascii', 12, 16);
    if (kind === 'VP8X' && buffer.length >= 30) {
      const width = 1 + (buffer[24] | (buffer[25] << 8) | (buffer[26] << 16));
      const height = 1 + (buffer[27] | (buffer[28] << 8) | (buffer[29] << 16));
      return { format: 'webp', width, height };
    }
    if (kind === 'VP8 ' && buffer.length >= 30) {
      const width = buffer.readUInt16LE(26) & 0x3fff;
      const height = buffer.readUInt16LE(28) & 0x3fff;
      if (width && height) return { format: 'webp', width, height };
    }
    return { format: 'webp' };
  }
  return null;
}

async function hashFile(filePath) {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash('sha256');
    const stream = fs.createReadStream(filePath);
    stream.on('data', (chunk) => hash.update(chunk));
    stream.on('error', reject);
    stream.on('end', () => resolve(hash.digest('hex')));
  });
}

function sanitizeResultUrl(raw) {
  try {
    const url = new URL(String(raw));
    if (!/(^|\.)google\.[a-z.]+$/i.test(url.hostname)) return '';
    const keep = new Set(['udm', 'lns_mode', 'lns_surface', 'q']);
    const params = new URLSearchParams();
    for (const key of keep) if (url.searchParams.has(key)) params.set(key, url.searchParams.get(key));
    return url.origin + url.pathname + (params.toString() ? '?' + params.toString() : '');
  } catch {
    return '';
  }
}

const HOST_RE = /(?:https?:\/\/)?((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,24})(?=$|[\s/\u203a|,;)\]\u3002\uff0c])/gi;
const BAD_HOST_SUFFIX = /\.(jpg|jpeg|png|webp|gif|mp4|js|css|html|php|svg)$/i;
const OK_TLDS = new Set(
  ('com net org edu gov io co jp cn us ca uk de fr ru cc re me tv info biz xyz app dev art blog shop site online pro ' +
    'vn id kr tw hk sg au br mx es it nl se no fi pl ch at be cz gr pt ro sk ua tr il in th my ph pe ar cl za ie nz ' +
    'dk hu bg hr lt lv ee si lu is mt cy ly to gg ai sh fm moe wiki top club live news store fun space website tech ' +
    'design photography pics video network solutions agency').split(' '),
);
const UI_LINE = /^(skip to main content|accessibility help|sign in|all|exact matches|visual matches|feedback|search results|ai overview|show all|translate this page|help|send feedback|privacy|terms|images|shopping|videos|news|maps|books|flights|finance|search|settings|跳过主要内容|无障碍功能帮助|翻译|登录|AI 模式|全部|完全匹配|外观匹配|反馈|搜索结果|AI 概览|全部显示|翻译此页|页脚链接|帮助|发送反馈|隐私权|条款|查看更多结果)$/i;

function normalizeHost(host) {
  return String(host).toLowerCase().replace(/^www\./, '').replace(/\.$/, '');
}

function textHosts(line) {
  const out = [];
  HOST_RE.lastIndex = 0;
  let match;
  while ((match = HOST_RE.exec(line)) !== null) {
    const host = normalizeHost(match[1]);
    if (!host || /(^|\.)google\.[a-z.]+$/i.test(host) || BAD_HOST_SUFFIX.test(host)) continue;
    if (!OK_TLDS.has(host.split('.').pop())) continue;
    out.push(host);
  }
  return [...new Set(out)];
}

function parseTextItems(text) {
  const lines = String(text || '').split(/\r?\n/).map((line) => line.trim());
  const items = [];
  const seen = new Set();
  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    if (!line) continue;
    for (const host of textHosts(line)) {
      if (seen.has(host)) continue;
      seen.add(host);
      let title = '';
      if (!/^https?:\/\//.test(line)) {
        for (let next = index + 1; next < Math.min(index + 4, lines.length); next += 1) {
          const candidate = lines[next];
          if (!candidate) continue;
          if (textHosts(candidate).length) break;
          if (UI_LINE.test(candidate)) continue;
          if (candidate.length < 6 || candidate.length > 220) continue;
          if (/^\d{4}[-年]\d{1,2}[-月]\d{1,2}/.test(candidate)) continue;
          title = candidate;
          break;
        }
      }
      items.push({ domain: host, title: clip(title, 160) });
      if (items.length >= 30) return items;
    }
  }
  return items;
}

function buildTopTitles(items) {
  const out = [];
  const seen = new Set();
  for (const item of items) {
    if (!item.title || item.title.length < 6) continue;
    const key = item.title + '|' + item.domain;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ title: item.title, domain: item.domain });
    if (out.length >= 8) break;
  }
  return out;
}

function buildTopDomains(items) {
  const out = [];
  for (const item of items) {
    if (!out.includes(item.domain)) out.push(item.domain);
    if (out.length >= 8) break;
  }
  return out;
}

function safeHost(raw) {
  try {
    return new URL(String(raw)).hostname.toLowerCase();
  } catch {
    return '';
  }
}

function riskSignals(text, url, title) {
  const out = [];
  const sample = String(text || '').slice(0, 8000);
  if (/unusual traffic/i.test(sample)) out.push('unusual_traffic');
  if (/not a robot|recaptcha|verify you are human|automated queries/i.test(sample)) out.push('captcha');
  if (/验证码|异常流量|人机验证|自动查询|机器人/.test(sample)) out.push('captcha_zh');
  if (/\/sorry\//.test(String(url || ''))) out.push('sorry_url');
  if (/^Sorry\.\.\./.test(String(title || ''))) out.push('sorry_title');
  if (/too many requests/i.test(sample)) out.push('too_many_requests');
  if (/(^|\.)consent\.google\.com$/i.test(safeHost(url))) out.push('consent_wall');
  if (/(^|\.)accounts\.google\.com$/i.test(safeHost(url))) out.push('login_wall');
  return [...new Set(out)];
}

function isLensResultUrl(raw) {
  try {
    const url = new URL(String(raw));
    return /(^|\.)google\.[a-z.]+$/i.test(url.hostname) && url.pathname.indexOf('/search') === 0;
  } catch {
    return false;
  }
}

// The page helper returns external http(s) cites only. Text stays in memory.
function pageExtract() {
  function isGoogleHost(host) {
    return /(^|\.)google\.[a-z.]+$/i.test(host) || /(^|\.)gstatic\.com$/i.test(host) || /(^|\.)googleusercontent\.com$/i.test(host);
  }
  function unwrap(raw) {
    try {
      let href = raw || '';
      let url = new URL(href, location.href);
      if (isGoogleHost(url.hostname) && url.pathname === '/url') {
        href = url.searchParams.get('q') || url.searchParams.get('url') || '';
        if (!href) return '';
        url = new URL(href, location.href);
      }
      if (url.protocol !== 'http:' && url.protocol !== 'https:') return '';
      if (isGoogleHost(url.hostname)) return '';
      return url.href;
    } catch {
      return '';
    }
  }
  const cites = [];
  const seen = Object.create(null);
  const nodes = document.querySelectorAll('cite');
  for (let index = 0; index < nodes.length; index += 1) {
    const cite = nodes[index];
    const text = (cite.innerText || '').trim().slice(0, 300);
    if (!text) continue;
    const anchor = cite.closest('a') || cite.querySelector('a');
    if (!anchor) continue;
    const href = unwrap(anchor.href);
    if (!href || seen[href]) continue;
    seen[href] = true;
    cites.push(href);
    if (cites.length >= 80) break;
  }
  return { cites };
}

function pageAttachProbe() {
  const element = document.querySelector('input[name="encoded_image"]');
  const inputs = [];
  const nodes = document.querySelectorAll('input[type=file]');
  for (let index = 0; index < nodes.length; index += 1) {
    inputs.push({ name: (nodes[index].name || '').slice(0, 60), accept: (nodes[index].accept || '').slice(0, 80) });
    if (inputs.length >= 5) break;
  }
  return { found: Boolean(element), inputs };
}

function pageConsentClick() {
  const wanted = ['accept all', 'accept all cookies', '接受全部', '全部接受', '同意', 'i agree', 'agree to all'];
  const nodes = document.querySelectorAll('button, [role="button"], input[type="submit"], a');
  for (let index = 0; index < nodes.length; index += 1) {
    const element = nodes[index];
    const text = ((element.innerText || element.value || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
    if (!text || text.length > 40) continue;
    for (const phrase of wanted) {
      if (text === phrase || text.indexOf(phrase) >= 0) {
        try { element.click(); return true; } catch { /* ignore */ }
      }
    }
  }
  return false;
}

const ATTACH_PROBE_EXPR = '(' + pageAttachProbe.toString() + ')()';
const CONSENT_CLICK_EXPR = '(' + pageConsentClick.toString() + ')()';
const EXTRACT_EXPR = '(' + pageExtract.toString() + ')()';

class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.id = 0;
    this.pending = new Map();
    this.closed = false;
    ws.addEventListener('message', (event) => this.onMessage(event));
    ws.addEventListener('close', () => { this.closed = true; this.failPending('cdp socket closed'); });
    ws.addEventListener('error', () => { this.closed = true; this.failPending('cdp socket error'); });
  }

  static async connect(url) {
    if (!/^ws:\/\/(127\.0\.0\.1|localhost|\[::1\]):\d+\//.test(String(url))) {
      throw new LensError('error', 'CDP_URL_INVALID');
    }
    const ws = new WebSocket(url);
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        try { ws.close(); } catch { /* ignore */ }
        reject(new LensError('error', 'CDP_CONNECT_TIMEOUT'));
      }, 10000);
      ws.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
      ws.addEventListener('error', () => { clearTimeout(timer); reject(new LensError('error', 'CDP_CONNECT_ERROR')); }, { once: true });
    });
    return new Cdp(ws);
  }

  failPending(message) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(new LensError('error', message));
    }
    this.pending.clear();
  }

  onMessage(event) {
    let message;
    try {
      const raw = typeof event.data === 'string' ? event.data : Buffer.from(event.data).toString('utf8');
      message = JSON.parse(raw);
    } catch {
      return;
    }
    if (message.id && this.pending.has(message.id)) {
      const pending = this.pending.get(message.id);
      clearTimeout(pending.timer);
      this.pending.delete(message.id);
      if (message.error) pending.reject(new LensError('error', 'CDP_COMMAND_FAILED'));
      else pending.resolve(message.result || {});
    }
  }

  send(method, params, timeoutMs) {
    if (this.closed || this.ws.readyState !== WebSocket.OPEN) {
      return Promise.reject(new LensError('error', 'CDP_NOT_OPEN'));
    }
    const id = (this.id += 1);
    const timeout = Math.max(1, Number(timeoutMs) || 30000);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (this.pending.delete(id)) reject(new LensError('error', 'CDP_TIMEOUT'));
      }, timeout);
      this.pending.set(id, { resolve, reject, timer });
      try {
        this.ws.send(JSON.stringify({ id, method, params: params || {} }));
      } catch {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(new LensError('error', 'CDP_SEND_FAILED'));
      }
    });
  }

  close() {
    this.closed = true;
    this.failPending('cdp session closed');
    try { this.ws.close(); } catch { /* ignore */ }
  }
}

function pickFreePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      server.close(() => resolve(port));
    });
  });
}

async function newPage(port) {
  const endpoint = 'http://127.0.0.1:' + port + '/json/new?about:blank';
  let response = null;
  try {
    response = await fetch(endpoint, { method: 'PUT', signal: AbortSignal.timeout(5000) });
  } catch {
    response = null;
  }
  if (!response || !response.ok) {
    try {
      response = await fetch(endpoint, { method: 'GET', signal: AbortSignal.timeout(5000) });
    } catch {
      response = null;
    }
  }
  if (!response || !response.ok) throw new LensError('error', 'PAGE_CREATE_FAILED');
  let target;
  try {
    target = await response.json();
  } catch {
    throw new LensError('error', 'PAGE_DESCRIPTOR_INVALID');
  }
  if (!target || !target.id || !target.webSocketDebuggerUrl) throw new LensError('error', 'PAGE_DESCRIPTOR_INCOMPLETE');
  return target;
}

async function closePage(port, id) {
  if (!port || !id) return;
  try {
    await fetch('http://127.0.0.1:' + port + '/json/close/' + encodeURIComponent(id), { signal: AbortSignal.timeout(3000) });
  } catch {
    // Best effort; the browser exits at the end of the run.
  }
}

async function waitForCdp(port, timeoutMs, browserState) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    if (browserState.failed) throw new KitError(EXIT.usage, 'browser failed to start: ' + (browserState.error || 'unknown'));
    try {
      const response = await fetch('http://127.0.0.1:' + port + '/json/version', { signal: AbortSignal.timeout(1500) });
      if (response.ok) return await response.json();
    } catch {
      // not ready yet
    }
    await sleepFor(400);
  }
  throw new KitError(EXIT.usage, 'browser CDP endpoint did not become ready');
}

function killBrowser(child, pid) {
  if (child) {
    try { child.kill(); } catch { /* ignore */ }
  }
  if (process.platform === 'win32' && pid) {
    try {
      spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true });
    } catch {
      // ignore
    }
  }
}

function assertDeadline(deadline) {
  if (Date.now() >= deadline) throw new LensError('timeout', 'DEADLINE_EXCEEDED');
}

function cdpSend(cdp, method, params, deadline, cap) {
  if (Date.now() >= deadline) return Promise.reject(new LensError('timeout', 'DEADLINE_EXCEEDED'));
  const timeout = Math.max(1000, Math.min(Number(cap) || 30000, deadline - Date.now()));
  return cdp.send(method, params, timeout);
}

async function evaluate(cdp, expression, deadline, cap) {
  const result = await cdpSend(cdp, 'Runtime.evaluate',
    { expression, returnByValue: true, awaitPromise: true, userGesture: true }, deadline, cap);
  if (result && result.exceptionDetails) throw new LensError('error', 'PAGE_EVAL_FAILED');
  return result && result.result ? result.result.value : undefined;
}

async function attachFile(cdp, filePath, deadline) {
  const attachDeadline = Math.min(deadline, Date.now() + 20000);
  for (let attempt = 1; attempt <= 20; attempt += 1) {
    assertDeadline(deadline);
    let probe = null;
    try {
      probe = await evaluate(cdp, ATTACH_PROBE_EXPR, deadline, 8000);
    } catch (error) {
      if (error instanceof LensError && error.status === 'timeout') throw error;
    }
    if (probe && probe.found) {
      const document = await cdpSend(cdp, 'DOM.getDocument', { depth: -1, pierce: true }, deadline, 15000);
      const rootId = document && document.root ? document.root.nodeId : 0;
      if (rootId) {
        const query = await cdpSend(cdp, 'DOM.querySelector',
          { nodeId: rootId, selector: 'input[name="encoded_image"]' }, deadline, 15000);
        if (query && query.nodeId) {
          await cdpSend(cdp, 'DOM.setFileInputFiles', { files: [filePath], nodeId: query.nodeId }, deadline, 15000);
          return attempt;
        }
      }
    }
    if (attempt >= 20 || Date.now() >= attachDeadline) break;
    await sleepFor(Math.min(1000, Math.max(0, attachDeadline - Date.now())));
  }
  throw new LensError('upload_rejected', 'ENCODED_IMAGE_MISSING');
}

async function lensOnce(cdp, filePath, deadline) {
  await cdpSend(cdp, 'Page.navigate', { url: 'https://lens.google.com/upload?ep=ccm&s=4&hl=en' }, deadline, 30000);
  await sleepFor(Math.min(2500, Math.max(0, deadline - Date.now())));
  let url = (await evaluate(cdp, 'location.href', deadline, 8000)) || '';
  let consentTried = false;
  if (/(^|\.)consent\.google\.com$/i.test(safeHost(url))) {
    consentTried = true;
    await evaluate(cdp, CONSENT_CLICK_EXPR, deadline, 5000);
    await sleepFor(1500);
    url = (await evaluate(cdp, 'location.href', deadline, 8000)) || '';
    if (/(^|\.)consent\.google\.com$/i.test(safeHost(url))) {
      throw new LensError('blocked', 'CONSENT_WALL', { signals: ['consent_wall'], resultUrl: sanitizeResultUrl(url) });
    }
  }
  if (/(^|\.)accounts\.google\.com$/i.test(safeHost(url))) {
    throw new LensError('blocked', 'LOGIN_WALL', { signals: ['login_wall'], resultUrl: sanitizeResultUrl(url) });
  }
  const attachAttempts = await attachFile(cdp, filePath, deadline);

  let text = '';
  let title = '';
  let lastLength = -1;
  let stable = 0;
  for (;;) {
    assertDeadline(deadline);
    await sleepFor(Math.min(2000, Math.max(0, deadline - Date.now())));
    url = (await evaluate(cdp, 'location.href', deadline, 8000)) || '';
    if (/(^|\.)consent\.google\.com$/i.test(safeHost(url))) {
      if (consentTried) throw new LensError('blocked', 'CONSENT_WALL', { signals: ['consent_wall'] });
      consentTried = true;
      await evaluate(cdp, CONSENT_CLICK_EXPR, deadline, 5000);
      await sleepFor(1500);
      continue;
    }
    if (/(^|\.)accounts\.google\.com$/i.test(safeHost(url))) {
      throw new LensError('blocked', 'LOGIN_WALL', { signals: ['login_wall'], resultUrl: sanitizeResultUrl(url) });
    }
    text = (await evaluate(cdp, 'document.body ? document.body.innerText : ""', deadline, 20000)) || '';
    title = (await evaluate(cdp, 'document.title', deadline, 8000)) || '';
    const signals = riskSignals(text, url, title);
    if (signals.length) {
      throw new LensError('blocked', 'RISK_SIGNAL', { signals, resultUrl: sanitizeResultUrl(url) });
    }
    if (isLensResultUrl(url)) {
      if (text.length === lastLength) stable += 1;
      else stable = 0;
      lastLength = text.length;
      if (stable >= 3 && text.length > 300) break;
      if (stable >= 5 && text.length > 200) break;
    }
  }
  if (!isLensResultUrl(url)) {
    throw new LensError('not_found', 'RESULT_PAGE_MISSING', { resultUrl: sanitizeResultUrl(url), textLen: text.length });
  }
  if (text.length < 200) {
    throw new LensError('not_found', 'RESULT_TEXT_SHORT', { resultUrl: sanitizeResultUrl(url), textLen: text.length });
  }
  let raw = { cites: [] };
  try {
    raw = (await evaluate(cdp, EXTRACT_EXPR, deadline, 30000)) || { cites: [] };
  } catch {
    raw = { cites: [] };
  }
  const items = parseTextItems(text);
  return {
    status: 'ok',
    topTitles: buildTopTitles(items),
    topDomains: buildTopDomains(items),
    citesCount: Array.isArray(raw.cites) ? raw.cites.length : 0,
    resultUrl: sanitizeResultUrl(url),
    textLen: text.length,
    attachAttempts,
  };
}

async function runJob(cdp, job, opts) {
  const started = Date.now();
  const deadline = started + opts.timeoutMs;
  let lastError = null;
  for (let attempt = 1; attempt <= opts.attempts; attempt += 1) {
    try {
      const data = await lensOnce(cdp, job.file, deadline);
      return { data, attempts: attempt, elapsedMs: Date.now() - started };
    } catch (error) {
      lastError = error;
      if (!(error instanceof LensError)) break;
      if (error.status !== 'error' && error.status !== 'upload_rejected') break;
      if (attempt >= opts.attempts) break;
      await sleepFor(2000 + Math.floor(Math.random() * 2000));
    }
  }
  throw lastError instanceof LensError ? lastError : new LensError('error', 'JOB_FAILED');
}

function baseRecord(job) {
  return {
    id: job.id,
    status: 'error',
    topTitles: [],
    topDomains: [],
    citesCount: 0,
    resultUrl: '',
    ts: nowIso(),
  };
}

function errorRecord(job, error) {
  const record = baseRecord(job);
  if (error instanceof LensError) {
    record.status = error.status;
    record.errorCode = error.code;
    if (error.extra && error.extra.resultUrl) record.resultUrl = error.extra.resultUrl;
    if (Array.isArray(error.extra && error.extra.signals) && error.extra.signals.length) {
      record.blockedSignals = error.extra.signals.slice(0, 4);
    }
  } else {
    record.errorCode = 'UNEXPECTED';
  }
  record.ts = nowIso();
  return record;
}

function loadCached(cacheDir, sha, maxAgeHours) {
  const file = path.join(cacheDir, 'lens', 'cache', sha + '.json');
  if (!fs.existsSync(file)) return null;
  const ageMs = Date.now() - fs.statSync(file).mtimeMs;
  if (maxAgeHours > 0 && ageMs > maxAgeHours * 3600 * 1000) return null;
  try {
    const payload = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (!payload || payload.status !== 'ok') return null;
    return payload;
  } catch {
    return null;
  }
}

function saveCached(cacheDir, sha, payload) {
  const dir = path.join(cacheDir, 'lens', 'cache');
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, sha + '.json');
  fs.writeFileSync(file, JSON.stringify(payload), 'utf8');
}

function cooldownPath(cacheDir) {
  return path.join(cacheDir, 'lens', 'blocked.json');
}

function readCooldown(cacheDir) {
  try {
    const payload = JSON.parse(fs.readFileSync(cooldownPath(cacheDir), 'utf8'));
    if (payload && Number.isFinite(payload.at)) return payload;
  } catch {
    // no marker
  }
  return null;
}

function writeCooldown(cacheDir, signals) {
  const file = cooldownPath(cacheDir);
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify({ at: Date.now(), iso: nowIso(), signals: (signals || []).slice(0, 4) }), 'utf8');
}

let stopping = false;
let activeBrowser = null;
let activeLockPath = null;

async function run(opts) {
  const cacheDir = path.resolve(opts.cacheDir || defaultCacheDir());
  const outPath = path.resolve(opts.out || path.join(cacheDir, 'lens', 'results.jsonl'));
  const profilePath = path.resolve(opts.profile || path.join(cacheDir, 'lens', 'profile'));
  const { jobs, bad } = readJobs(path.resolve(opts.jobs));
  const done = readExistingIds(outPath, opts.retryErrors);
  let pending = jobs.filter((job) => !done.has(job.id));
  if (opts.limit > 0) pending = pending.slice(0, opts.limit);

  const summary = {
    tool: TOOL,
    version: VERSION,
    status: 'ok',
    jobs: jobs.length,
    badLines: bad,
    skippedExisting: jobs.length - pending.length,
    pending: pending.length,
    processed: 0,
    ok: 0,
    blocked: 0,
    failed: 0,
    cacheHits: 0,
    skipped: 0,
    remaining: 0,
    out: displayPath(outPath),
    cacheDir: displayPath(cacheDir),
  };

  if (!pending.length) {
    console.log(JSON.stringify(summary, null, opts.pretty ? 2 : 0));
    return EXIT.ok;
  }

  if (!opts.ignoreCooldown) {
    const marker = readCooldown(cacheDir);
    if (marker && Date.now() - marker.at < BLOCK_COOLDOWN_MS) {
      summary.status = 'blocked_cooldown';
      summary.cooldownUntil = new Date(marker.at + BLOCK_COOLDOWN_MS).toISOString();
      summary.blockedSignals = Array.isArray(marker.signals) ? marker.signals : [];
      summary.remaining = pending.length;
      console.log(JSON.stringify(summary, null, opts.pretty ? 2 : 0));
      console.error('[lens_batch] blocked cooldown active until ' + summary.cooldownUntil + '; stop calling Lens.');
      return EXIT.partial;
    }
  }

  const edge = path.resolve(opts.edge || defaultEdgePath());
  if (!fs.existsSync(edge)) {
    throw new KitError(EXIT.usage, 'browser executable not found; set QIMENG_RESEARCH_KIT_EDGE', summary);
  }
  fs.mkdirSync(cacheDir, { recursive: true });
  fs.mkdirSync(path.dirname(outPath), { recursive: true });

  const lockPath = path.join(cacheDir, 'lens', 'profile.lock');
  fs.mkdirSync(path.dirname(lockPath), { recursive: true });
  let lockHandle = null;
  try {
    lockHandle = fs.openSync(lockPath, 'wx');
  } catch {
    let stale = false;
    try {
      const owner = Number(fs.readFileSync(lockPath, 'utf8').trim());
      if (!Number.isInteger(owner) || owner <= 0) {
        stale = true;
      } else {
        try {
          process.kill(owner, 0);
        } catch (error) {
          stale = error && error.code === 'ESRCH';
        }
      }
    } catch {
      stale = true;
    }
    if (stale) {
      try { fs.rmSync(lockPath, { force: true }); } catch { /* ignore */ }
      try { lockHandle = fs.openSync(lockPath, 'wx'); } catch { lockHandle = null; }
    }
    if (lockHandle === null) {
      throw new KitError(EXIT.usage,
        'another lens_batch run holds the profile lock; wait for it or use --profile', summary);
    }
  }
  fs.writeSync(lockHandle, String(process.pid));
  activeLockPath = lockPath;

  if (opts.resetProfile) {
    fs.rmSync(profilePath, { recursive: true, force: true });
  }
  fs.mkdirSync(profilePath, { recursive: true, mode: 0o700 });

  const port = await pickFreePort();
  const args = [
    '--headless=new',
    '--disable-gpu',
    '--no-sandbox',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-sync',
    '--disable-extensions',
    '--disable-background-networking',
    '--disable-component-update',
    '--remote-allow-origins=*',
    '--window-size=1280,1600',
    '--force-device-scale-factor=1',
    '--user-data-dir=' + profilePath,
    '--remote-debugging-port=' + port,
    'about:blank',
  ];
  const browserState = { failed: false, error: null };
  const child = spawn(edge, args, { stdio: 'ignore', windowsHide: true });
  activeBrowser = child;
  child.once('error', () => { browserState.failed = true; browserState.error = 'spawn_error'; });
  child.once('exit', (code) => {
    if (!stopping) { browserState.failed = true; browserState.error = 'exit_' + (code === null ? 'null' : code); }
  });

  let cdp = null;
  let page = null;
  const started = Date.now();
  try {
    await waitForCdp(port, 45000, browserState);
    page = await newPage(port);
    cdp = await Cdp.connect(page.webSocketDebuggerUrl);
    await cdp.send('Page.enable', {}, 5000);
    await cdp.send('Runtime.enable', {}, 5000);
    await cdp.send('DOM.enable', {}, 5000);

    for (let index = 0; index < pending.length; index += 1) {
      const job = pending[index];
      const filePath = path.resolve(job.file);
      let info = null;
      try {
        const stat = fs.statSync(filePath);
        if (!stat.isFile()) throw new LensError('skipped', 'NOT_A_FILE');
        if (stat.size <= 0) throw new LensError('skipped', 'EMPTY_FILE');
        if (stat.size > MAX_FILE_BYTES) throw new LensError('skipped', 'TOO_LARGE');
        const extension = path.extname(filePath).toLowerCase();
        if (!ALLOWED_EXT.has(extension)) throw new LensError('skipped', 'UNSUPPORTED_EXTENSION');
        const head = Buffer.alloc(Math.min(stat.size, MAX_HEAD_BYTES));
        const fd = fs.openSync(filePath, 'r');
        try { fs.readSync(fd, head, 0, head.length, 0); } finally { fs.closeSync(fd); }
        info = readImageInfo(head);
        if (!info || !['jpeg', 'png', 'webp'].includes(info.format)) {
          throw new LensError('skipped', 'UNSUPPORTED_CONTENT');
        }
      } catch (error) {
        if (error instanceof LensError) {
          const skipped = errorRecord(job, error);
          skipped.status = 'skipped';
          skipped.errorCode = error.code;
          appendResult(outPath, skipped);
          summary.skipped += 1;
          summary.processed += 1;
          if (opts.verbose) console.error('[lens_batch] skip ' + job.id + ' ' + error.code);
          continue;
        }
        const failed = errorRecord(job, new LensError('skipped', 'FILE_UNREADABLE'));
        failed.status = 'skipped';
        failed.errorCode = 'FILE_UNREADABLE';
        appendResult(outPath, failed);
        summary.skipped += 1;
        summary.processed += 1;
        continue;
      }

      if (info.width && info.height && opts.maxDim > 0 && Math.max(info.width, info.height) > opts.maxDim) {
        console.error('[lens_batch] warn ' + job.id + ': ' + info.width + 'x' + info.height +
          ' exceeds ' + opts.maxDim + 'px; pre-scale to <=1600px WebP for speed/accuracy');
      }
      if (info.format !== 'webp') {
        console.error('[lens_batch] warn ' + job.id + ': ' + info.format + ' input; WebP <=1600px keeps uploads small');
      }

      const sha = await hashFile(filePath);
      if (!opts.noCache) {
        const cached = loadCached(cacheDir, sha, opts.maxAgeHours);
        if (cached) {
          const hit = baseRecord(job);
          hit.frameSha256 = sha;
          if (cached.topTitles) hit.topTitles = cached.topTitles;
          if (cached.topDomains) hit.topDomains = cached.topDomains;
          hit.citesCount = Number(cached.citesCount) || 0;
          hit.resultUrl = cached.resultUrl || '';
          hit.cacheHit = true;
          appendResult(outPath, hit);
          summary.cacheHits += 1;
          summary.processed += 1;
          if (opts.verbose) console.error('[lens_batch] cache ' + job.id + ' ' + sha.slice(0, 12));
          if (index + 1 < pending.length) await sleepFor(opts.minIntervalMs);
          continue;
        }
      }

      if (index > 0 && opts.minIntervalMs > 0) await sleepFor(opts.minIntervalMs);
      let outcome = null;
      try {
        outcome = await runJob(cdp, { id: job.id, file: filePath }, opts);
      } catch (error) {
        const failed = errorRecord(job, error);
        failed.frameSha256 = sha;
        appendResult(outPath, failed);
        summary.processed += 1;
        if (failed.status === 'blocked') {
          summary.blocked += 1;
          summary.status = 'blocked';
          writeCooldown(cacheDir, failed.blockedSignals);
          summary.blockedSignals = failed.blockedSignals || [];
          summary.cooldownUntil = new Date(Date.now() + BLOCK_COOLDOWN_MS).toISOString();
          summary.remaining = pending.length - summary.processed;
          console.error('[lens_batch] blocked by Google risk control; stopping all Lens calls for 60 minutes.');
          break;
        }
        summary.failed += 1;
        summary.status = 'partial';
        if (opts.verbose) console.error('[lens_batch] fail ' + job.id + ' ' + failed.errorCode);
        continue;
      }

      const ok = baseRecord(job);
      ok.status = 'ok';
      ok.topTitles = outcome.data.topTitles;
      ok.topDomains = outcome.data.topDomains;
      ok.citesCount = outcome.data.citesCount;
      ok.resultUrl = outcome.data.resultUrl;
      ok.frameSha256 = sha;
      ok.attempts = outcome.attempts;
      ok.elapsedMs = outcome.elapsedMs;
      appendResult(outPath, ok);
      summary.ok += 1;
      summary.processed += 1;
      if (!opts.noCache) {
        saveCached(cacheDir, sha, {
          status: 'ok',
          topTitles: ok.topTitles,
          topDomains: ok.topDomains,
          citesCount: ok.citesCount,
          resultUrl: ok.resultUrl,
          fetchedAt: ok.ts,
        });
      }
      if (opts.verbose) {
        console.error('[lens_batch] ok ' + job.id + ' titles=' + ok.topTitles.length +
          ' domains=' + ok.topDomains.join(',') + ' cites=' + ok.citesCount + ' ' + outcome.elapsedMs + 'ms');
      }
    }
  } finally {
    stopping = true;
    if (cdp) { try { cdp.close(); } catch { /* ignore */ } }
    if (page && port) await closePage(port, page.id);
    killBrowser(child, child.pid);
    activeBrowser = null;
    try { if (lockHandle !== null) fs.closeSync(lockHandle); } catch { /* ignore */ }
    try { fs.rmSync(lockPath, { force: true }); } catch { /* ignore */ }
    activeLockPath = null;
  }

  summary.durationMs = Date.now() - started;
  summary.remaining = Math.max(0, summary.pending - summary.processed);
  if (summary.blocked > 0) summary.status = 'blocked';
  else if (summary.failed > 0 || summary.remaining > 0) summary.status = 'partial';
  console.log(JSON.stringify(summary, null, opts.pretty ? 2 : 0));
  if (summary.blocked > 0 || summary.failed > 0 || summary.remaining > 0) return EXIT.partial;
  return EXIT.ok;
}

async function main() {
  const { opts, errors } = parseArgs(process.argv.slice(2));
  if (opts.help) {
    process.stdout.write(USAGE);
    return EXIT.ok;
  }
  if (errors.length) {
    for (const error of errors) console.error('[lens_batch] ' + error);
    process.stderr.write('\n' + USAGE);
    return EXIT.usage;
  }
  if (!opts.jobs) {
    console.error('[lens_batch] --jobs is required');
    process.stderr.write('\n' + USAGE);
    return EXIT.usage;
  }
  ensureNodeVersion();
  try {
    return await run(opts);
  } catch (error) {
    if (error instanceof KitError) {
      console.error('[lens_batch] ' + error.message);
      if (error.summary) console.log(JSON.stringify({ ...error.summary, status: 'error', error: error.message }));
      return error.exitCode;
    }
    console.error('[lens_batch] unexpected error: ' + String(error && error.message ? error.message : error).slice(0, 300));
    console.log(JSON.stringify({ tool: TOOL, status: 'error', error: 'unexpected' }));
    return EXIT.usage;
  }
}

process.on('SIGINT', () => {
  stopping = true;
  if (activeBrowser) killBrowser(activeBrowser, activeBrowser.pid);
  if (activeLockPath) { try { fs.rmSync(activeLockPath, { force: true }); } catch { /* ignore */ } }
  process.exit(130);
});
process.on('SIGTERM', () => {
  stopping = true;
  if (activeBrowser) killBrowser(activeBrowser, activeBrowser.pid);
  if (activeLockPath) { try { fs.rmSync(activeLockPath, { force: true }); } catch { /* ignore */ } }
  process.exit(143);
});

main().then((code) => {
  process.exitCode = code;
});