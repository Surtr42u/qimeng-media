#!/usr/bin/env python3
"""Yandex reverse-image search (CBIR) with a compact JSON result.

Usage:
  python -X utf8 tools/research_kit/yandex_cbir.py IMAGE [--out FILE] [--summary]

Only the Python 3.13 standard library is used. The image is uploaded once to
Yandex's public CBIR endpoint, the result page is parsed for candidate sites and
similar images, and the raw HTML (when kept) plus the compact JSON are cached in
an ignored directory. No HTML is printed and no page text is echoed.

Safety/privacy:
- Only https requests, bounded response size, redirects limited to 3 hops.
- The tool never solves a captcha or login wall; HTTP 403/429 is reported as
  blocked/rate_limited and must not be retried in a loop.
- Real image paths/results live in the ignored cache or an explicit --out file;
  do not commit them.

Exit codes: 0 ok; 1 blocked/rate_limited/timeout/not_found/error; 2 usage.
"""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import os
import re
import socket
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

TOOL = 'yandex_cbir'
VERSION = '1.0.0'
EXIT_OK, EXIT_PARTIAL, EXIT_USAGE = 0, 1, 2
UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36')
MAX_BODY = 8 * 1024 * 1024
MAX_HTML = 4 * 1024 * 1024
TRACKERS = {'utm_source', 'utm_medium', 'utm_campaign', 'utm_term', 'utm_content',
            'yclid', 'gclid', 'fbclid', 'from', 'source', 'ref'}


def find_repo_root() -> Path | None:
    here = Path(__file__).resolve()
    for parent in here.parents:
        if (parent / 'AGENTS.md').is_file() and (parent / 'tools').is_dir():
            return parent
    return None


def default_cache_dir() -> Path:
    env = os.environ.get('QIMENG_RESEARCH_KIT_CACHE', '').strip()
    if env:
        return Path(env).expanduser().resolve()
    root = find_repo_root() or Path.cwd()
    return root / 'build' / 'research-kit.local' / 'cache'


def now_iso() -> str:
    return time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())


def clip(value, limit: int) -> str:
    text = re.sub(r'\s+', ' ', str(value or '')).strip()
    return text[:limit]


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return None


def write_json_atomic(path: Path, payload) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.tmp')
    temporary.write_text(json.dumps(payload, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    os.replace(temporary, path)


class FetchError(Exception):
    """Network failure with the shared research-kit status vocabulary."""

    def __init__(self, status: str, code: str, detail: str = ''):
        super().__init__(code)
        self.status = status
        self.code = code
        self.detail = detail


def build_opener(no_proxy: bool):
    handlers = []
    if no_proxy:
        handlers.append(urllib.request.ProxyHandler({}))

    class HttpsRedirect(urllib.request.HTTPRedirectHandler):
        max_redirections = 3

        def redirect_request(self, req, fp, code, msg, headers, newurl):
            if not str(newurl).lower().startswith('https://'):
                raise FetchError('blocked', 'redirect_insecure')
            return super().redirect_request(req, fp, code, msg, headers, newurl)

    handlers.append(HttpsRedirect())
    return urllib.request.build_opener(*handlers)


def fetch_bytes(url: str, *, data: bytes | None = None, headers: dict | None = None,
                timeout: float = 90.0, max_bytes: int = MAX_BODY,
                no_proxy: bool = False) -> tuple[bytes, int, dict]:
    request_headers = {
        'User-Agent': UA,
        'Accept': 'text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.9',
    }
    if headers:
        request_headers.update(headers)
    request = urllib.request.Request(url, data=data, headers=request_headers, method='POST' if data else 'GET')
    opener = build_opener(no_proxy)
    try:
        with opener.open(request, timeout=timeout) as response:
            body = response.read(max_bytes + 1)
            if len(body) > max_bytes:
                raise FetchError('error', 'body_too_large')
            return body, response.status, dict(response.headers)
    except FetchError:
        raise
    except urllib.error.HTTPError as exc:
        if exc.code in (401, 403, 451):
            raise FetchError('blocked', 'http_%d' % exc.code) from None
        if exc.code == 429:
            raise FetchError('rate_limited', 'http_429') from None
        if exc.code == 404:
            raise FetchError('not_found', 'http_404') from None
        raise FetchError('error', 'http_%d' % exc.code) from None
    except urllib.error.URLError as exc:
        reason = getattr(exc, 'reason', exc)
        text = str(reason)
        if isinstance(reason, ssl.SSLError) or 'CERTIFICATE' in text.upper() or 'SSL' in type(reason).__name__.upper():
            raise FetchError('blocked', 'tls_failed', text[:120]) from None
        if isinstance(reason, (socket.timeout, TimeoutError)):
            raise FetchError('timeout', 'timeout') from None
        raise FetchError('error', 'network_error', text[:120]) from None
    except (socket.timeout, TimeoutError):
        raise FetchError('timeout', 'timeout') from None
    except ssl.SSLError as exc:
        raise FetchError('blocked', 'tls_failed', str(exc)[:120]) from None


class FileRateLimiter:
    """Cross-invocation spacing limiter stored in the ignored cache directory."""

    def __init__(self, path: Path, limit: int, window_seconds: float, label: str):
        self.path = path
        self.limit = max(0, int(limit))
        self.window = max(0.0, float(window_seconds))
        self.label = label

    def acquire(self) -> float:
        if self.limit <= 0 or self.window <= 0:
            return 0.0
        waited = 0.0
        for _ in range(120):
            raw = read_json(self.path)
            stamps = [float(item) for item in raw if isinstance(item, (int, float))] if isinstance(raw, list) else []
            now = time.time()
            stamps = [stamp for stamp in stamps if now - stamp < self.window]
            if len(stamps) < self.limit:
                stamps.append(now)
                write_json_atomic(self.path, stamps)
                return waited
            delay = self.window - (now - min(stamps)) + 0.2
            if delay <= 0:
                continue
            print('[%s] rate limit: sleeping %.1fs' % (self.label, delay), file=sys.stderr)
            time.sleep(delay)
            waited += delay
        raise FetchError('rate_limited', 'limiter_stalled')


def build_multipart(field: str, filename: str, content: bytes, mime: str) -> tuple[bytes, str]:
    boundary = '----ResearchKit' + uuid.uuid4().hex
    head = (
        '--' + boundary + '\r\n'
        'Content-Disposition: form-data; name="' + field + '"; filename="' + filename + '"\r\n'
        'Content-Type: ' + mime + '\r\n\r\n'
    ).encode('utf-8')
    tail = ('\r\n--' + boundary + '--\r\n').encode('utf-8')
    return head + content + tail, boundary


def guess_mime(path: Path, data: bytes) -> str:
    if data[:3] == b'\xff\xd8\xff':
        return 'image/jpeg'
    if data[:8] == b'\x89PNG\r\n\x1a\n':
        return 'image/png'
    if data[:4] == b'RIFF' and data[8:12] == b'WEBP':
        return 'image/webp'
    suffix = path.suffix.lower()
    return {'jpg': 'image/jpeg', 'jpeg': 'image/jpeg', 'webp': 'image/webp'}.get(suffix.lstrip('.'), 'image/jpeg')


def sanitize_url(raw: str) -> str:
    try:
        parts = urllib.parse.urlsplit(str(raw))
    except ValueError:
        return ''
    if parts.scheme not in ('http', 'https') or not parts.netloc:
        return ''
    query = [(key, value) for key, value in urllib.parse.parse_qsl(parts.query, keep_blank_values=False)
             if key.lower() not in TRACKERS]
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, parts.path, urllib.parse.urlencode(query), ''))


def domain_of(raw: str) -> str:
    try:
        return urllib.parse.urlsplit(str(raw)).hostname.lower().removeprefix('www.').rstrip('.')
    except (ValueError, AttributeError):
        return ''


def strip_tags(value: str) -> str:
    return html.unescape(re.sub(r'<[^>]+>', '', value or ''))


def extract_cbir_id(payload) -> str:
    if not isinstance(payload, dict):
        return ''
    for key in ('cbirId', 'cbir_id', 'cbirID'):
        value = payload.get(key)
        if isinstance(value, str) and value:
            return value
    for block in payload.get('blocks') or []:
        if not isinstance(block, dict):
            continue
        params = block.get('params') or {}
        if not isinstance(params, dict):
            continue
        for key in ('cbirId', 'cbir_id', 'cbirID'):
            value = params.get(key)
            if isinstance(value, str) and value:
                return value
        for key in ('url', 'cbir_page_url', 'serp_url'):
            value = params.get(key)
            if isinstance(value, str) and 'cbir_id=' in value:
                query = urllib.parse.parse_qs(urllib.parse.urlsplit(value).query)
                if query.get('cbir_id'):
                    return query['cbir_id'][0]
    return ''


def parse_sites(page: str) -> list[dict]:
    sites = []
    seen = set()
    start = page.find('CbirSitesList')
    section = page[start:start + 200000] if start >= 0 else page
    chunks = section.split('<li class="CbirSites-Item"')[1:]
    for chunk in chunks:
        anchor = re.search(r'<a[^>]+href="([^"]+)"[^>]*>(.*?)</a>', chunk, re.S)
        if not anchor:
            continue
        url = sanitize_url(html.unescape(anchor.group(1)))
        if not url:
            continue
        title = clip(strip_tags(anchor.group(2)), 160)
        snippet = ''
        snippet_match = re.search(r'CbirSites-ItemSnippet[^>]*>(.*?)</div>', chunk, re.S)
        if snippet_match:
            snippet = clip(strip_tags(snippet_match.group(1)), 160)
        domain = domain_of(url)
        if not domain or url in seen:
            continue
        seen.add(url)
        item = {'title': title, 'domain': domain, 'url': clip(url, 500)}
        if snippet:
            item['snippet'] = snippet
        sites.append(item)
        if len(sites) >= 12:
            break
    return sites


def parse_similar(page: str) -> list[dict]:
    similar = []
    seen = set()
    for match in re.finditer(r'img_url=([^&"\']+)', page):
        url = sanitize_url(urllib.parse.unquote(html.unescape(match.group(1))))
        if not url or url in seen:
            continue
        seen.add(url)
        similar.append({'url': clip(url, 500), 'domain': domain_of(url)})
        if len(similar) >= 8:
            break
    start = page.find('CbirSimilarList')
    if start >= 0:
        section = page[start:start + 200000]
        alts = re.findall(r'<img[^>]+alt="([^"]*)"', section)
        for index, alt in enumerate(alts[:len(similar)]):
            text = clip(strip_tags(alt), 120)
            if text:
                similar[index]['alt'] = text
    return similar


def parse_tags(page: str) -> list[str]:
    tags = []
    for match in re.finditer(r'alt="([^"]{2,80})"', page[:200000]):
        text = clip(strip_tags(match.group(1)), 60)
        if not text or text.lower() in ('image', 'picture', 'photo'):
            continue
        if text not in tags:
            tags.append(text)
        if len(tags) >= 10:
            break
    return tags


def parse_page(page: str) -> dict:
    sites = parse_sites(page)
    similar = parse_similar(page)
    notes = []
    if not sites:
        notes.append('sites_parse_empty')
    return {'sites': sites, 'similar': similar, 'tags': parse_tags(page), 'notes': notes}


def run(args) -> tuple[int, dict]:
    image = Path(args.image).expanduser()
    if not image.is_file():
        raise ValueError('image does not exist: ' + str(image))
    data = image.read_bytes()
    if not data:
        raise ValueError('image is empty')
    image_sha = sha256_bytes(data)
    cache_root = Path(args.cache_dir).expanduser().resolve() / TOOL if args.cache_dir else default_cache_dir() / TOOL
    result = {
        'tool': TOOL,
        'version': VERSION,
        'status': 'error',
        'imageSha256': image_sha,
        'queriedAt': now_iso(),
        'cbirId': '',
        'resultUrl': '',
        'sites': [],
        'similar': [],
        'tags': [],
        'notes': [],
    }

    cache_key = image_sha + '-' + re.sub(r'[^a-z0-9]+', '', args.host.lower()) + '-' + args.lang
    cache_file = cache_root / (cache_key + '.json')
    if not args.refresh:
        cached = read_json(cache_file)
        if isinstance(cached, dict) and cached.get('status') == 'ok':
            cached_at = cached.get('cachedAt') or ''
            fresh = True
            if cached_at and args.max_age_hours > 0:
                try:
                    stamp = time.mktime(time.strptime(cached_at, '%Y-%m-%dT%H:%M:%SZ'))
                    fresh = (time.time() - stamp) < args.max_age_hours * 3600
                except ValueError:
                    fresh = True
            if fresh:
                result.update({key: cached.get(key) for key in ('sites', 'similar', 'tags', 'cbirId', 'resultUrl', 'notes')})
                result['status'] = 'ok'
                result['cacheHit'] = True
                return EXIT_OK, result

    limiter = FileRateLimiter(cache_root / 'rate.json', 1, args.min_interval_seconds, TOOL)
    try:
        limiter.acquire()
        filename = clip(image.name, 80) or 'image'
        mime = guess_mime(image, data)
        body, boundary = build_multipart('upfile', filename, data, mime)
        upload_url = (
            'https://' + args.host + '/images/search?rpt=imageview&format=json&request='
            + urllib.parse.quote('{"blocks":[{"block":"b-page_type_search-by-image__link"}]}')
        )
        payload_bytes, _, _ = fetch_bytes(
            upload_url,
            data=body,
            headers={'Content-Type': 'multipart/form-data; boundary=' + boundary},
            timeout=args.timeout,
            max_bytes=MAX_BODY,
            no_proxy=args.no_proxy,
        )
        try:
            payload = json.loads(payload_bytes.decode('utf-8', 'ignore'))
        except ValueError:
            raise FetchError('error', 'invalid_json')
        cbir_id = extract_cbir_id(payload)
        if not cbir_id:
            result['status'] = 'not_found'
            result['notes'] = ['no_cbir_id']
            return EXIT_PARTIAL, result
        result['cbirId'] = cbir_id
        result['resultUrl'] = (
            'https://' + args.host + '/images/search?rpt=imageview&lang=' + urllib.parse.quote(args.lang)
            + '&cbir_id=' + urllib.parse.quote(cbir_id)
        )
        page_bytes, _, _ = fetch_bytes(result['resultUrl'], timeout=args.timeout, max_bytes=MAX_HTML,
                                       no_proxy=args.no_proxy)
        page = page_bytes.decode('utf-8', 'ignore')
        if args.keep_html:
            raw_dir = cache_root / 'raw'
            raw_dir.mkdir(parents=True, exist_ok=True)
            (raw_dir / (image_sha + '.html')).write_text(page, encoding='utf-8')
        parsed = parse_page(page)
        result.update(parsed)
        result['status'] = 'ok'
        result['cachedAt'] = now_iso()
        write_json_atomic(cache_file, {**result, 'cachedAt': result['cachedAt']})
        return EXIT_OK, result
    except FetchError as exc:
        result['status'] = exc.status
        result['errorCode'] = exc.code
        if exc.detail:
            result['notes'] = [clip(exc.detail, 120)]
        return EXIT_PARTIAL, result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog='yandex_cbir.py',
        description='Yandex reverse-image search; compact candidate JSON only (no HTML dump).',
    )
    parser.add_argument('image', help='image file (JPEG/PNG/WebP recommended)')
    parser.add_argument('--out', help='write the JSON result here instead of stdout')
    parser.add_argument('--cache-dir', help='cache root; default $QIMENG_RESEARCH_KIT_CACHE or build/research-kit.local/cache')
    parser.add_argument('--host', default='yandex.com', help='Yandex host (default: yandex.com)')
    parser.add_argument('--lang', default='en', help='language code (default: en)')
    parser.add_argument('--timeout', type=float, default=90.0, help='per-request seconds (default: 90)')
    parser.add_argument('--max-age-hours', type=int, default=168, help='cache TTL hours (default: 168)')
    parser.add_argument('--min-interval-seconds', type=float, default=4.0, help='minimum spacing between uploads (default: 4)')
    parser.add_argument('--refresh', action='store_true', help='ignore the cached result')
    parser.add_argument('--keep-html', action='store_true', help='keep the result HTML in the ignored cache for evidence')
    parser.add_argument('--no-proxy', action='store_true', help='ignore HTTP(S)_PROXY environment settings')
    parser.add_argument('--pretty', action='store_true', help='indent the JSON output')
    parser.add_argument('--summary', action='store_true', help='print a compact summary instead of the full result')
    args = parser.parse_args(argv)

    if not args.host or re.search(r'[^a-z0-9.-]', args.host.lower()):
        print('[%s] invalid --host' % TOOL, file=sys.stderr)
        return EXIT_USAGE
    try:
        code, result = run(args)
    except (ValueError, OSError) as exc:
        print('[%s] %s' % (TOOL, exc), file=sys.stderr)
        return EXIT_USAGE
    payload = {
        'status': result['status'],
        'sites': len(result['sites']),
        'similar': len(result['similar']),
        'topSites': [item.get('title', '') for item in result['sites'][:6]],
        'notes': result.get('notes', []),
        'error': result.get('errorCode'),
    } if args.summary else result
    text = json.dumps(payload, ensure_ascii=False, indent=2 if args.pretty else None,
                      separators=None if args.pretty else (',', ':'))
    if args.out:
        out_path = Path(args.out).expanduser()
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
        print(text)
    else:
        print(text)
    if result['status'] != 'ok':
        print('[%s] status=%s code=%s' % (TOOL, result['status'], result.get('errorCode', '')), file=sys.stderr)
    return code if result['status'] == 'ok' else EXIT_PARTIAL


if __name__ == '__main__':
    raise SystemExit(main())