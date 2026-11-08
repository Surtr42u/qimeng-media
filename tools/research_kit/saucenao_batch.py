#!/usr/bin/env python3
"""SauceNAO batch reverse-image search with a small, bounded summary per image.

Usage:
  python -X utf8 tools/research_kit/saucenao_batch.py --jobs jobs.jsonl [--out results.jsonl]

The API key comes from the environment (default QIMENG_RESEARCH_KIT_SAUCENAO_KEY)
or an ignored key file, never from the repository. The free SauceNAO tier allows
4 requests per 30 seconds and a daily cap; this tool enforces a cross-invocation
rate limiter and a daily counter in the ignored cache directory, and stops on
401/403/429 instead of retrying in a loop.

Output is JSONL, one compact object per job: similarity/creator/source/title and
the best index name. No HTML and no full result payload is written. Raw responses
are not persisted by default.

Exit codes: 0 ok; 1 partial/blocked/rate_limited/timeout/error; 2 usage/environment.
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

TOOL = 'saucenao_batch'
VERSION = '1.0.0'
EXIT_OK, EXIT_PARTIAL, EXIT_USAGE = 0, 1, 2
UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36')
MAX_BODY = 2 * 1024 * 1024
MAX_FILE_BYTES = 20 * 1024 * 1024
ALLOWED_EXT = {'.jpg', '.jpeg', '.png', '.webp', '.gif', '.bmp'}
API_URL = 'https://saucenao.com/search.php'


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


def utc_day() -> str:
    return time.strftime('%Y-%m-%d', time.gmtime())


def clip(value, limit: int) -> str:
    return re.sub(r'\s+', ' ', str(value or '')).strip()[:limit]


def display_path(value) -> str:
    try:
        return str(Path(value).resolve().relative_to(Path.cwd().resolve()))
    except (ValueError, OSError):
        return str(value)


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
                timeout: float = 45.0, max_bytes: int = MAX_BODY,
                no_proxy: bool = False) -> tuple[bytes, int, dict]:
    request_headers = {'User-Agent': UA, 'Accept': 'application/json, text/plain, */*'}
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


class DailyBudget:
    def __init__(self, path: Path, limit: int, label: str):
        self.path = path
        self.limit = max(0, int(limit))
        self.label = label
        self.used = 0
        raw = read_json(path)
        if isinstance(raw, dict) and raw.get('date') == utc_day():
            self.used = int(raw.get('count') or 0)

    def remaining(self) -> int:
        if self.limit <= 0:
            return 10 ** 9
        return max(0, self.limit - self.used)

    def consume(self) -> None:
        self.used += 1
        write_json_atomic(self.path, {'date': utc_day(), 'count': self.used})


def build_multipart(fields: list[tuple[str, str]], file_field: str, filename: str,
                    content: bytes, mime: str) -> tuple[bytes, str]:
    boundary = '----ResearchKit' + uuid.uuid4().hex
    parts = []
    for name, value in fields:
        parts.append(('--' + boundary + '\r\n').encode('utf-8'))
        parts.append(('Content-Disposition: form-data; name="' + name + '"\r\n\r\n').encode('utf-8'))
        parts.append(value.encode('utf-8') + b'\r\n')
    parts.append(('--' + boundary + '\r\n').encode('utf-8'))
    parts.append(('Content-Disposition: form-data; name="' + file_field + '"; filename="' + filename + '"\r\n').encode('utf-8'))
    parts.append(('Content-Type: ' + mime + '\r\n\r\n').encode('utf-8'))
    parts.append(content + b'\r\n')
    parts.append(('--' + boundary + '--\r\n').encode('utf-8'))
    return b''.join(parts), boundary


def guess_mime(path: Path, data: bytes) -> str:
    if data[:3] == b'\xff\xd8\xff':
        return 'image/jpeg'
    if data[:8] == b'\x89PNG\r\n\x1a\n':
        return 'image/png'
    if data[:4] == b'RIFF' and data[8:12] == b'WEBP':
        return 'image/webp'
    suffix = path.suffix.lower().lstrip('.')
    return {'jpg': 'image/jpeg', 'jpeg': 'image/jpeg', 'gif': 'image/gif',
            'bmp': 'image/bmp', 'webp': 'image/webp'}.get(suffix, 'image/jpeg')


def stringify(value, limit: int) -> str:
    if isinstance(value, list):
        value = value[0] if value else ''
    if isinstance(value, (int, float)):
        value = str(value)
    return clip(html.unescape(re.sub(r'<[^>]+>', '', str(value or ''))), limit)


def parse_response(payload: dict) -> tuple[str | None, dict | None, dict]:
    header = payload.get('header') if isinstance(payload, dict) else None
    if not isinstance(header, dict):
        return 'invalid_response', None, {}
    status = header.get('status')
    quota = {
        'shortRemaining': header.get('short_remaining'),
        'longRemaining': header.get('long_remaining'),
    }
    if isinstance(status, int) and status < 0:
        if status in (-1, -2):
            return ('blocked' if status == -1 else 'rate_limited'), None, quota
        return 'error', None, quota
    results = payload.get('results')
    if not isinstance(results, list) or not results:
        return 'no_match', None, quota
    best = results[0]
    if not isinstance(best, dict):
        return 'invalid_response', None, quota
    result_header = best.get('header') or {}
    data = best.get('data') or {}
    try:
        similarity = float(str(result_header.get('similarity', '0')).replace('%', '').strip())
    except (TypeError, ValueError):
        similarity = 0.0
    ext_urls = data.get('ext_urls') if isinstance(data.get('ext_urls'), list) else []
    source = stringify(ext_urls[0], 300) if ext_urls else stringify(data.get('source'), 300)
    best_result = {
        'similarity': round(similarity, 1),
        'creator': stringify(data.get('creator'), 120),
        'source': source,
        'title': stringify(data.get('title'), 150),
        'index': stringify(result_header.get('index_name'), 60),
        'extUrls': [stringify(item, 300) for item in ext_urls[:2] if item],
    }
    return None, best_result, quota


def read_jsonl(path: Path) -> tuple[list[dict], int]:
    items = []
    bad = 0
    text = path.read_text(encoding='utf-8-sig')
    seen = set()
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        try:
            item = json.loads(line)
        except ValueError:
            bad += 1
            continue
        if not isinstance(item, dict):
            bad += 1
            continue
        job_id = str(item.get('id', '')).strip()
        file_path = item.get('file')
        if not job_id or not isinstance(file_path, str) or not file_path.strip():
            bad += 1
            continue
        if job_id in seen:
            raise ValueError('duplicate job id: ' + job_id)
        seen.add(job_id)
        items.append({'id': job_id, 'file': file_path.strip()})
    return items, bad


def existing_ids(path: Path, retry_errors: bool) -> set[str]:
    done = set()
    if not path.is_file():
        return done
    for raw in path.read_text(encoding='utf-8-sig').splitlines():
        line = raw.strip()
        if not line:
            continue
        try:
            item = json.loads(line)
        except ValueError:
            continue
        if not isinstance(item, dict) or 'id' not in item:
            continue
        status = item.get('status')
        if retry_errors and status not in ('ok', 'low_confidence', 'no_match'):
            continue
        done.add(str(item['id']))
    return done


def load_api_key(env_name: str, key_file: str | None) -> str:
    key = os.environ.get(env_name, '').strip()
    if key:
        return key
    if key_file:
        try:
            return Path(key_file).expanduser().read_text(encoding='utf-8').strip()
        except OSError:
            return ''
    return ''


def run(args) -> int:
    cache_root = (Path(args.cache_dir).expanduser().resolve() if args.cache_dir else default_cache_dir()) / TOOL
    out_path = Path(args.out).expanduser() if args.out else cache_root / 'results.jsonl'
    out_path.parent.mkdir(parents=True, exist_ok=True)
    cache_root.mkdir(parents=True, exist_ok=True)

    api_key = load_api_key(args.api_key_env, args.api_key_file)
    if not api_key:
        print('[%s] missing API key: set %s (or --api-key-file with an ignored file)' % (TOOL, args.api_key_env),
              file=sys.stderr)
        return EXIT_USAGE

    jobs, bad = read_jsonl(Path(args.jobs).expanduser())
    if not jobs:
        print('[%s] jobs file contains no valid job' % TOOL, file=sys.stderr)
        return EXIT_USAGE
    done = existing_ids(out_path, args.retry_errors)
    pending = [job for job in jobs if job['id'] not in done]
    if args.limit > 0:
        pending = pending[:args.limit]

    limiter = FileRateLimiter(cache_root / 'rate.json', args.per_window, args.window_seconds, TOOL)
    budget = DailyBudget(cache_root / 'daily.json', args.daily_limit, TOOL)

    summary = {
        'tool': TOOL,
        'version': VERSION,
        'status': 'ok',
        'jobs': len(jobs),
        'badLines': bad,
        'skippedExisting': len(jobs) - len(pending),
        'pending': len(pending),
        'processed': 0,
        'ok': 0,
        'lowConfidence': 0,
        'noMatch': 0,
        'blocked': 0,
        'failed': 0,
        'skipped': 0,
        'remaining': 0,
        'out': display_path(out_path),
        'quota': {},
    }

    cache_dir = cache_root / 'cache'
    stop_reason = ''
    for index, job in enumerate(pending):
        if args.limit and summary['processed'] >= args.limit:
            break
        remaining_budget = budget.remaining()
        if remaining_budget <= 0:
            stop_reason = 'daily_budget'
            break
        image = Path(job['file']).expanduser()
        record = {
            'id': job['id'],
            'status': 'error',
            'similarity': None,
            'creator': '',
            'source': '',
            'title': '',
            'index': '',
            'ts': now_iso(),
        }
        if not image.is_file():
            record['status'] = 'skipped'
            record['errorCode'] = 'file_missing'
        elif image.stat().st_size <= 0 or image.stat().st_size > MAX_FILE_BYTES:
            record['status'] = 'skipped'
            record['errorCode'] = 'file_size'
        elif image.suffix.lower() not in ALLOWED_EXT:
            record['status'] = 'skipped'
            record['errorCode'] = 'unsupported_extension'
        if record['status'] == 'skipped':
            with out_path.open('a', encoding='utf-8') as handle:
                handle.write(json.dumps(record, ensure_ascii=False) + '\n')
            summary['skipped'] += 1
            summary['processed'] += 1
            continue

        data = image.read_bytes()
        image_sha = sha256_bytes(data)
        cache_key = image_sha + '-' + str(args.min_similarity) + '-' + str(args.numres)
        cache_file = cache_dir / (cache_key + '.json')
        cached = None if args.refresh else read_json(cache_file)
        if isinstance(cached, dict) and cached.get('status') in ('ok', 'low_confidence', 'no_match'):
            cached_at = cached.get('cachedAt') or ''
            fresh = True
            if cached_at and args.max_age_hours > 0:
                try:
                    stamp_fields = time.strptime(cached_at, '%Y-%m-%dT%H:%M:%SZ')
                    fresh = (time.time() - time.mktime(stamp_fields)) < args.max_age_hours * 3600
                except ValueError:
                    fresh = True
            if fresh:
                record.update({key: cached.get(key) for key in
                               ('status', 'similarity', 'creator', 'source', 'title', 'index', 'extUrls')})
                record.pop('extUrls', None)
                record['cacheHit'] = True
                with out_path.open('a', encoding='utf-8') as handle:
                    handle.write(json.dumps(record, ensure_ascii=False) + '\n')
                summary['processed'] += 1
                summary[{'ok': 'ok', 'low_confidence': 'lowConfidence', 'no_match': 'noMatch'}[record['status']]] += 1
                continue

        if index > 0:
            try:
                limiter.acquire()
            except FetchError:
                stop_reason = 'limiter'
                break
        budget.consume()
        filename = clip(image.name, 80) or 'image'
        body, boundary = build_multipart(
            [
                ('output_type', '2'),
                ('api_key', api_key),
                ('numres', str(args.numres)),
                ('db', str(args.db)),
                ('hide', ''),
            ],
            'file', filename, data, guess_mime(image, data),
        )
        try:
            response_bytes, _, _ = fetch_bytes(
                API_URL, data=body,
                headers={'Content-Type': 'multipart/form-data; boundary=' + boundary},
                timeout=args.timeout, no_proxy=args.no_proxy,
            )
            try:
                payload = json.loads(response_bytes.decode('utf-8', 'ignore'))
            except ValueError:
                raise FetchError('error', 'invalid_json')
            status, best, quota = parse_response(payload)
            summary['quota'] = {key: value for key, value in quota.items() if value is not None}
            if status == 'invalid_response':
                record['status'] = 'error'
                record['errorCode'] = 'invalid_response'
            elif status in ('blocked', 'rate_limited'):
                record['status'] = status
                record['errorCode'] = status
                summary['blocked'] += 1
                summary['processed'] += 1
                with out_path.open('a', encoding='utf-8') as handle:
                    handle.write(json.dumps(record, ensure_ascii=False) + '\n')
                stop_reason = status
                break
            elif best is None:
                record['status'] = 'no_match'
                record['errorCode'] = 'no_results'
            else:
                record.update({key: best.get(key) for key in ('similarity', 'creator', 'source', 'title', 'index')})
                record['extUrls'] = best.get('extUrls', [])[:2]
                if best['similarity'] >= args.min_similarity:
                    record['status'] = 'ok'
                else:
                    record['status'] = 'low_confidence'
                write_json_atomic(cache_file, {
                    'status': record['status'],
                    'similarity': record['similarity'],
                    'creator': record['creator'],
                    'source': record['source'],
                    'title': record['title'],
                    'index': record['index'],
                    'cachedAt': now_iso(),
                })
        except FetchError as exc:
            record['status'] = exc.status
            record['errorCode'] = exc.code
            if exc.status in ('blocked', 'rate_limited'):
                summary['blocked'] += 1
                summary['processed'] += 1
                with out_path.open('a', encoding='utf-8') as handle:
                    handle.write(json.dumps(record, ensure_ascii=False) + '\n')
                stop_reason = exc.status
                break
        summary['processed'] += 1
        if record['status'] == 'ok':
            summary['ok'] += 1
        elif record['status'] == 'low_confidence':
            summary['lowConfidence'] += 1
        elif record['status'] == 'no_match':
            summary['noMatch'] += 1
        elif record['status'] in ('timeout', 'error'):
            summary['failed'] += 1
        with out_path.open('a', encoding='utf-8') as handle:
            handle.write(json.dumps(record, ensure_ascii=False) + '\n')

    summary['remaining'] = max(0, len(pending) - summary['processed'])
    if stop_reason in ('blocked', 'rate_limited', 'daily_budget'):
        summary['status'] = stop_reason
    elif summary['failed'] > 0 or summary['remaining'] > 0:
        summary['status'] = 'partial'
    print(json.dumps(summary, ensure_ascii=False, separators=(',', ':')))
    if stop_reason == 'blocked':
        print('[%s] blocked by SauceNAO; stop calling this endpoint and check the key/quota.' % TOOL, file=sys.stderr)
    elif stop_reason == 'rate_limited':
        print('[%s] rate limited; wait at least 30 seconds before the next batch.' % TOOL, file=sys.stderr)
    elif stop_reason == 'daily_budget':
        print('[%s] daily budget exhausted; remaining jobs were left untouched for resume.' % TOOL, file=sys.stderr)
    if summary['status'] == 'ok':
        return EXIT_OK
    return EXIT_PARTIAL


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog='saucenao_batch.py',
        description='Batched SauceNAO lookup with 4/30s pacing and compact JSONL results.',
    )
    parser.add_argument('--jobs', required=True, help='jobs JSONL: {"id":"...","file":"..."}')
    parser.add_argument('--out', help='results JSONL; default <cache>/saucenao/results.jsonl')
    parser.add_argument('--cache-dir', help='cache root; default $QIMENG_RESEARCH_KIT_CACHE')
    parser.add_argument('--api-key-env', default='QIMENG_RESEARCH_KIT_SAUCENAO_KEY',
                        help='environment variable holding the key (default: QIMENG_RESEARCH_KIT_SAUCENAO_KEY)')
    parser.add_argument('--api-key-file', help='ignored file containing the key (used when the env var is empty)')
    parser.add_argument('--min-similarity', type=float, default=70.0, help='ok threshold percent (default: 70)')
    parser.add_argument('--numres', type=int, default=5, choices=range(1, 11), metavar='1..10')
    parser.add_argument('--db', type=int, default=999, help='SauceNAO db selector (default: 999 = all)')
    parser.add_argument('--per-window', type=int, default=4, help='requests per window (default: 4)')
    parser.add_argument('--window-seconds', type=float, default=30.0, help='window seconds (default: 30)')
    parser.add_argument('--daily-limit', type=int, default=90, help='hard daily request cap (default: 90)')
    parser.add_argument('--limit', type=int, default=0, help='process at most N pending jobs (0 = all)')
    parser.add_argument('--max-age-hours', type=int, default=720, help='cache TTL hours (default: 720)')
    parser.add_argument('--timeout', type=float, default=45.0, help='request timeout seconds (default: 45)')
    parser.add_argument('--retry-errors', action='store_true', help='retry previously failed ids')
    parser.add_argument('--refresh', action='store_true', help='ignore the cached result')
    parser.add_argument('--no-proxy', action='store_true', help='ignore HTTP(S)_PROXY environment settings')
    args = parser.parse_args(argv)
    try:
        return run(args)
    except ValueError as exc:
        print('[%s] %s' % (TOOL, exc), file=sys.stderr)
        return EXIT_USAGE
    except OSError as exc:
        print('[%s] file error: %s' % (TOOL, exc.strerror or exc), file=sys.stderr)
        return EXIT_USAGE


if __name__ == '__main__':
    raise SystemExit(main())