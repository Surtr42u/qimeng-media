#!/usr/bin/env python3
"""Kemono public API probe: creator and post metadata, trimmed to a small JSON.

Usage:
  python -X utf8 tools/research_kit/kemono_probe.py --service patreon --creator <id> --posts 10
  python -X utf8 tools/research_kit/kemono_probe.py --service fanbox --creator <id> --post <post-id>

Only the public Kemono API over https is used. Output keeps creator name/id,
post count, timestamps and per-post title/attachment counts; attachment paths and
file URLs are never printed. Optional --cdn-check sends one HEAD request to the
first attachment path to detect a TLS failure: such a failure is recorded as
blocked (tls_failed) and is NOT retried, per the research guidance.

Exit codes: 0 ok; 1 partial/not_found/blocked/rate_limited/timeout/error; 2 usage.
"""

from __future__ import annotations

import argparse
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
from pathlib import Path

TOOL = 'kemono_probe'
VERSION = '1.0.0'
EXIT_OK, EXIT_PARTIAL, EXIT_USAGE = 0, 1, 2
UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36')
MAX_BODY = 4 * 1024 * 1024
SERVICES = ('patreon', 'fanbox', 'pixiv', 'gumroad', 'subscribestar', 'fantia', 'boosty', 'dlsite')


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
    return re.sub(r'\s+', ' ', str(value or '')).strip()[:limit]


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


def fetch(url: str, *, timeout: float, no_proxy: bool, method: str = 'GET',
          max_bytes: int = MAX_BODY) -> tuple[bytes, int]:
    request = urllib.request.Request(url, headers={
        'User-Agent': UA,
        'Accept': 'application/json, */*',
    }, method=method)
    opener = build_opener(no_proxy)
    try:
        with opener.open(request, timeout=timeout) as response:
            body = b'' if method == 'HEAD' else response.read(max_bytes + 1)
            if len(body) > max_bytes:
                raise FetchError('error', 'body_too_large')
            return body, response.status
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


def fetch_json(url: str, timeout: float, no_proxy: bool):
    body, _ = fetch(url, timeout=timeout, no_proxy=no_proxy)
    try:
        return json.loads(body.decode('utf-8', 'ignore'))
    except ValueError:
        raise FetchError('error', 'invalid_json') from None


class FileRateLimiter:
    def __init__(self, path: Path, limit: int, window_seconds: float, label: str):
        self.path = path
        self.limit = max(0, int(limit))
        self.window = max(0.0, float(window_seconds))
        self.label = label

    def acquire(self) -> None:
        if self.limit <= 0 or self.window <= 0:
            return
        for _ in range(60):
            raw = read_json(self.path)
            stamps = [float(item) for item in raw if isinstance(item, (int, float))] if isinstance(raw, list) else []
            now = time.time()
            stamps = [stamp for stamp in stamps if now - stamp < self.window]
            if len(stamps) < self.limit:
                stamps.append(now)
                write_json_atomic(self.path, stamps)
                return
            delay = self.window - (now - min(stamps)) + 0.1
            if delay <= 0:
                continue
            time.sleep(delay)
        raise FetchError('rate_limited', 'limiter_stalled')


def parse_creator(payload) -> dict:
    if not isinstance(payload, dict):
        return {}
    return {
        'id': clip(payload.get('id'), 60),
        'name': clip(payload.get('name'), 120),
        'postCount': payload.get('post_count') if isinstance(payload.get('post_count'), int) else None,
        'indexed': clip(payload.get('indexed'), 40),
        'updated': clip(payload.get('updated'), 40),
        'favorited': payload.get('favorite_count') if isinstance(payload.get('favorite_count'), int) else None,
    }


def attachment_count(post: dict) -> int:
    attachments = post.get('attachments') if isinstance(post.get('attachments'), list) else []
    count = len(attachments)
    if isinstance(post.get('file'), dict) and post['file']:
        count += 1
    return count


def parse_post(post, *, detail: bool = False) -> dict:
    if not isinstance(post, dict):
        return {}
    item = {
        'id': clip(post.get('id'), 60),
        'title': clip(post.get('title'), 200),
        'published': clip(post.get('published'), 40),
        'edited': clip(post.get('edited'), 40),
        'attachments': attachment_count(post),
    }
    if detail:
        content = clip(post.get('content'), 300)
        if content:
            item['contentPreview'] = content
        item['hasFile'] = isinstance(post.get('file'), dict) and bool(post.get('file'))
        item['tags'] = [clip(tag, 40) for tag in (post.get('tags') or [])[:8] if tag]
    return item


def attachment_url(base: str, post: dict) -> str:
    """Build the first attachment CDN URL for a TLS probe; never returned to output."""
    candidates = []
    file_info = post.get('file') if isinstance(post.get('file'), dict) else {}
    if isinstance(file_info.get('path'), str):
        candidates.append(file_info['path'])
    for attachment in post.get('attachments') or []:
        if isinstance(attachment, dict) and isinstance(attachment.get('path'), str):
            candidates.append(attachment['path'])
    for raw in candidates:
        path = str(raw).strip()
        if not path:
            continue
        if path.startswith('http://') or path.startswith('https://'):
            parsed = urllib.parse.urlsplit(path)
            if parsed.scheme != 'https':
                continue
            return path
        normalized = path if path.startswith('/') else '/' + path
        if normalized.startswith('/data/'):
            return base + normalized
        return base.rstrip('/') + '/data' + normalized
    return ''


def run(args) -> int:
    cache_root = (Path(args.cache_dir).expanduser().resolve() if args.cache_dir else default_cache_dir()) / TOOL
    cache_root.mkdir(parents=True, exist_ok=True)
    key = '%s-%s-%s-%s' % (args.service, args.creator, args.posts, args.post or '')
    cache_file = cache_root / (re.sub(r'[^A-Za-z0-9._-]+', '_', key)[:120] + '.json')
    result = {
        'tool': TOOL,
        'version': VERSION,
        'status': 'error',
        'service': args.service,
        'creator': {},
        'posts': [],
        'fetchedAt': now_iso(),
        'notes': [],
    }

    if not args.refresh:
        cached = read_json(cache_file)
        if isinstance(cached, dict) and cached.get('status') in ('ok', 'partial'):
            cached_at = cached.get('fetchedAt') or ''
            fresh = True
            if cached_at and args.max_age_hours > 0:
                try:
                    fresh = (time.time() - time.mktime(time.strptime(cached_at, '%Y-%m-%dT%H:%M:%SZ'))) < args.max_age_hours * 3600
                except ValueError:
                    fresh = True
            if fresh:
                result.update(cached)
                result['cacheHit'] = True
                print(json.dumps(result, ensure_ascii=False, indent=2 if args.pretty else None,
                                 separators=None if args.pretty else (',', ':')))
                return EXIT_OK if result['status'] == 'ok' else EXIT_PARTIAL

    base = args.base.rstrip('/')
    limiter = FileRateLimiter(cache_root / 'rate.json', 1, args.min_interval_seconds, TOOL)
    partial = False
    stop_reason = ''

    creator_payload = None
    if not args.no_creator:
        try:
            limiter.acquire()
            creator_payload = fetch_json(base + '/api/v1/' + args.service + '/user/' + urllib.parse.quote(args.creator),
                                         args.timeout, args.no_proxy)
        except FetchError as exc:
            result['status'] = exc.status
            result['errorCode'] = exc.code
            result['notes'].append('creator_fetch_failed')
            print(json.dumps(result, ensure_ascii=False, indent=2 if args.pretty else None,
                             separators=None if args.pretty else (',', ':')))
            if exc.status == 'blocked':
                print('[%s] blocked while reading the creator; not retried.' % TOOL, file=sys.stderr)
            return EXIT_PARTIAL
        creator = parse_creator(creator_payload)
        if not creator:
            result['status'] = 'error'
            result['errorCode'] = 'invalid_creator_shape'
            print(json.dumps(result, ensure_ascii=False, indent=2 if args.pretty else None,
                             separators=None if args.pretty else (',', ':')))
            return EXIT_PARTIAL
        result['creator'] = creator
        result['creator']['url'] = base + '/' + args.service + '/user/' + urllib.parse.quote(args.creator)

    posts_payload = None
    if args.post:
        try:
            limiter.acquire()
            post_payload = fetch_json(base + '/api/v1/' + args.service + '/post/' + urllib.parse.quote(args.post),
                                      args.timeout, args.no_proxy)
            first = parse_post(post_payload, detail=True)
            if not first:
                raise FetchError('error', 'invalid_post_shape')
            result['posts'] = [first]
            if args.cdn_check:
                url = attachment_url(base, post_payload)
                if url:
                    try:
                        limiter.acquire()
                        fetch(url, timeout=args.timeout, no_proxy=args.no_proxy, method='HEAD', max_bytes=1024)
                        result['cdn'] = {'status': 'ok'}
                    except FetchError as exc:
                        result['cdn'] = {'status': 'blocked' if exc.status == 'blocked' else exc.status,
                                         'code': exc.code}
                        result['notes'].append('cdn_unreachable')
                        if exc.status == 'blocked':
                            stop_reason = 'blocked'
                        partial = True
        except FetchError as exc:
            result['status'] = exc.status
            result['errorCode'] = exc.code
            result['notes'].append('post_fetch_failed')
            print(json.dumps(result, ensure_ascii=False, indent=2 if args.pretty else None,
                             separators=None if args.pretty else (',', ':')))
            return EXIT_PARTIAL
    elif args.posts > 0:
        count = min(max(1, args.posts), 50)
        try:
            limiter.acquire()
            posts_payload = fetch_json(
                base + '/api/v1/' + args.service + '/user/' + urllib.parse.quote(args.creator)
                + '/posts?o=0&count=' + str(count),
                args.timeout, args.no_proxy,
            )
            if not isinstance(posts_payload, list):
                raise FetchError('error', 'invalid_posts_shape')
            result['posts'] = [item for item in (parse_post(post) for post in posts_payload[:count]) if item]
            if args.cdn_check:
                for post in posts_payload[:2]:
                    url = attachment_url(base, post) if isinstance(post, dict) else ''
                    if not url:
                        continue
                    try:
                        limiter.acquire()
                        fetch(url, timeout=args.timeout, no_proxy=args.no_proxy, method='HEAD', max_bytes=1024)
                        result['cdn'] = {'status': 'ok'}
                    except FetchError as exc:
                        result['cdn'] = {'status': 'blocked' if exc.status == 'blocked' else exc.status,
                                         'code': exc.code}
                        result['notes'].append('cdn_unreachable')
                        if exc.status == 'blocked':
                            stop_reason = 'blocked'
                        partial = True
                        break
        except FetchError as exc:
            result['status'] = exc.status
            result['errorCode'] = exc.code
            result['notes'].append('posts_fetch_failed')
            print(json.dumps(result, ensure_ascii=False, indent=2 if args.pretty else None,
                             separators=None if args.pretty else (',', ':')))
            return EXIT_PARTIAL

    result['status'] = 'partial' if partial else 'ok'
    if stop_reason == 'blocked':
        result['status'] = 'blocked'
    result['fetchedAt'] = now_iso()
    write_json_atomic(cache_file, result)
    print(json.dumps(result, ensure_ascii=False, indent=2 if args.pretty else None,
                     separators=None if args.pretty else (',', ':')))
    if result['status'] != 'ok':
        print('[%s] status=%s code=%s; CDN failures are recorded as blocked and not retried.'
              % (TOOL, result['status'], result.get('errorCode', '')), file=sys.stderr)
        return EXIT_PARTIAL
    return EXIT_OK


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog='kemono_probe.py',
        description='Kemono public API probe for creator and post metadata (trimmed JSON).',
    )
    parser.add_argument('--service', required=True, choices=SERVICES, help='Kemono service key')
    parser.add_argument('--creator', required=True, help='Kemono creator id for the service')
    parser.add_argument('--posts', type=int, default=10, help='recent posts to list, 0-50 (default: 10)')
    parser.add_argument('--post', help='single post id instead of a list')
    parser.add_argument('--base', default=os.environ.get('QIMENG_RESEARCH_KIT_KEMONO_BASE', 'https://kemono.su'),
                        help='Kemono base URL (https only)')
    parser.add_argument('--cdn-check', action='store_true',
                        help='HEAD the first attachment to detect TLS/CDN failure (single attempt)')
    parser.add_argument('--cache-dir', help='cache root; default $QIMENG_RESEARCH_KIT_CACHE')
    parser.add_argument('--max-age-hours', type=int, default=6, help='cache TTL hours (default: 6)')
    parser.add_argument('--min-interval-seconds', type=float, default=1.5,
                        help='minimum spacing between API requests (default: 1.5)')
    parser.add_argument('--timeout', type=float, default=20.0, help='request timeout seconds (default: 20)')
    parser.add_argument('--no-creator', action='store_true', help='skip the creator endpoint')
    parser.add_argument('--refresh', action='store_true', help='ignore the cached result')
    parser.add_argument('--no-proxy', action='store_true', help='ignore HTTP(S)_PROXY environment settings')
    parser.add_argument('--pretty', action='store_true', help='indent the JSON output')
    args = parser.parse_args(argv)
    if not args.base.startswith('https://'):
        print('[%s] --base must be https' % TOOL, file=sys.stderr)
        return EXIT_USAGE
    if args.posts < 0 or args.posts > 50:
        print('[%s] --posts must be 0-50' % TOOL, file=sys.stderr)
        return EXIT_USAGE
    try:
        return run(args)
    except OSError as exc:
        print('[%s] file error: %s' % (TOOL, exc.strerror or exc), file=sys.stderr)
        return EXIT_USAGE


if __name__ == '__main__':
    raise SystemExit(main())
