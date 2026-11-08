#!/usr/bin/env python3
"""Trimmed FxTwitter probe for a public X account or post.

Usage:
  python -X utf8 tools/research_kit/fxtwitter_probe.py @handle
  python -X utf8 tools/research_kit/fxtwitter_probe.py https://x.com/<user>/status/<id>
  python -X utf8 tools/research_kit/fxtwitter_probe.py <numeric-post-id> --kind post

Only the public FxTwitter API (api.fxtwitter.com) is called. Output is a small
JSON object with nick/handle/numeric id/bio/avatar url/links, or post author and
short text. Fields are clipped so a probe costs a few hundred tokens, never a
full page. The numeric id and preservation rules are documented in
tools/AUTHOR_RESEARCH.md: a handle can be renamed or reused, so keep the id and
the query date with every conclusion.

Exit codes: 0 ok; 1 not_found/blocked/rate_limited/timeout/error; 2 usage.
"""

from __future__ import annotations

import argparse
import hashlib
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

TOOL = 'fxtwitter_probe'
VERSION = '1.0.0'
EXIT_OK, EXIT_PARTIAL, EXIT_USAGE = 0, 1, 2
UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36')
MAX_BODY = 2 * 1024 * 1024
NON_HANDLE_PATHS = {
    'i', 'intent', 'share', 'home', 'search', 'hashtag', 'settings', 'messages',
    'notifications', 'explore', 'login', 'logout', 'signup', 'tos', 'privacy',
    'about', 'download', 'compose', 'account', 'jobs',
}


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


def fetch_json(url: str, timeout: float, no_proxy: bool) -> dict:
    request = urllib.request.Request(url, headers={
        'User-Agent': UA,
        'Accept': 'application/json',
    })
    opener = build_opener(no_proxy)
    try:
        with opener.open(request, timeout=timeout) as response:
            body = response.read(MAX_BODY + 1)
            if len(body) > MAX_BODY:
                raise FetchError('error', 'body_too_large')
            try:
                payload = json.loads(body.decode('utf-8', 'ignore'))
            except ValueError:
                raise FetchError('error', 'invalid_json') from None
            if not isinstance(payload, dict):
                raise FetchError('error', 'invalid_shape')
            return payload
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


def normalize_target(raw: str, kind: str) -> tuple[str, str]:
    target = str(raw or '').strip()
    if not target:
        raise ValueError('target is empty')
    status_match = re.search(r'(?:^|/)status(?:es)?/(\d{5,25})', target)
    if status_match:
        return 'post', status_match.group(1)
    if re.fullmatch(r'\d{5,25}', target):
        if kind == 'author':
            raise ValueError('numeric target looks like a post id, not a handle')
        return 'post', target
    if target.startswith('http://') or target.startswith('https://'):
        parts = urllib.parse.urlsplit(target)
        host = parts.hostname.lower() if parts.hostname else ''
        if host not in ('x.com', 'twitter.com', 'www.x.com', 'www.twitter.com', 'mobile.x.com'):
            raise ValueError('only public x.com/twitter.com links are accepted')
        segments = [segment for segment in parts.path.split('/') if segment]
        if not segments:
            raise ValueError('link has no handle')
        handle = segments[0]
        if handle.lower() in NON_HANDLE_PATHS or handle.startswith('#'):
            raise ValueError('link is not an account profile or post')
        if kind == 'post':
            raise ValueError('post kind requires /status/<id> in the link')
        return 'author', handle
    handle = target.lstrip('@').strip()
    if not re.fullmatch(r'[A-Za-z0-9_]{1,15}', handle):
        raise ValueError('handle must be 1-15 letters/digits/underscore')
    if kind == 'post':
        raise ValueError('post kind requires a numeric id or a /status/ link')
    return 'author', handle


def resolve_kind(raw: str, kind: str) -> tuple[str, str]:
    if kind == 'auto':
        status_match = re.search(r'(?:^|/)status(?:es)?/(\d{5,25})', str(raw or ''))
        if status_match or re.fullmatch(r'\d{5,25}', str(raw or '').strip()):
            return 'post', ''
        return 'author', ''
    return kind, ''


def website_url(value) -> str:
    if isinstance(value, dict):
        value = value.get('url') or value.get('expanded_url') or ''
    return clip(value, 300) if isinstance(value, str) else ''


def build_links(website: str, profile: str) -> list[dict]:
    links = []
    seen = set()
    for label, url in (('profile', profile), ('website', website)):
        if not url or not url.lower().startswith('https://') or url in seen:
            continue
        seen.add(url)
        links.append({'label': label, 'url': url})
    return links[:3]


def parse_author(payload: dict) -> dict:
    user = payload.get('user') if isinstance(payload, dict) else None
    if not isinstance(user, dict):
        return {}
    verification = user.get('verification') if isinstance(user.get('verification'), dict) else {}
    handle = clip(user.get('screen_name'), 32)
    website = website_url(user.get('website'))
    profile = 'https://x.com/' + handle if handle else ''
    return {
        'kind': 'author',
        'handle': handle,
        'name': clip(user.get('name'), 100),
        'id': clip(user.get('id'), 40),
        'bio': clip(user.get('description'), 500),
        'avatarUrl': clip(user.get('avatar_url'), 400),
        'bannerUrl': clip(user.get('banner_url'), 400),
        'website': website,
        'location': clip(user.get('location'), 100),
        'joined': clip(user.get('joined'), 40),
        'followers': user.get('followers') if isinstance(user.get('followers'), int) else None,
        'following': user.get('following') if isinstance(user.get('following'), int) else None,
        'tweets': user.get('tweets') if isinstance(user.get('tweets'), int) else None,
        'verified': bool(verification.get('verified')),
        'links': build_links(website, profile),
    }


def parse_post(payload: dict) -> dict:
    tweet = payload.get('tweet') if isinstance(payload, dict) else None
    if not isinstance(tweet, dict):
        return {}
    author = tweet.get('author') if isinstance(tweet.get('author'), dict) else {}
    media = tweet.get('media') if isinstance(tweet.get('media'), dict) else {}
    photos = media.get('photos') if isinstance(media.get('photos'), list) else []
    return {
        'kind': 'post',
        'id': clip(tweet.get('id'), 40),
        'url': clip(tweet.get('url'), 300),
        'createdAt': clip(tweet.get('created_at'), 60),
        'text': clip(tweet.get('text'), 600),
        'authorHandle': clip(author.get('screen_name'), 32),
        'authorName': clip(author.get('name'), 100),
        'authorId': clip(author.get('id'), 40),
        'photos': len(photos),
        'likes': tweet.get('likes') if isinstance(tweet.get('likes'), int) else None,
        'reposts': tweet.get('reposts') if isinstance(tweet.get('reposts'), int) else None,
        'replies': tweet.get('replies') if isinstance(tweet.get('replies'), int) else None,
    }


def run(args) -> int:
    kind_hint, _ = resolve_kind(args.target, args.kind)
    kind, value = normalize_target(args.target, kind_hint)
    cache_root = (Path(args.cache_dir).expanduser().resolve() if args.cache_dir else default_cache_dir()) / TOOL
    cache_root.mkdir(parents=True, exist_ok=True)
    key = kind + '-' + value.lower()
    cache_file = cache_root / (hashlib.sha256(key.encode('utf-8')).hexdigest()[:32] + '.json')

    result = {'tool': TOOL, 'version': VERSION, 'status': 'error', 'fetchedAt': now_iso()}
    if not args.refresh:
        cached = read_json(cache_file)
        if isinstance(cached, dict) and cached.get('status') == 'ok':
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
                emit(result, args)
                return EXIT_OK

    url = 'https://api.fxtwitter.com/' + ('status/' + value if kind == 'post' else urllib.parse.quote(value))
    try:
        payload = fetch_json(url, args.timeout, args.no_proxy)
    except FetchError as exc:
        result['status'] = exc.status
        result['errorCode'] = exc.code
        if exc.detail:
            result['note'] = clip(exc.detail, 120)
        emit(result, args)
        return EXIT_PARTIAL

    code = payload.get('code')
    if isinstance(code, int) and code == 404:
        result['status'] = 'not_found'
        result['errorCode'] = 'body_404'
        emit(result, args)
        return EXIT_PARTIAL
    parsed = parse_post(payload) if kind == 'post' else parse_author(payload)
    if not parsed:
        result['status'] = 'error'
        result['errorCode'] = 'missing_object'
        emit(result, args)
        return EXIT_PARTIAL
    result.update(parsed)
    result['status'] = 'ok'
    write_json_atomic(cache_file, result)
    emit(result, args)
    return EXIT_OK


def emit(result: dict, args) -> None:
    if args.summary:
        if result.get('kind') == 'post':
            payload = {key: result.get(key) for key in
                       ('status', 'kind', 'id', 'authorHandle', 'createdAt', 'photos')}
        else:
            payload = {key: result.get(key) for key in
                       ('status', 'kind', 'handle', 'name', 'id', 'followers', 'tweets')}
        payload['links'] = len(result.get('links', []))
    else:
        payload = result
    text = json.dumps(payload, ensure_ascii=False, indent=2 if args.pretty else None,
                      separators=None if args.pretty else (',', ':'))
    if args.out:
        out_path = Path(args.out).expanduser()
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding='utf-8')
    print(text)
    if result['status'] != 'ok':
        print('[%s] status=%s code=%s' % (TOOL, result['status'], result.get('errorCode', '')), file=sys.stderr)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog='fxtwitter_probe.py',
        description='Trimmed public FxTwitter probe for an X account or post.',
    )
    parser.add_argument('target', help='handle (@name), profile URL, post id, or /status/ URL')
    parser.add_argument('--kind', choices=('auto', 'author', 'post'), default='auto')
    parser.add_argument('--out', help='write the full JSON result here')
    parser.add_argument('--cache-dir', help='cache root; default $QIMENG_RESEARCH_KIT_CACHE')
    parser.add_argument('--max-age-hours', type=int, default=24, help='cache TTL hours (default: 24)')
    parser.add_argument('--timeout', type=float, default=20.0, help='request timeout seconds (default: 20)')
    parser.add_argument('--refresh', action='store_true', help='ignore the cached result')
    parser.add_argument('--no-proxy', action='store_true', help='ignore HTTP(S)_PROXY environment settings')
    parser.add_argument('--pretty', action='store_true', help='indent the JSON output')
    parser.add_argument('--summary', action='store_true', help='print only key fields')
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