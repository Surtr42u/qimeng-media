// Qimeng research sidecar: loopback HTTP + headless Edge/CDP for Google Lens and
// Google text search. Node standard library only; no npm dependencies.
//
// Security model: listen on 127.0.0.1 only, require a bearer token, keep path
// inputs inside a configured root, never log/echo the token or cookies, and
// never try to bypass captchas or login walls. See README.md for the contract.
import http from 'node:http';
import net from 'node:net';
import { spawn, spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const VERSION = '1.0.0';
const HOST = '127.0.0.1';
const MAX_FILE_BYTES = 20 * 1024 * 1024;
const MAX_JSON_BYTES = 64 * 1024;
const MAX_MULTIPART_BYTES = MAX_FILE_BYTES + 1024 * 1024;
const ALLOWED_EXT = new Set(['.jpg', '.jpeg', '.png', '.webp']);
const STATUS_BY_CODE = {
  INPUT_INVALID: 400,
  UNAUTHORIZED: 401,
  NOT_FOUND: 404,
  UPLOAD_REJECTED: 422,
  BUSY: 429,
  NO_RESULT: 502,
  BLOCKED_BOT: 503,
  TIMEOUT: 504,
};
const PUBLIC_STATUS = { BLOCKED_BOT: 'blocked_bot', TIMEOUT: 'timeout' };
const RETRY_AFTER = { BUSY: '2', BLOCKED_BOT: '600' };

function envInt(name, fallback, min, max) {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const value = Number(raw);
  if (!Number.isInteger(value) || value < min || value > max) {
    console.error('[research-sidecar] invalid ' + name + '; expected an integer between ' + min + ' and ' + max + '.');
    process.exit(1);
  }
  return value;
}

const PORT = envInt('QIMENG_RESEARCH_SIDECAR_PORT', 18421, 1, 65535);
const CDP_PORT = envInt('QIMENG_RESEARCH_SIDECAR_CDP_PORT', 0, 0, 65535);
const TIMEOUT_MS = envInt('QIMENG_RESEARCH_SIDECAR_TIMEOUT_MS', 90000, 1000, 300000);
const ATTACH_TIMEOUT_MS = envInt('QIMENG_RESEARCH_SIDECAR_ATTACH_TIMEOUT_MS', 20000, 1000, 60000);
const TOKEN = (process.env.QIMENG_RESEARCH_SIDECAR_TOKEN || '').trim();
const ALLOWED_ROOT_INPUT = (process.env.QIMENG_RESEARCH_SIDECAR_ALLOWED_ROOT || '').trim();
const LOG_FILE = (process.env.QIMENG_RESEARCH_SIDECAR_LOG || '').trim()
  ? path.resolve((process.env.QIMENG_RESEARCH_SIDECAR_LOG || '').trim())
  : '';
const DATA_DIR = path.resolve(
  (process.env.QIMENG_RESEARCH_SIDECAR_DATA_DIR || '').trim() || path.join(os.tmpdir(), 'qimeng-research-sidecar'),
);
const SESSION_DIR = path.resolve(
  (process.env.QIMENG_RESEARCH_SIDECAR_SESSION_DIR || '').trim() || path.join(DATA_DIR, 'session'),
);
const EDGE = (process.env.QIMENG_RESEARCH_SIDECAR_EDGE || '').trim() || defaultEdgePath();
const HOME_DIR = os.homedir();

if (!TOKEN) {
  console.error(
    '[research-sidecar] QIMENG_RESEARCH_SIDECAR_TOKEN is required. ' +
      'Set it to a long random secret in the service environment before starting; ' +
      'refusing to start without authentication. The token is never printed.',
  );
  process.exit(1);
}

let allowedRoot = null;
if (ALLOWED_ROOT_INPUT) {
  try {
    const real = fs.realpathSync(path.resolve(ALLOWED_ROOT_INPUT));
    if (!fs.statSync(real).isDirectory()) throw new Error('not a directory');
    allowedRoot = real;
  } catch {
    console.error(
      '[research-sidecar] QIMENG_RESEARCH_SIDECAR_ALLOWED_ROOT must be an existing directory; refusing to start.',
    );
    process.exit(1);
  }
}

try {
  fs.mkdirSync(SESSION_DIR, { recursive: true, mode: 0o700 });
} catch {
  console.error('[research-sidecar] unable to create the browser session directory; refusing to start.');
  process.exit(1);
}
if (LOG_FILE) {
  try {
    fs.mkdirSync(path.dirname(LOG_FILE), { recursive: true });
  } catch {
    // Logging to a file is optional; stdout logging continues.
  }
}

function defaultEdgePath() {
  const candidates =
    process.platform === 'win32'
      ? [
          'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
          'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
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

function redactMessage(value) {
  let text = String(value === undefined || value === null ? '' : value);
  if (TOKEN && TOKEN.length >= 8) text = text.split(TOKEN).join('[redacted]');
  if (HOME_DIR) text = text.split(HOME_DIR).join('<home>');
  if (DATA_DIR) text = text.split(DATA_DIR).join('<data>');
  if (allowedRoot) text = text.split(allowedRoot).join('<root>');
  text = text.split(os.tmpdir()).join('<tmp>');
  return text.replace(/\s+/g, ' ').trim().slice(0, 500);
}

function log(event, fields) {
  const record = { ts: new Date().toISOString(), event, ...(fields || {}) };
  const line = JSON.stringify(record);
  try {
    console.log(line);
  } catch {
    // stdout may be closed during shutdown
  }
  if (LOG_FILE) {
    try {
      fs.appendFileSync(LOG_FILE, line + '\n');
    } catch {
      // Logging failures must not break research requests.
    }
  }
}

class SidecarError extends Error {
  constructor(code, message, extra) {
    super(message);
    this.name = 'SidecarError';
    this.code = code;
    this.extra = extra;
  }
}

function fail(code, message, extra) {
  return new SidecarError(code, message, extra);
}

function sleepFor(ms) {
  return new Promise((resolve) => setTimeout(resolve, Math.max(0, ms)));
}

function assertDeadline(deadline, message) {
  if (Date.now() >= deadline) throw fail('TIMEOUT', message || 'request deadline exceeded');
}

async function sleepUntil(deadline, ms) {
  const end = Math.min(Date.now() + Math.max(0, ms), deadline);
  if (end <= Date.now()) throw fail('TIMEOUT', 'request deadline exceeded');
  await sleepFor(end - Date.now());
}

function sha256(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex');
}

function sha256Text(text) {
  return crypto.createHash('sha256').update(String(text || ''), 'utf8').digest('hex');
}

function isGoogleHost(hostname) {
  return (
    /(^|\.)google\.[a-z.]+$/i.test(hostname) ||
    /(^|\.)gstatic\.com$/i.test(hostname) ||
    /(^|\.)googleusercontent\.com$/i.test(hostname) ||
    hostname === 'accounts.google.com' ||
    hostname === 'support.google.com' ||
    hostname === 'policies.google.com' ||
    hostname === 'consent.google.com'
  );
}

const SESSION_PARAMS = new Set([
  'gsessionid',
  'lsessionid',
  'vsrid',
  'authuser',
  'qsubts',
  'biw',
  'bih',
  'hl',
  'gl',
  'sclient',
  'uule',
  'aqs',
  'oq',
  'gws_rd',
  'ei',
  'ved',
  'usg',
  'fbs',
  'adurl',
  'sa',
  'source',
  'sxsrf',
  'dpr',
  'safe',
  'nfpr',
  'filter',
  'pws',
  'ie',
  'oe',
  'cad',
  'cd',
  'rct',
  'j',
  'esrc',
  'sig',
  'sig2',
  'client',
  'channel',
  'm',
  'cvid',
  'form',
  'sp',
  'gs_l',
  'gs_ssp',
  'ictx',
  'bvm',
]);
const GOOGLE_KEEP = new Set(['udm', 'lns_mode', 'lns_surface', 'q', 'tbm', 'start', 'num']);

function sanitizeUrl(raw) {
  if (!raw) return '';
  let url;
  try {
    url = new URL(String(raw));
  } catch {
    return '';
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return '';
  if (url.username || url.password) {
    url.username = '';
    url.password = '';
  }
  if (isGoogleHost(url.hostname)) {
    const kept = new URLSearchParams();
    for (const key of GOOGLE_KEEP) {
      if (url.searchParams.has(key)) kept.set(key, url.searchParams.get(key));
    }
    url.search = kept.toString();
    url.hash = '';
  } else {
    for (const key of [...url.searchParams.keys()]) {
      if (SESSION_PARAMS.has(key.toLowerCase())) url.searchParams.delete(key);
    }
    if (url.hash.indexOf('#:~:text=') === 0) url.hash = '';
  }
  return url.toString();
}

function domainOf(raw) {
  try {
    return new URL(String(raw)).hostname.toLowerCase().replace(/^www\./, '').replace(/\.$/, '');
  } catch {
    return '';
  }
}

function normalizeExternalUrl(raw) {
  if (!raw) return null;
  let url;
  try {
    url = new URL(String(raw));
  } catch {
    return null;
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
  if (isGoogleHost(url.hostname)) {
    if (url.pathname !== '/url') return null;
    const target = url.searchParams.get('q') || url.searchParams.get('url') || '';
    if (!target) return null;
    return normalizeExternalUrl(target);
  }
  const clean = sanitizeUrl(url.href);
  if (!clean) return null;
  const domain = domainOf(clean);
  if (!domain) return null;
  return { url: clean, domain };
}

function isGoogleGotoUrl(raw) {
  try {
    const url = new URL(String(raw));
    return isGoogleHost(url.hostname) && url.pathname === '/goto';
  } catch {
    return false;
  }
}

const HOST_RE =
  /(?:https?:\/\/)?((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,24})(?=$|[\s/\u203a|,;)\]\u3002\uff0c])/gi;
const BAD_HOST_SUFFIX = /\.(jpg|jpeg|png|webp|gif|mp4|js|css|html|php|svg)$/i;
const OK_TLDS = new Set(
  (
    'com net org edu gov io co jp cn us ca uk de fr ru cc re me tv info biz xyz app dev art blog shop site online pro ' +
    'vn id kr tw hk sg au br mx es it nl se no fi pl ch at be cz gr pt ro sk ua tr il in th my ph pe ar cl za ie nz ' +
    'dk hu bg hr lt lv ee si lu is mt cy ly to gg ai sh fm moe wiki top club live news store fun space website tech ' +
    'design photography pics video network solutions agency'
  ).split(' '),
);
const UI_LINE =
  /^(跳过主要内容|无障碍功能帮助|翻译|登录|AI 模式|全部|完全匹配|外观匹配|反馈|搜索结果|AI 概览|全部显示|翻译此页|页脚链接|帮助|发送反馈|隐私权|条款|查看更多结果|ウェブ検索結果|Images|Shopping|Videos|News|Maps|Books|Flights|Finance|Search|Sign in|Settings|Privacy|Terms)$/;

function validTld(host) {
  return OK_TLDS.has(host.split('.').pop());
}

function normalizeHost(host) {
  return host.toLowerCase().replace(/^www\./, '').replace(/\.$/, '');
}

function textHosts(line) {
  const out = [];
  HOST_RE.lastIndex = 0;
  let match;
  while ((match = HOST_RE.exec(line)) !== null) {
    const host = normalizeHost(match[1]);
    if (!host || isGoogleHost(host) || BAD_HOST_SUFFIX.test(host) || !validTld(host)) continue;
    out.push(host);
  }
  return [...new Set(out)];
}

function parseTextItems(text) {
  const lines = String(text || '')
    .split(/\r?\n/)
    .map((line) => line.trim());
  const items = [];
  const seen = new Set();
  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i];
    if (!line) continue;
    for (const host of textHosts(line)) {
      if (seen.has(host)) continue;
      seen.add(host);
      let title = '';
      if (!/^https?:\/\//.test(line)) {
        for (let j = i + 1; j < Math.min(i + 4, lines.length); j += 1) {
          const candidate = lines[j];
          if (!candidate) continue;
          if (textHosts(candidate).length) break;
          if (UI_LINE.test(candidate)) continue;
          if (candidate.length < 6 || candidate.length > 220) continue;
          if (/^\d{4}年\d{1,2}月\d{1,2}日/.test(candidate)) continue;
          title = candidate;
          break;
        }
      }
      items.push({ domain: host, title });
      if (items.length >= 40) return items;
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
    if (out.length >= 20) break;
  }
  return out;
}

function buildTopDomains(items) {
  const out = [];
  for (const item of items) {
    if (!out.includes(item.domain)) out.push(item.domain);
    if (out.length >= 30) break;
  }
  return out;
}

function buildLensLinks(rawLinks) {
  const out = [];
  const seen = new Set();
  for (const raw of Array.isArray(rawLinks) ? rawLinks : []) {
    const normalized = normalizeExternalUrl(raw && raw.href);
    if (!normalized || seen.has(normalized.url)) continue;
    seen.add(normalized.url);
    out.push({
      url: normalized.url,
      domain: normalized.domain,
      title: String((raw && raw.text) || '').trim().replace(/\s+/g, ' ').slice(0, 200),
    });
    if (out.length >= 50) break;
  }
  return out;
}

function buildLensCites(rawCites) {
  const out = [];
  const seen = new Set();
  for (const raw of Array.isArray(rawCites) ? rawCites : []) {
    const normalized = normalizeExternalUrl(raw && raw.href);
    if (!normalized || seen.has(normalized.url)) continue;
    seen.add(normalized.url);
    const item = { url: normalized.url, domain: normalized.domain };
    const title = String((raw && raw.title) || '').trim().replace(/\s+/g, ' ').slice(0, 300);
    if (title) item.title = title;
    out.push(item);
    if (out.length >= 80) break;
  }
  return out;
}

async function resolveGoogleRedirect(raw, deadline, cookieHeader) {
  let url;
  try {
    url = new URL(String(raw));
  } catch {
    return null;
  }
  if (!isGoogleGotoUrl(url.href)) return null;
  const remainingMs = deadline - Date.now();
  if (remainingMs <= 1500) return null;
  const timeoutMs = Math.min(4000, remainingMs - 500);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), Math.max(1000, timeoutMs));
  const headers = {};
  if (cookieHeader) headers.cookie = cookieHeader;
  try {
    const response = await fetch(url.href, { method: 'GET', redirect: 'manual', headers, signal: controller.signal });
    const location = response.headers.get('location') || '';
    if (response.body) {
      try {
        await response.body.cancel();
      } catch {
        // ignore
      }
    }
    if (response.status === 429) {
      throw fail('BLOCKED_BOT', 'Google result redirect returned HTTP 429', { signals: ['http_429'] });
    }
    if (response.status >= 300 && response.status < 400 && location) {
      const target = new URL(location, url.href);
      if (isGoogleHost(target.hostname)) {
        if (target.pathname === '/sorry' || target.pathname === '/sorry/' || target.pathname.startsWith('/sorry/')) {
          throw fail('BLOCKED_BOT', 'Google sorry page while resolving a result link', { signals: ['sorry_url'] });
        }
        return null;
      }
      if (target.protocol !== 'http:' && target.protocol !== 'https:') return null;
      return target.href;
    }
    return null;
  } catch (error) {
    if (error instanceof SidecarError) throw error;
    log('goto_resolve_error', {
      name: error && error.name ? String(error.name).slice(0, 40) : 'error',
      message: redactMessage(error && error.message ? error.message : error),
      cause: error && error.cause && error.cause.code ? String(error.cause.code).slice(0, 60) : null,
    });
    return null;
  } finally {
    clearTimeout(timer);
  }
}

async function buildSearchItems(rawItems, deadline, cookieHeader) {
  const raw = Array.isArray(rawItems) ? rawItems.slice(0, 24) : [];
  const resolved = new Array(raw.length).fill(null);
  let cursor = 0;
  let blocked = null;
  const workerCount = Math.min(1, raw.length);
  const workers = [];
  for (let workerIndex = 0; workerIndex < workerCount; workerIndex += 1) {
    workers.push(
      (async () => {
        for (;;) {
          const index = cursor;
          cursor += 1;
          if (index >= raw.length || blocked) return;
          if (Date.now() >= deadline - 1000) return;
          const entry = raw[index];
          if (!entry || !entry.href) continue;
          if (/^(read more|阅读更多|さらに表示|もっと見る)$/i.test(String(entry.title || '').trim())) continue;
          try {
            let href = entry.href;
            if (isGoogleGotoUrl(href)) href = await resolveGoogleRedirect(href, deadline, cookieHeader);
            const normalized = normalizeExternalUrl(href);
            if (!normalized) continue;
            const title = String(entry.title || '')
              .trim()
              .replace(/\s+/g, ' ')
              .slice(0, 300);
            if (!title) continue;
            resolved[index] = { title, url: normalized.url, domain: normalized.domain };
          } catch (error) {
            if (error instanceof SidecarError && error.code === 'BLOCKED_BOT') {
              blocked = error;
              return;
            }
          }
        }
      })(),
    );
  }
  await Promise.all(workers);
  if (blocked) throw blocked;
  const out = [];
  const seen = new Set();
  for (const item of resolved) {
    if (!item || seen.has(item.url)) continue;
    seen.add(item.url);
    out.push(item);
    if (out.length >= 20) break;
  }
  return out;
}

function riskSignals(text, url, title, http429) {
  const out = [];
  const sample = String(text || '').slice(0, 8000);
  if (http429 > 0) out.push('http_429');
  if (/unusual traffic/i.test(sample)) out.push('unusual_traffic_text');
  if (/not a robot|recaptcha|verify you are human|automated queries/i.test(sample)) out.push('captcha_text');
  if (/验证码|异常流量|人机验证|自动查询|机器人/.test(sample)) out.push('captcha_text_zh');
  if (/\/sorry\//.test(url || '')) out.push('sorry_url');
  if (/^Sorry\.\.\./.test(title || '')) out.push('sorry_title');
  if (/too many requests/i.test(sample)) out.push('too_many_requests_text');
  return [...new Set(out)];
}

function isConsentUrl(raw) {
  try {
    const host = new URL(String(raw)).hostname.toLowerCase();
    return host === 'consent.google.com' || host === 'consent.youtube.com';
  } catch {
    return false;
  }
}

function isLoginUrl(raw) {
  try {
    const url = new URL(String(raw));
    if (!/(^|\.)accounts\.google\.com$/i.test(url.hostname)) return false;
    return /\/(v3\/signin|ServiceLogin|signin)/i.test(url.pathname);
  } catch {
    return false;
  }
}

function isGoogleSearchUrl(raw) {
  try {
    const url = new URL(String(raw));
    return isGoogleHost(url.hostname) && url.pathname.indexOf('/search') === 0;
  } catch {
    return false;
  }
}

function pageAttachProbe() {
  var el = document.querySelector('input[name="encoded_image"]');
  var files = [];
  var inputs = document.querySelectorAll('input[type=file]');
  for (var i = 0; i < inputs.length; i += 1) {
    files.push({ name: (inputs[i].name || '').slice(0, 60), accept: (inputs[i].accept || '').slice(0, 80) });
    if (files.length >= 5) break;
  }
  return { found: Boolean(el), count: files.length, inputs: files };
}

function pageConsentClick() {
  var wanted = ['accept all', 'accept all cookies', '接受全部', '全部接受', '同意', 'i agree', 'agree to all'];
  var nodes = document.querySelectorAll('button, [role="button"], input[type="submit"], a');
  for (var i = 0; i < nodes.length; i += 1) {
    var el = nodes[i];
    var text = ((el.innerText || el.value || '') + '').replace(/\s+/g, ' ').trim().toLowerCase();
    if (!text || text.length > 40) continue;
    for (var j = 0; j < wanted.length; j += 1) {
      if (text === wanted[j] || text.indexOf(wanted[j]) >= 0) {
        try {
          el.click();
          return true;
        } catch (e) {}
      }
    }
  }
  return false;
}

function pageLensExtract() {
  function isGoogleHost(h) {
    return (
      /(^|\.)google\.[a-z.]+$/i.test(h) ||
      /(^|\.)gstatic\.com$/i.test(h) ||
      /(^|\.)googleusercontent\.com$/i.test(h) ||
      h === 'accounts.google.com' ||
      h === 'support.google.com' ||
      h === 'policies.google.com' ||
      h === 'consent.google.com'
    );
  }
  function unwrap(raw) {
    try {
      var href = raw || '';
      var url = new URL(href, location.href);
      if (isGoogleHost(url.hostname) && url.pathname === '/url') {
        href = url.searchParams.get('q') || url.searchParams.get('url') || '';
        if (!href) return '';
        url = new URL(href, location.href);
      }
      if (url.protocol !== 'http:' && url.protocol !== 'https:') return '';
      if (isGoogleHost(url.hostname)) return '';
      return url.href;
    } catch (e) {
      return '';
    }
  }
  var links = [];
  var seen = Object.create(null);
  var anchors = document.querySelectorAll('a');
  for (var i = 0; i < anchors.length; i += 1) {
    var href = unwrap(anchors[i].href);
    if (!href || seen[href]) continue;
    seen[href] = true;
    links.push({ href: href, text: (anchors[i].innerText || '').trim().slice(0, 300) });
    if (links.length >= 300) break;
  }
  var cites = [];
  var citeSeen = Object.create(null);
  var citeNodes = document.querySelectorAll('cite');
  for (var k = 0; k < citeNodes.length; k += 1) {
    var cite = citeNodes[k];
    var text = (cite.innerText || '').trim().slice(0, 300);
    if (!text) continue;
    var anchor = cite.closest('a') || cite.querySelector('a');
    if (!anchor) continue;
    var citeHref = unwrap(anchor.href);
    if (!citeHref || citeSeen[citeHref]) continue;
    citeSeen[citeHref] = true;
    cites.push({ title: text, href: citeHref });
    if (cites.length >= 80) break;
  }
  return { links: links, cites: cites };
}

function pageSearchExtract() {
  function isGoogleHost(h) {
    return (
      /(^|\.)google\.[a-z.]+$/i.test(h) ||
      /(^|\.)gstatic\.com$/i.test(h) ||
      /(^|\.)googleusercontent\.com$/i.test(h) ||
      h === 'accounts.google.com' ||
      h === 'support.google.com' ||
      h === 'policies.google.com' ||
      h === 'consent.google.com'
    );
  }
  function unwrap(raw) {
    try {
      var href = raw || '';
      var url = new URL(href, location.href);
      if (isGoogleHost(url.hostname) && url.pathname === '/url') {
        href = url.searchParams.get('q') || url.searchParams.get('url') || '';
        if (!href) return '';
        url = new URL(href, location.href);
      }
      if (url.protocol !== 'http:' && url.protocol !== 'https:') return '';
      if (isGoogleHost(url.hostname)) {
        if (url.pathname === '/goto') return url.href;
        return '';
      }
      return url.href;
    } catch (e) {
      return '';
    }
  }
  var scoped = document.querySelectorAll('#search a, #rso a');
  var anchors = scoped.length ? scoped : document.querySelectorAll('a');
  var items = [];
  var seen = Object.create(null);
  for (var i = 0; i < anchors.length; i += 1) {
    var anchor = anchors[i];
    var href = unwrap(anchor.href);
    if (!href || seen[href]) continue;
    var h3 = anchor.querySelector('h3');
    var title = ((h3 && h3.innerText) || anchor.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 300);
    if (!title) continue;
    seen[href] = true;
    items.push({ title: title, href: href });
    if (items.length >= 60) break;
  }
  return { items: items, hasResults: Boolean(document.querySelector('#search, #rso')) };
}

const ATTACH_PROBE_EXPR = '(' + pageAttachProbe.toString() + ')()';
const CONSENT_CLICK_EXPR = '(' + pageConsentClick.toString() + ')()';
const LENS_EXTRACT_EXPR = '(' + pageLensExtract.toString() + ')()';
const SEARCH_EXTRACT_EXPR = '(' + pageSearchExtract.toString() + ')()';

class CdpSession {
  constructor(ws) {
    this.ws = ws;
    this.id = 0;
    this.pending = new Map();
    this.listeners = new Map();
    this.closed = false;
    ws.addEventListener('message', (event) => this.onMessage(event));
    ws.addEventListener('close', () => {
      this.closed = true;
      this.failPending('cdp socket closed');
    });
    ws.addEventListener('error', () => {
      this.closed = true;
      this.failPending('cdp socket error');
    });
  }

  static async connect(url) {
    if (!/^ws:\/\/(127\.0\.0\.1|localhost|\[::1\]):\d+\//.test(String(url))) {
      throw fail('NO_RESULT', 'unexpected browser debugging URL');
    }
    const ws = new WebSocket(url);
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        try {
          ws.close();
        } catch {
          // ignore
        }
        reject(fail('NO_RESULT', 'browser debugging socket did not open'));
      }, 10000);
      ws.addEventListener(
        'open',
        () => {
          clearTimeout(timer);
          resolve();
        },
        { once: true },
      );
      ws.addEventListener(
        'error',
        () => {
          clearTimeout(timer);
          reject(fail('NO_RESULT', 'browser debugging socket error'));
        },
        { once: true },
      );
    });
    return new CdpSession(ws);
  }

  failPending(message) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer);
      pending.reject(fail('NO_RESULT', message));
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
      if (message.error) {
        pending.reject(
          fail('NO_RESULT', 'browser command failed: ' + String(message.error.message || 'error').slice(0, 200)),
        );
      } else {
        pending.resolve(message.result || {});
      }
      return;
    }
    if (message.method) {
      for (const listener of this.listeners.get(message.method) || []) {
        try {
          listener(message.params || {});
        } catch {
          // Page event listeners must not break the CDP session.
        }
      }
    }
  }

  send(method, params, timeoutMs) {
    if (this.closed || this.ws.readyState !== WebSocket.OPEN) {
      return Promise.reject(fail('NO_RESULT', 'browser debugging socket is not open'));
    }
    const id = (this.id += 1);
    const timeout = Math.max(1, Number(timeoutMs) || 30000);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (this.pending.delete(id)) reject(fail('CDP_TIMEOUT', 'browser command timed out: ' + method));
      }, timeout);
      this.pending.set(id, { resolve, reject, timer });
      try {
        this.ws.send(JSON.stringify({ id, method, params: params || {} }));
      } catch {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(fail('NO_RESULT', 'browser command could not be sent'));
      }
    });
  }

  on(method, listener) {
    const listeners = this.listeners.get(method) || [];
    listeners.push(listener);
    this.listeners.set(method, listeners);
  }

  close() {
    this.closed = true;
    this.failPending('browser debugging session closed');
    try {
      this.ws.close();
    } catch {
      // ignore
    }
  }
}

const browser = {
  child: null,
  pid: null,
  cdpPort: null,
  state: 'starting',
  error: null,
  startedAt: null,
};
let inflight = 0;
let shuttingDown = false;
const counters = {
  requests_total: 0,
  ok_total: 0,
  error_total: 0,
  busy_total: 0,
  error_counts: {},
};
const startedAt = new Date().toISOString();

function isBrowserReady() {
  return !shuttingDown && browser.state === 'ready' && Boolean(browser.pid) && Boolean(browser.cdpPort);
}

function pickFreePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once('error', reject);
    server.listen(0, HOST, () => {
      const port = server.address().port;
      server.close(() => resolve(port));
    });
  });
}

async function newPageOnPort(port) {
  const url = 'http://127.0.0.1:' + port + '/json/new?about:blank';
  let response = null;
  try {
    response = await fetch(url, { method: 'PUT', signal: AbortSignal.timeout(5000) });
  } catch {
    response = null;
  }
  if (!response || !response.ok) {
    try {
      response = await fetch(url, { method: 'GET', signal: AbortSignal.timeout(5000) });
    } catch {
      response = null;
    }
  }
  if (!response || !response.ok) throw fail('NO_RESULT', 'browser could not create a page');
  let target;
  try {
    target = await response.json();
  } catch {
    throw fail('NO_RESULT', 'browser returned an invalid page descriptor');
  }
  if (!target || !target.id || !target.webSocketDebuggerUrl) {
    throw fail('NO_RESULT', 'browser returned an incomplete page descriptor');
  }
  return target;
}

async function closeTarget(port, id) {
  if (!port || !id) return;
  try {
    await fetch('http://127.0.0.1:' + port + '/json/close/' + encodeURIComponent(id), {
      signal: AbortSignal.timeout(3000),
    });
  } catch {
    // Best effort; the browser will close the target when it exits.
  }
}

async function waitForCdp(port, timeoutMs) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    if (browser.state === 'failed') throw new Error('browser process failed during startup');
    try {
      const response = await fetch('http://127.0.0.1:' + port + '/json/version', {
        signal: AbortSignal.timeout(1500),
      });
      if (response.ok) return await response.json();
    } catch {
      // not ready yet
    }
    await sleepFor(400);
  }
  throw new Error('browser CDP endpoint did not become ready');
}

async function verifyBrowser(port) {
  const target = await newPageOnPort(port);
  let cdp = null;
  try {
    cdp = await CdpSession.connect(target.webSocketDebuggerUrl);
    await cdp.send('Page.enable', {}, 5000);
    await cdp.send('Runtime.enable', {}, 5000);
    const result = await cdp.send('Runtime.evaluate', { expression: '1 + 1', returnByValue: true }, 5000);
    if (!result || !result.result || result.result.value !== 2) throw new Error('browser self-check failed');
  } finally {
    try {
      if (cdp) cdp.close();
    } catch {
      // ignore
    }
    await closeTarget(port, target.id);
  }
}

async function launchBrowser() {
  if (shuttingDown) return;
  browser.state = 'starting';
  browser.error = null;
  browser.startedAt = new Date().toISOString();
  const cdpPort = CDP_PORT > 0 ? CDP_PORT : await pickFreePort();
  browser.cdpPort = cdpPort;
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
    '--user-data-dir=' + SESSION_DIR,
    '--remote-debugging-port=' + cdpPort,
    'about:blank',
  ];
  log('browser_launch', { edge: path.basename(EDGE) || 'browser', cdp_port: cdpPort, headless: true });
  const browserEnv = { ...process.env };
  delete browserEnv.QIMENG_RESEARCH_SIDECAR_TOKEN;
  const child = spawn(EDGE, args, { stdio: 'ignore', windowsHide: true, env: browserEnv });
  browser.child = child;
  browser.pid = child.pid || null;
  child.once('error', (error) => {
    if (browser.child !== child) return;
    browser.state = 'failed';
    browser.error = 'browser process failed to start';
    log('browser_spawn_error', { code: error && error.code ? String(error.code).slice(0, 40) : 'spawn_error' });
  });
  child.once('exit', (code, signal) => {
    if (browser.child !== child) return;
    browser.child = null;
    browser.pid = null;
    if (shuttingDown) {
      browser.state = 'stopped';
      return;
    }
    browser.state = 'failed';
    browser.error =
      'browser exited unexpectedly (code=' + (code === null ? 'null' : code) + (signal ? ', signal=' + signal : '') + ')';
    log('browser_exit', { code: code === null ? null : code, signal: signal || null });
  });
  await waitForCdp(cdpPort, 45000);
  await verifyBrowser(cdpPort);
  if (shuttingDown) return;
  if (!browser.child) throw new Error('browser exited during startup');
  browser.state = 'ready';
  browser.error = null;
  log('browser_ready', { pid: browser.pid, cdp_port: cdpPort });
}

function killBrowser() {
  const child = browser.child;
  const pid = browser.pid;
  browser.child = null;
  browser.pid = null;
  browser.state = shuttingDown ? 'stopped' : browser.state;
  if (child) {
    try {
      child.kill();
    } catch {
      // ignore
    }
  }
  if (process.platform === 'win32' && pid) {
    try {
      spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true });
    } catch {
      // ignore
    }
  }
}

async function withPage(fn) {
  if (!isBrowserReady()) throw fail('NO_RESULT', 'browser is not ready; check GET /healthz');
  const port = browser.cdpPort;
  const target = await newPageOnPort(port);
  let cdp = null;
  try {
    cdp = await CdpSession.connect(target.webSocketDebuggerUrl);
    await cdp.send('Page.enable', {}, 5000);
    await cdp.send('Runtime.enable', {}, 5000);
    await cdp.send('DOM.enable', {}, 5000);
    await cdp.send('Network.enable', {}, 5000);
    return await fn(cdp);
  } catch (error) {
    if (error instanceof SidecarError) throw error;
    if (error && error.code === 'CDP_TIMEOUT') throw fail('TIMEOUT', 'browser command timed out');
    throw fail('NO_RESULT', 'browser page task failed: ' + redactMessage(error && error.message ? error.message : error));
  } finally {
    try {
      if (cdp) cdp.close();
    } catch {
      // ignore
    }
    await closeTarget(port, target.id);
  }
}

function cdpSend(cdp, method, params, deadline, cap) {
  if (Date.now() >= deadline) return Promise.reject(fail('TIMEOUT', 'request deadline exceeded'));
  const timeout = Math.max(1000, Math.min(Number(cap) || 30000, deadline - Date.now()));
  return cdp.send(method, params, timeout);
}

async function evaluate(cdp, expression, deadline, cap) {
  const result = await cdpSend(
    cdp,
    'Runtime.evaluate',
    { expression, returnByValue: true, awaitPromise: true, userGesture: true },
    deadline,
    cap,
  );
  if (result && result.exceptionDetails) throw fail('NO_RESULT', 'page script evaluation failed');
  return result && result.result ? result.result.value : undefined;
}

async function navigate(cdp, url, deadline) {
  const result = await cdpSend(cdp, 'Page.navigate', { url }, deadline, 30000);
  if (result && result.errorText) throw fail('NO_RESULT', 'page navigation failed: ' + String(result.errorText).slice(0, 80));
}

async function tryAcceptConsent(cdp, deadline) {
  try {
    return Boolean(await evaluate(cdp, CONSENT_CLICK_EXPR, deadline, 5000));
  } catch {
    return false;
  }
}

async function attachLensFile(cdp, uploadPath, deadline, requestId) {
  const attachStart = Date.now();
  const attachDeadline = Math.min(deadline, attachStart + ATTACH_TIMEOUT_MS);
  let lastProbe = null;
  for (let attempt = 1; attempt <= 20; attempt += 1) {
    assertDeadline(deadline, 'request deadline exceeded before file attach');
    lastProbe = await evaluate(cdp, ATTACH_PROBE_EXPR, deadline, 10000);
    if (lastProbe && lastProbe.found) {
      const document = await cdpSend(cdp, 'DOM.getDocument', { depth: -1, pierce: true }, deadline, 15000);
      const rootId = document && document.root ? document.root.nodeId : 0;
      if (rootId) {
        const query = await cdpSend(
          cdp,
          'DOM.querySelector',
          { nodeId: rootId, selector: 'input[name="encoded_image"]' },
          deadline,
          15000,
        );
        if (query && query.nodeId) {
          await cdpSend(cdp, 'DOM.setFileInputFiles', { files: [uploadPath], nodeId: query.nodeId }, deadline, 15000);
          log('lens_attached', {
            request_id: requestId,
            attach_ms: Date.now() - attachStart,
            attempts: attempt,
            file_inputs: lastProbe.count,
          });
          return;
        }
      }
    }
    if (attempt >= 20 || Date.now() >= attachDeadline) break;
    const wait = Math.min(1000, attachDeadline - Date.now());
    if (wait <= 0) break;
    await sleepFor(wait);
  }
  throw fail('UPLOAD_REJECTED', 'input[name=encoded_image] was not found within ' + ATTACH_TIMEOUT_MS + 'ms', {
    inputs: lastProbe && Array.isArray(lastProbe.inputs) ? lastProbe.inputs : [],
  });
}

async function runLens(upload, deadline, requestId, startedAtMs) {
  return withPage(async (cdp) => {
    const network = { http429: 0 };
    cdp.on('Network.responseReceived', (params) => {
      try {
        if (params && params.response && params.response.status === 429) network.http429 += 1;
      } catch {
        // ignore
      }
    });
    await navigate(cdp, 'https://lens.google.com/upload?ep=ccm&s=4&hl=en', deadline);
    await sleepUntil(deadline, 2500);
    let consentTried = false;
    let pageUrl = (await evaluate(cdp, 'location.href', deadline, 5000)) || '';
    if (isConsentUrl(pageUrl)) {
      consentTried = true;
      const clicked = await tryAcceptConsent(cdp, deadline);
      log('lens_consent', { request_id: requestId, clicked });
      await sleepUntil(deadline, 1500);
      pageUrl = (await evaluate(cdp, 'location.href', deadline, 5000)) || '';
      if (isConsentUrl(pageUrl)) {
        throw fail('BLOCKED_BOT', 'Google consent wall requires manual acceptance', {
          signals: ['consent_wall'],
          result_url: sanitizeUrl(pageUrl),
        });
      }
    }
    if (isLoginUrl(pageUrl)) {
      throw fail('BLOCKED_BOT', 'Google login wall detected; not bypassed', {
        signals: ['login_wall'],
        result_url: sanitizeUrl(pageUrl),
      });
    }
    await attachLensFile(cdp, upload.file, deadline, requestId);

    let url = '';
    let text = '';
    let title = '';
    let risk = [];
    let previousLength = -1;
    let stable = 0;
    for (;;) {
      const left = deadline - Date.now();
      if (left <= 0) throw fail('TIMEOUT', 'Lens result did not stabilize before the request deadline');
      await sleepFor(Math.min(2000, left));
      assertDeadline(deadline, 'Lens result did not stabilize before the request deadline');
      url = (await evaluate(cdp, 'location.href', deadline, 10000)) || '';
      if (isConsentUrl(url)) {
        if (consentTried) {
          throw fail('BLOCKED_BOT', 'Google consent wall requires manual acceptance', {
            signals: ['consent_wall'],
            result_url: sanitizeUrl(url),
          });
        }
        consentTried = true;
        const clicked = await tryAcceptConsent(cdp, deadline);
        log('lens_consent', { request_id: requestId, clicked });
        await sleepUntil(deadline, 1500);
        continue;
      }
      if (isLoginUrl(url)) {
        throw fail('BLOCKED_BOT', 'Google login wall detected; not bypassed', {
          signals: ['login_wall'],
          result_url: sanitizeUrl(url),
        });
      }
      text = (await evaluate(cdp, 'document.body ? document.body.innerText : ""', deadline, 20000)) || '';
      title = (await evaluate(cdp, 'document.title', deadline, 10000)) || '';
      risk = riskSignals(text, url, title, network.http429);
      if (risk.length) break;
      if (isGoogleSearchUrl(url)) {
        if (text.length === previousLength) stable += 1;
        else stable = 0;
        previousLength = text.length;
        if (stable >= 3 && text.indexOf('正在搜索') < 0 && text.length > 400) break;
        if (stable >= 5 && text.length > 300) break;
      }
    }
    if (risk.length) {
      throw fail('BLOCKED_BOT', 'Google risk-control/captcha signal detected; no bypass attempted', {
        signals: risk,
        result_url: sanitizeUrl(url),
        page_title: String(title || '').slice(0, 200),
      });
    }
    assertDeadline(deadline, 'request deadline exceeded');
    if (!isGoogleSearchUrl(url)) {
      throw fail('NO_RESULT', 'upload did not reach a Google Lens result page', {
        result_url: sanitizeUrl(url),
        page_title: String(title || '').slice(0, 200),
        text_len: text.length,
      });
    }
    if (text.length < 200) {
      throw fail('NO_RESULT', 'Lens result page text is too short', {
        result_url: sanitizeUrl(url),
        page_title: String(title || '').slice(0, 200),
        text_len: text.length,
      });
    }
    const raw = (await evaluate(cdp, LENS_EXTRACT_EXPR, deadline, 30000)) || { links: [], cites: [] };
    const items = parseTextItems(text);
    const links = buildLensLinks(raw.links);
    const cites = buildLensCites(raw.cites);
    const topTitles = buildTopTitles(items);
    const topDomains = buildTopDomains(items);
    const result = {
      ok: true,
      status: 'ok',
      frame_sha256: upload.sha256,
      elapsed_ms: Date.now() - startedAtMs,
      text_sha256: sha256Text(text),
      text_len: text.length,
      topTitles,
      topDomains,
      cites,
      links,
      result_url: sanitizeUrl(url),
      fetched_at: new Date().toISOString(),
      request_id: requestId,
    };
    log('lens_result', {
      request_id: requestId,
      text_len: result.text_len,
      links: links.length,
      cites: cites.length,
      top_domains: topDomains.slice(0, 10),
    });
    return result;
  });
}

async function runSearch(query, waitMs, deadline, requestId, startedAtMs) {
  return withPage(async (cdp) => {
    const network = { http429: 0 };
    cdp.on('Network.responseReceived', (params) => {
      try {
        if (params && params.response && params.response.status === 429) network.http429 += 1;
      } catch {
        // ignore
      }
    });
    const searchUrl = 'https://www.google.com/search?q=' + encodeURIComponent(query) + '&hl=en&num=20';
    await navigate(cdp, searchUrl, deadline);
    const initialWait = Math.min(Math.max(1500, waitMs), Math.max(1000, deadline - Date.now() - 1000));
    await sleepUntil(deadline, initialWait);
    let url = '';
    let title = '';
    let text = '';
    let consentTried = false;
    let risk = [];
    let previousLength = -1;
    let stable = 0;
    const loopEnd = Math.min(deadline, Date.now() + 20000);
    for (;;) {
      url = (await evaluate(cdp, 'location.href', deadline, 10000)) || '';
      if (isConsentUrl(url)) {
        if (consentTried) {
          throw fail('BLOCKED_BOT', 'Google consent wall requires manual acceptance', {
            signals: ['consent_wall'],
            final_url: sanitizeUrl(url),
          });
        }
        consentTried = true;
        const clicked = await tryAcceptConsent(cdp, deadline);
        log('search_consent', { request_id: requestId, clicked });
        await sleepUntil(deadline, 1500);
        continue;
      }
      if (isLoginUrl(url)) {
        throw fail('BLOCKED_BOT', 'Google login wall detected; not bypassed', {
          signals: ['login_wall'],
          final_url: sanitizeUrl(url),
        });
      }
      title = (await evaluate(cdp, 'document.title', deadline, 10000)) || '';
      text = (await evaluate(cdp, 'document.body ? document.body.innerText : ""', deadline, 20000)) || '';
      risk = riskSignals(text, url, title, network.http429);
      if (risk.length) break;
      if (text.length === previousLength) stable += 1;
      else stable = 0;
      previousLength = text.length;
      if (stable >= 2 && text.length > 80) break;
      if (Date.now() >= loopEnd) break;
      await sleepUntil(deadline, 1000);
    }
    if (risk.length) {
      throw fail('BLOCKED_BOT', 'Google risk-control/captcha signal detected; no bypass attempted', {
        signals: risk,
        final_url: sanitizeUrl(url),
        page_title: String(title || '').slice(0, 200),
      });
    }
    assertDeadline(deadline, 'request deadline exceeded');
    if (!/^https?:/i.test(url)) {
      throw fail('NO_RESULT', 'Google search did not produce a result page', { final_url: sanitizeUrl(url) });
    }
    if (text.length < 50) {
      throw fail('NO_RESULT', 'Google search result page text is too short', {
        final_url: sanitizeUrl(url),
        page_title: String(title || '').slice(0, 200),
        text_len: text.length,
      });
    }
    let cookieHeader = '';
    let googleCookieCount = 0;
    try {
      const cookieResult = await cdpSend(cdp, 'Network.getAllCookies', {}, deadline, 10000);
      const cookies = (cookieResult.cookies || []).filter((cookie) => /(^|\.)google\.com$/i.test(cookie.domain));
      googleCookieCount = cookies.length;
      cookieHeader = cookies.map((cookie) => cookie.name + '=' + cookie.value).join('; ');
    } catch {
      // Link resolution falls back to cookie-less Google redirects when cookies are unavailable.
    }
    const raw = (await evaluate(cdp, SEARCH_EXTRACT_EXPR, deadline, 30000)) || {
      items: [],
      hasResults: false,
    };
    const items = await buildSearchItems(raw.items, deadline, cookieHeader);
    log('search_extract', {
      request_id: requestId,
      raw_items: Array.isArray(raw.items) ? raw.items.length : -1,
      has_results: Boolean(raw.hasResults),
      google_cookies: googleCookieCount,
      items: items.length,
    });
    if (!raw.hasResults && items.length === 0) {
      throw fail('NO_RESULT', 'Google search results container was not found', {
        final_url: sanitizeUrl(url),
        page_title: String(title || '').slice(0, 200),
        text_len: text.length,
      });
    }
    const response = {
      ok: true,
      status: 'ok',
      final_url: sanitizeUrl(url),
      title: String(title || '').slice(0, 500),
      text_len: text.length,
      items,
      fetched_at: new Date().toISOString(),
      request_id: requestId,
      elapsed_ms: Date.now() - startedAtMs,
    };
    log('search_result', {
      request_id: requestId,
      text_len: response.text_len,
      items: items.length,
      final_host: domainOf(response.final_url),
    });
    return response;
  });
}

function detectImage(buffer) {
  if (!buffer || buffer.length < 12) return null;
  if (buffer[0] === 0xff && buffer[1] === 0xd8 && buffer[2] === 0xff) {
    return { family: 'jpeg', ext: '.jpg', mime: 'image/jpeg' };
  }
  const pngMagic = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
  if (pngMagic.every((byte, index) => buffer[index] === byte)) {
    return { family: 'png', ext: '.png', mime: 'image/png' };
  }
  if (buffer.toString('ascii', 0, 4) === 'RIFF' && buffer.toString('ascii', 8, 12) === 'WEBP') {
    return { family: 'webp', ext: '.webp', mime: 'image/webp' };
  }
  return null;
}

function extensionFamily(extension) {
  if (extension === '.jpg' || extension === '.jpeg') return 'jpeg';
  if (extension === '.png') return 'png';
  if (extension === '.webp') return 'webp';
  return '';
}

function validateImage(buffer, filename) {
  if (!buffer || buffer.length === 0) throw fail('INPUT_INVALID', 'file is empty');
  if (buffer.length > MAX_FILE_BYTES) throw fail('INPUT_INVALID', 'file exceeds the 20 MiB limit');
  const kind = detectImage(buffer);
  if (!kind) throw fail('INPUT_INVALID', 'unsupported image content: expected JPEG, PNG or WebP');
  if (filename) {
    const extension = path.extname(path.basename(String(filename))).toLowerCase();
    if (extension) {
      const family = extensionFamily(extension);
      if (!family) throw fail('INPUT_INVALID', 'unsupported file extension: expected .jpg, .jpeg, .png or .webp');
      if (family !== kind.family) throw fail('INPUT_INVALID', 'file extension does not match image content');
    }
  }
  return kind;
}

function createTempUpload(buffer, extension) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'qimeng-research-sidecar-'));
  const file = path.join(directory, 'frame' + extension);
  fs.writeFileSync(file, buffer, { mode: 0o600 });
  return {
    directory,
    file,
    bytes: buffer.length,
    sha256: sha256(buffer),
  };
}

function removeTempUpload(upload) {
  if (!upload || !upload.directory) return;
  try {
    fs.rmSync(upload.directory, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 });
  } catch (error) {
    log('temp_cleanup_failed', { code: error && error.code ? String(error.code).slice(0, 40) : 'unknown' });
  }
}

function isWithinAllowedRoot(realPath) {
  if (!allowedRoot) return false;
  const root = process.platform === 'win32' ? allowedRoot.toLowerCase() : allowedRoot;
  const candidate = process.platform === 'win32' ? realPath.toLowerCase() : realPath;
  const relative = path.relative(root, candidate);
  return relative === '' || (!relative.startsWith('..' + path.sep) && relative !== '..' && !path.isAbsolute(relative));
}

function validateAllowedFile(input) {
  if (!allowedRoot) {
    throw fail('INPUT_INVALID', 'path input is disabled: QIMENG_RESEARCH_SIDECAR_ALLOWED_ROOT is not configured');
  }
  if (typeof input !== 'string' || !input.trim()) {
    throw fail('INPUT_INVALID', 'file must be a non-empty absolute path');
  }
  if (!path.isAbsolute(input)) throw fail('INPUT_INVALID', 'file must be an absolute path');
  let real;
  try {
    real = fs.realpathSync(path.resolve(input.trim()));
  } catch {
    throw fail('INPUT_INVALID', 'file does not exist');
  }
  if (!isWithinAllowedRoot(real)) throw fail('INPUT_INVALID', 'file is outside the configured allowed root');
  let stat;
  try {
    stat = fs.statSync(real);
  } catch {
    throw fail('INPUT_INVALID', 'file does not exist');
  }
  if (!stat.isFile()) throw fail('INPUT_INVALID', 'file is not a regular file');
  if (stat.size <= 0 || stat.size > MAX_FILE_BYTES) {
    throw fail('INPUT_INVALID', 'file size must be between 1 byte and 20 MiB');
  }
  const extension = path.extname(real).toLowerCase();
  if (!ALLOWED_EXT.has(extension)) {
    throw fail('INPUT_INVALID', 'unsupported file extension: expected .jpg, .jpeg, .png or .webp');
  }
  return { real, extension, size: stat.size };
}

function multipartBoundary(contentType) {
  const match = /boundary=(?:"([^"]+)"|([^;]+))/i.exec(String(contentType || ''));
  return match ? String(match[1] || match[2] || '').trim() : '';
}

function parsePartHeaders(headerText) {
  const headers = {};
  for (const line of String(headerText || '').split(/\r?\n/)) {
    const index = line.indexOf(':');
    if (index <= 0) continue;
    const key = line.slice(0, index).trim().toLowerCase();
    const value = line.slice(index + 1).trim();
    headers[key] = headers[key] ? headers[key] + '; ' + value : value;
  }
  return headers;
}

function dispositionParam(disposition, name) {
  const expression = new RegExp('(?:^|;)\\s*' + name + '\\*?\\s*=\\s*(?:"([^"]*)"|([^;\\s]*))', 'i');
  const match = expression.exec(String(disposition || ''));
  return match ? String(match[1] !== undefined ? match[1] : match[2] || '') : '';
}

function parseMultipartBody(body, boundary) {
  if (!boundary || boundary.length > 200) throw fail('INPUT_INVALID', 'invalid multipart boundary');
  const source = Buffer.concat([Buffer.from('\r\n'), body]);
  const separator = Buffer.from('\r\n--' + boundary, 'latin1');
  const parts = [];
  let position = source.indexOf(separator);
  if (position === -1) throw fail('INPUT_INVALID', 'invalid multipart body');
  while (position !== -1) {
    let cursor = position + separator.length;
    if (source[cursor] === 0x2d && source[cursor + 1] === 0x2d) break;
    if (source[cursor] === 0x0d && source[cursor + 1] === 0x0a) cursor += 2;
    const headerEnd = source.indexOf('\r\n\r\n', cursor);
    if (headerEnd === -1) throw fail('INPUT_INVALID', 'invalid multipart part headers');
    const headerText = source.subarray(cursor, headerEnd).toString('utf8');
    const dataStart = headerEnd + 4;
    const next = source.indexOf(separator, dataStart);
    if (next === -1) throw fail('INPUT_INVALID', 'unterminated multipart part');
    parts.push({ headerText, data: source.subarray(dataStart, next) });
    position = next;
  }
  return parts;
}

function extractSingleFilePart(body, boundary) {
  const parts = parseMultipartBody(body, boundary);
  const files = [];
  for (const part of parts) {
    const headers = parsePartHeaders(part.headerText);
    const disposition = headers['content-disposition'] || '';
    const name = dispositionParam(disposition, 'name');
    if (name !== 'file') continue;
    files.push({
      filename: path.basename(dispositionParam(disposition, 'filename') || '').slice(0, 200),
      data: part.data,
    });
  }
  if (files.length === 0) throw fail('INPUT_INVALID', 'multipart field "file" is required');
  if (files.length > 1) throw fail('INPUT_INVALID', 'exactly one multipart field "file" is allowed');
  return files[0];
}

function readBody(req, limitBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    let tooLarge = false;
    const onData = (chunk) => {
      size += chunk.length;
      if (size > limitBytes) {
        tooLarge = true;
        chunks.length = 0;
      } else if (!tooLarge) {
        chunks.push(chunk);
      }
    };
    req.on('data', onData);
    req.on('end', () => {
      req.removeListener('data', onData);
      if (tooLarge) reject(fail('INPUT_INVALID', 'request body exceeds the allowed size'));
      else resolve(Buffer.concat(chunks));
    });
    req.on('error', () => reject(fail('INPUT_INVALID', 'request body could not be read')));
  });
}

async function receiveLensInput(req) {
  const contentType = String(req.headers['content-type'] || '');
  if (/^multipart\/form-data/i.test(contentType)) {
    const boundary = multipartBoundary(contentType);
    if (!boundary) throw fail('INPUT_INVALID', 'multipart boundary is missing');
    const body = await readBody(req, MAX_MULTIPART_BYTES);
    const part = extractSingleFilePart(body, boundary);
    const kind = validateImage(part.data, part.filename);
    return { mode: 'multipart', ...createTempUpload(part.data, kind.ext) };
  }
  if (/^application\/json/i.test(contentType)) {
    const body = await readBody(req, MAX_JSON_BYTES);
    let payload;
    try {
      payload = JSON.parse(body.toString('utf8'));
    } catch {
      throw fail('INPUT_INVALID', 'invalid JSON body');
    }
    const validated = validateAllowedFile(payload && payload.file);
    let buffer;
    try {
      buffer = fs.readFileSync(validated.real);
    } catch {
      throw fail('INPUT_INVALID', 'file could not be read');
    }
    const kind = validateImage(buffer, path.basename(validated.real));
    return { mode: 'path', ...createTempUpload(buffer, kind.ext) };
  }
  throw fail(
    'INPUT_INVALID',
    'expected multipart/form-data with field "file" or application/json {"file":"<absolute path>"}',
  );
}

async function receiveSearchInput(req) {
  const contentType = String(req.headers['content-type'] || '');
  if (!/^application\/json/i.test(contentType)) {
    throw fail('INPUT_INVALID', 'POST /search requires application/json');
  }
  const body = await readBody(req, MAX_JSON_BYTES);
  let payload;
  try {
    payload = JSON.parse(body.toString('utf8'));
  } catch {
    throw fail('INPUT_INVALID', 'invalid JSON body');
  }
  if (!payload || typeof payload.query !== 'string') {
    throw fail('INPUT_INVALID', 'query must be a non-empty string');
  }
  const query = payload.query.trim();
  if (!query) throw fail('INPUT_INVALID', 'query must be a non-empty string');
  if (query.length > 500) throw fail('INPUT_INVALID', 'query is too long (max 500 characters)');
  let waitMs = 5000;
  if (payload.waitMs !== undefined) {
    if (
      typeof payload.waitMs !== 'number' ||
      !Number.isFinite(payload.waitMs) ||
      payload.waitMs < 0 ||
      payload.waitMs > 30000
    ) {
      throw fail('INPUT_INVALID', 'waitMs must be a number between 0 and 30000');
    }
    waitMs = Math.floor(payload.waitMs);
  }
  return { query, waitMs };
}

function authOk(req) {
  const expected = crypto.createHash('sha256').update(TOKEN, 'utf8').digest();
  const values = [];
  const rawToken = req.headers['x-lens-token'];
  if (Array.isArray(rawToken)) values.push(...rawToken);
  else if (typeof rawToken === 'string') values.push(rawToken);
  const rawAuthorization = req.headers.authorization;
  const authorizationValues = Array.isArray(rawAuthorization) ? rawAuthorization : [rawAuthorization];
  for (const value of authorizationValues) {
    if (typeof value !== 'string') continue;
    const match = /^Bearer\s+(.+)$/i.exec(value);
    if (match) values.push(match[1]);
  }
  for (const value of values) {
    const candidate = String(value || '').trim();
    if (!candidate) continue;
    const got = crypto.createHash('sha256').update(candidate, 'utf8').digest();
    if (crypto.timingSafeEqual(got, expected)) return true;
  }
  return false;
}

function sendJson(res, status, body, headers) {
  if (res.writableEnded || res.destroyed) return;
  const data = Buffer.from(JSON.stringify(body), 'utf8');
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': data.length,
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    ...(headers || {}),
  });
  res.end(data);
}

function publicError(code, message, requestId, elapsedMs, extra) {
  return {
    ok: false,
    status: PUBLIC_STATUS[code] || 'error',
    request_id: requestId,
    elapsed_ms: elapsedMs,
    error: {
      code,
      message: redactMessage(message || code),
      ...(extra || {}),
    },
  };
}

function sanitizeExtra(extra) {
  if (!extra || typeof extra !== 'object') return undefined;
  const output = {};
  if (Array.isArray(extra.signals)) {
    output.signals = extra.signals.slice(0, 10).map((signal) => String(signal).slice(0, 40));
  }
  if (typeof extra.result_url === 'string' && extra.result_url) output.result_url = sanitizeUrl(extra.result_url);
  if (typeof extra.final_url === 'string' && extra.final_url) output.final_url = sanitizeUrl(extra.final_url);
  if (typeof extra.page_title === 'string') output.page_title = extra.page_title.slice(0, 200);
  if (Array.isArray(extra.inputs)) {
    output.inputs = extra.inputs.slice(0, 5).map((input) => ({
      name: String((input && input.name) || '').slice(0, 60),
      accept: String((input && input.accept) || '').slice(0, 80),
    }));
  }
  if (Number.isInteger(extra.text_len)) output.text_len = extra.text_len;
  return Object.keys(output).length ? output : undefined;
}

function normalizeError(error) {
  if (error instanceof SidecarError && STATUS_BY_CODE[error.code]) {
    return { code: error.code, message: redactMessage(error.message), extra: sanitizeExtra(error.extra) };
  }
  if (error && (error.code === 'CDP_TIMEOUT' || error.name === 'TimeoutError' || error.code === 'ETIMEDOUT')) {
    return { code: 'TIMEOUT', message: 'request deadline exceeded', extra: undefined };
  }
  return {
    code: 'NO_RESULT',
    message: redactMessage(error && error.message ? error.message : 'internal error'),
    extra: undefined,
  };
}

function sendRequestError(res, error, requestId, startedAtMs, route) {
  const normalized = normalizeError(error);
  const status = STATUS_BY_CODE[normalized.code] || 502;
  counters.error_total += 1;
  counters.error_counts[normalized.code] = (counters.error_counts[normalized.code] || 0) + 1;
  log('request_error', {
    request_id: requestId,
    route,
    code: normalized.code,
    message: normalized.message,
    elapsed_ms: Date.now() - startedAtMs,
    signals: normalized.extra && normalized.extra.signals ? normalized.extra.signals : undefined,
  });
  sendJson(
    res,
    status,
    publicError(normalized.code, normalized.message, requestId, Date.now() - startedAtMs, normalized.extra),
    RETRY_AFTER[normalized.code] ? { 'retry-after': RETRY_AFTER[normalized.code] } : undefined,
  );
}

async function doLens(req, res, requestId, startedAtMs, deadline) {
  let upload = null;
  try {
    upload = await receiveLensInput(req);
    if (!isBrowserReady()) throw fail('NO_RESULT', 'browser is not ready; check GET /healthz');
    log('lens_start', {
      request_id: requestId,
      mode: upload.mode,
      bytes: upload.bytes,
      frame_sha256: upload.sha256,
    });
    const result = await runLens(upload, deadline, requestId, startedAtMs);
    counters.ok_total += 1;
    log('lens_ok', {
      request_id: requestId,
      elapsed_ms: result.elapsed_ms,
      frame_sha256: result.frame_sha256,
      text_sha256: result.text_sha256,
      text_len: result.text_len,
      links: result.links.length,
      cites: result.cites.length,
      top_domains: result.topDomains.slice(0, 10),
    });
    sendJson(res, 200, result);
  } catch (error) {
    sendRequestError(res, error, requestId, startedAtMs, '/lens');
  } finally {
    removeTempUpload(upload);
  }
}

async function doSearch(req, res, requestId, startedAtMs, deadline) {
  try {
    const input = await receiveSearchInput(req);
    if (!isBrowserReady()) throw fail('NO_RESULT', 'browser is not ready; check GET /healthz');
    log('search_start', { request_id: requestId, query_len: input.query.length, wait_ms: input.waitMs });
    const result = await runSearch(input.query, input.waitMs, deadline, requestId, startedAtMs);
    counters.ok_total += 1;
    log('search_ok', {
      request_id: requestId,
      elapsed_ms: result.elapsed_ms,
      text_len: result.text_len,
      items: result.items.length,
      final_host: domainOf(result.final_url),
    });
    sendJson(res, 200, result);
  } catch (error) {
    sendRequestError(res, error, requestId, startedAtMs, '/search');
  }
}

function healthPayload() {
  return {
    ok: true,
    ready: isBrowserReady(),
    edgePid: browser.pid || null,
    inflight,
    counters: {
      requests_total: counters.requests_total,
      ok_total: counters.ok_total,
      error_total: counters.error_total,
      busy_total: counters.busy_total,
      error_counts: { ...counters.error_counts },
    },
    version: VERSION,
    startedAt,
    error: browser.error || null,
    browserState: browser.state,
    cdpPort: browser.cdpPort || null,
    timeoutMs: TIMEOUT_MS,
    attachTimeoutMs: ATTACH_TIMEOUT_MS,
    allowedRootConfigured: Boolean(allowedRoot),
  };
}

async function handleRequest(req, res) {
  let pathname = '/';
  try {
    pathname = new URL(req.url || '/', 'http://' + HOST + ':' + PORT).pathname;
  } catch {
    sendJson(res, 404, { ok: false, status: 'error', error: { code: 'NOT_FOUND', message: 'unknown route' } });
    return;
  }
  if (req.method === 'GET' && pathname === '/healthz') {
    sendJson(res, 200, healthPayload());
    return;
  }
  if (req.method === 'POST' && (pathname === '/lens' || pathname === '/search')) {
    const requestId = crypto.randomUUID();
    const startedAtMs = Date.now();
    counters.requests_total += 1;
    if (!authOk(req)) {
      counters.error_total += 1;
      counters.error_counts.UNAUTHORIZED = (counters.error_counts.UNAUTHORIZED || 0) + 1;
      log('request_unauthorized', { request_id: requestId, route: pathname });
      sendJson(res, 401, publicError('UNAUTHORIZED', 'missing or invalid sidecar token', requestId, 0));
      try {
        req.resume();
      } catch {
        // ignore
      }
      return;
    }
    if (inflight > 0) {
      counters.busy_total += 1;
      counters.error_total += 1;
      counters.error_counts.BUSY = (counters.error_counts.BUSY || 0) + 1;
      log('request_busy', { request_id: requestId, route: pathname });
      sendJson(res, 429, publicError('BUSY', 'single-instance concurrency limit is 1', requestId, 0), {
        'retry-after': RETRY_AFTER.BUSY,
      });
      try {
        req.resume();
      } catch {
        // ignore
      }
      return;
    }
    inflight = 1;
    const deadline = startedAtMs + TIMEOUT_MS;
    try {
      if (pathname === '/lens') await doLens(req, res, requestId, startedAtMs, deadline);
      else await doSearch(req, res, requestId, startedAtMs, deadline);
    } catch (error) {
      sendRequestError(res, error, requestId, startedAtMs, pathname);
    } finally {
      inflight = 0;
    }
    return;
  }
  sendJson(res, 404, { ok: false, status: 'error', error: { code: 'NOT_FOUND', message: 'unknown route' } });
  if (req.method === 'POST') {
    try {
      req.resume();
    } catch {
      // ignore
    }
  }
}

const server = http.createServer((req, res) => {
  handleRequest(req, res).catch((error) => {
    log('request_unhandled', { message: redactMessage(error && error.message ? error.message : error) });
    sendJson(res, 502, publicError('NO_RESULT', 'internal error', crypto.randomUUID(), 0));
  });
});
server.headersTimeout = 30000;
server.requestTimeout = Math.max(120000, TIMEOUT_MS + 30000);
server.keepAliveTimeout = 5000;

server.on('error', (error) => {
  console.error(
    '[research-sidecar] HTTP server could not listen on ' +
      HOST +
      ':' +
      PORT +
      ' (' +
      (error && error.code ? error.code : 'error') +
      ').',
  );
  process.exit(1);
});

server.listen(PORT, HOST, () => {
  log('http_listen', {
    version: VERSION,
    host: HOST,
    port: PORT,
    token_required: true,
    allowed_root_configured: Boolean(allowedRoot),
    timeout_ms: TIMEOUT_MS,
    attach_timeout_ms: ATTACH_TIMEOUT_MS,
    edge: path.basename(EDGE) || 'browser',
  });
  launchBrowser().catch((error) => {
    if (browser.child) killBrowser();
    browser.state = 'failed';
    browser.error = browser.error || 'browser failed to start';
    log('browser_failed', { message: redactMessage(error && error.message ? error.message : error) });
    // Keep the HTTP service alive so /healthz can report ready:false and error.
  });
});

function shutdown(reason, exitCode) {
  if (shuttingDown) return;
  shuttingDown = true;
  log('shutdown', { reason, code: exitCode === undefined ? 0 : exitCode });
  try {
    server.close();
  } catch {
    // ignore
  }
  try {
    if (typeof server.closeAllConnections === 'function') server.closeAllConnections();
  } catch {
    // ignore
  }
  killBrowser();
  setTimeout(() => process.exit(exitCode === undefined ? 0 : exitCode), 300);
}

process.on('SIGINT', () => shutdown('SIGINT', 0));
process.on('SIGTERM', () => shutdown('SIGTERM', 0));
process.on('uncaughtException', (error) => {
  log('uncaught_exception', { message: redactMessage(error && error.message ? error.message : error) });
  shutdown('uncaughtException', 1);
});
process.on('unhandledRejection', (error) => {
  log('unhandled_rejection', { message: redactMessage(error && error.message ? error.message : error) });
  shutdown('unhandledRejection', 1);
});
process.on('exit', () => {
  if (browser.child) {
    try {
      browser.child.kill();
    } catch {
      // ignore
    }
  }
  if (process.platform === 'win32' && browser.pid) {
    try {
      spawnSync('taskkill', ['/PID', String(browser.pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true });
    } catch {
      // ignore
    }
  }
});
