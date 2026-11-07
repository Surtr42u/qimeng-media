#!/usr/bin/env python3
"""Download public avatar images, validate them and write a JSONL manifest.

Usage:
  python -X utf8 tools/research_kit/avatar_fetch.py --jobs avatars.jsonl
  python -X utf8 tools/research_kit/avatar_fetch.py --url https://pbs.twimg.com/profile_images/.../x_normal.jpg

Input jobs (JSONL): {"id":"author-001","url":"https://...","filename":"optional"}

Behaviour:
- The source URL is normalized: X/Twitter pbs.twimg.com suffixes _normal,
  _bigger, _mini and _reasonably_small become _400x400 (and ?name=400x400).
- Only https is fetched; literal loopback/private hosts and non-image content are
  rejected. The download is capped (default 5 MiB), its magic bytes and pixel
  dimensions are validated, and SHA-256 is recorded.
- Optional --to-jpeg converts with ffmpeg (external tool, no Pillow).
- No paid content is accessed: this tool only fetches the explicit avatar URL it
  is given and never follows profile links or paywalled pages.

Exit codes: 0 ok; 1 some jobs failed; 2 usage or missing ffmpeg with --to-jpeg.
"""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
import re
import shutil
import socket
import ssl
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

TOOL = 'avatar_fetch'
VERSION = '1.0.0'
EXIT_OK, EXIT_PARTIAL, EXIT_USAGE = 0, 1, 2
UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36')
MAX_BYTES = 5 * 1024 * 1024
X_SUFFIX = re.compile(r'_(normal|bigger|mini|reasonably_small)\b', re.I)
ALLOWED_FORMATS = {'jpeg': '.jpg', 'png': '.png', 'gif': '.gif', 'webp': '.webp'}


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


def safe_id(raw: str, fallback: str) -> str:
    value = re.sub(r'[^A-Za-z0-9._-]+', '-', str(raw or '')).strip('-._')
    return clip(value or fallback, 80)


def normalize_avatar_url(raw: str) -> str:
    target = str(raw or '').strip()
    if not target:
        raise ValueError('empty avatar url')
    parts = urllib.parse.urlsplit(target)
    if parts.scheme != 'https':
        raise ValueError('avatar url must be https')
    if not parts.hostname:
        raise ValueError('avatar url has no host')
    host = parts.hostname.lower()
    path = parts.path
    if host == 'pbs.twimg.com':
        path = X_SUFFIX.sub('_400x400', path)
        query = urllib.parse.parse_qsl(parts.query, keep_blank_values=True)
        if any(key == 'name' for key, _ in query):
            query = [(key, '400x400' if key == 'name' else value) for key, value in query]
        parts = parts._replace(path=path, query=urllib.parse.urlencode(query))
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, parts.path, parts.query, ''))


def host_is_public(hostname: str) -> bool:
    host = hostname.lower().rstrip('.')
    if not host or host == 'localhost' or host.endswith('.local') or host.endswith('.internal'):
        return False
    if host.endswith('.localhost'):
        return False
    try:
        address = ipaddress.ip_address(host)
        return address.is_global and not address.is_multicast
    except ValueError:
        return True


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
            host = urllib.parse.urlsplit(str(newurl)).hostname or ''
            if not host_is_public(host):
                raise FetchError('blocked', 'redirect_private_host')
            return super().redirect_request(req, fp, code, msg, headers, newurl)

    handlers.append(HttpsRedirect())
    return urllib.request.build_opener(*handlers)


def download(url: str, max_bytes: int, timeout: float, no_proxy: bool) -> tuple[bytes, str, dict]:
    request = urllib.request.Request(url, headers={
        'User-Agent': UA,
        'Accept': 'image/avif,image/webp,image/png,image/jpeg,image/gif;q=0.9,*/*;q=0.5',
    })
    opener = build_opener(no_proxy)
    try:
        with opener.open(request, timeout=timeout) as response:
            body = response.read(max_bytes + 1)
            if len(body) > max_bytes:
                raise FetchError('error', 'body_too_large')
            return body, response.geturl(), dict(response.headers)
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


def sniff_format(data: bytes) -> str:
    if data[:3] == b'\xff\xd8\xff':
        return 'jpeg'
    if data[:8] == b'\x89PNG\r\n\x1a\n':
        return 'png'
    if data[:6] in (b'GIF87a', b'GIF89a'):
        return 'gif'
    if data[:4] == b'RIFF' and data[8:12] == b'WEBP':
        return 'webp'
    return ''


def image_size(data: bytes) -> tuple[int | None, int | None]:
    if data[:8] == b'\x89PNG\r\n\x1a\n' and len(data) >= 24:
        return struct.unpack('>II', data[16:24])
    if data[:6] in (b'GIF87a', b'GIF89a') and len(data) >= 10:
        return struct.unpack('<HH', data[6:10])
    if data[:4] == b'RIFF' and data[8:12] == b'WEBP' and len(data) >= 30:
        kind = data[12:16]
        if kind == b'VP8X':
            return (1 + int.from_bytes(data[24:27], 'little'),
                    1 + int.from_bytes(data[27:30], 'little'))
        if kind == b'VP8 ':
            width = struct.unpack('<H', data[26:28])[0] & 0x3fff
            height = struct.unpack('<H', data[28:30])[0] & 0x3fff
            return (width, height) if width and height else (None, None)
        if kind == b'VP8L' and len(data) >= 25 and data[20] == 0x2f:
            bits = int.from_bytes(data[21:25], 'little')
            width = (bits & 0x3fff) + 1
            height = ((bits >> 14) & 0x3fff) + 1
            return width, height
    if data[:2] == b'\xff\xd8':
        offset = 2
        while offset + 9 < len(data):
            if data[offset] != 0xff:
                offset += 1
                continue
            marker = data[offset + 1]
            if marker in (0xd8, 0x01) or 0xd0 <= marker <= 0xd7:
                offset += 2
                continue
            if marker in (0xd9, 0xda):
                break
            length = struct.unpack('>H', data[offset + 2:offset + 4])[0]
            if length < 2:
                break
            if 0xc0 <= marker <= 0xcf and marker not in (0xc4, 0xc8, 0xcc):
                height, width = struct.unpack('>HH', data[offset + 5:offset + 9])
                return width, height
            offset += 2 + length
    return None, None


def find_tool(explicit: str | None, env_name: str, name: str) -> str:
    candidate = (explicit or os.environ.get(env_name, '')).strip() or name
    if Path(candidate).is_file():
        return str(Path(candidate).resolve())
    found = shutil.which(candidate)
    return found or ''


def convert_to_jpeg(ffmpeg: str, source: Path, target: Path, max_width: int) -> tuple[bool, str]:
    command = [
        ffmpeg, '-hide_banner', '-nostdin', '-v', 'error', '-y', '-i', str(source),
        '-frames:v', '1', '-vf', 'scale=min(%d\\,iw):-2' % max_width, '-q:v', '3', str(target),
    ]
    try:
        completed = subprocess.run(command, capture_output=True, timeout=60, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return False, 'ffmpeg_failed'
    if completed.returncode != 0 or not target.is_file() or target.stat().st_size == 0:
        return False, 'ffmpeg_failed'
    return True, ''


def read_jobs(args) -> list[dict]:
    jobs = []
    if args.jobs:
        path = Path(args.jobs).expanduser()
        try:
            text = path.read_text(encoding='utf-8-sig')
        except OSError:
            raise ValueError('cannot read jobs file')
        for raw in text.splitlines():
            line = raw.strip()
            if not line or line.startswith('#'):
                continue
            item = json.loads(line)
            if not isinstance(item, dict) or not item.get('url'):
                raise ValueError('each job needs url')
            job_id = safe_id(str(item.get('id') or ''), 'job-%02d' % (len(jobs) + 1))
            jobs.append({'id': job_id, 'url': str(item['url']), 'filename': item.get('filename')})
    for index, url in enumerate(args.url or []):
        jobs.append({'id': 'avatar-%02d' % (index + 1), 'url': url, 'filename': None})
    if not jobs:
        raise ValueError('provide --jobs or --url')
    seen = set()
    for job in jobs:
        if job['id'] in seen:
            raise ValueError('duplicate job id: ' + job['id'])
        seen.add(job['id'])
    return jobs


def existing_ids(manifest: Path, retry_errors: bool) -> set[str]:
    done = set()
    if not manifest.is_file():
        return done
    for raw in manifest.read_text(encoding='utf-8-sig').splitlines():
        try:
            item = json.loads(raw)
        except ValueError:
            continue
        if not isinstance(item, dict) or 'id' not in item:
            continue
        if retry_errors and item.get('status') != 'ok':
            continue
        done.add(str(item['id']))
    return done


def run(args) -> int:
    out_dir = (Path(args.out_dir).expanduser().resolve() if args.out_dir
               else default_cache_dir() / 'avatars')
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest = (Path(args.manifest).expanduser() if args.manifest else out_dir / 'manifest.jsonl')
    manifest.parent.mkdir(parents=True, exist_ok=True)

    ffmpeg = ''
    if args.to_jpeg:
        ffmpeg = find_tool(args.ffmpeg, 'QIMENG_RESEARCH_KIT_FFMPEG', 'ffmpeg')
        if not ffmpeg:
            print('[%s] --to-jpeg needs ffmpeg; set QIMENG_RESEARCH_KIT_FFMPEG or --ffmpeg' % TOOL, file=sys.stderr)
            return EXIT_USAGE

    try:
        jobs = read_jobs(args)
    except ValueError as exc:
        print('[%s] %s' % (TOOL, exc), file=sys.stderr)
        return EXIT_USAGE
    done = existing_ids(manifest, args.retry_errors)
    pending = [job for job in jobs if job['id'] not in done]
    if args.limit > 0:
        pending = pending[:args.limit]

    summary = {
        'tool': TOOL,
        'version': VERSION,
        'status': 'ok',
        'jobs': len(jobs),
        'skippedExisting': len(jobs) - len(pending),
        'pending': len(pending),
        'ok': 0,
        'failed': 0,
        'blocked': 0,
        'notFound': 0,
        'rateLimited': 0,
        'skipped': 0,
        'manifest': display_path(manifest),
        'outDir': display_path(out_dir),
    }

    for job in pending:
        record = {
            'id': job['id'],
            'status': 'error',
            'sourceUrl': clipped_url(job['url']),
            'finalUrl': '',
            'format': '',
            'width': None,
            'height': None,
            'bytes': 0,
            'sha256': '',
            'savedAs': '',
            'ts': now_iso(),
        }
        try:
            normalized = normalize_avatar_url(job['url'])
            record['sourceUrl'] = clipped_url(normalized)
            host = urllib.parse.urlsplit(normalized).hostname or ''
            if not host_is_public(host):
                raise FetchError('blocked', 'private_host')
            data, final_url, headers = download(normalized, args.max_bytes, args.timeout, args.no_proxy)
            record['finalUrl'] = clipped_url(final_url)
            record['bytes'] = len(data)
            record['sha256'] = hashlib.sha256(data).hexdigest()
            fmt = sniff_format(data)
            if not fmt:
                raise FetchError('error', 'unsupported_format')
            width, height = image_size(data)
            if width is None or height is None:
                raise FetchError('error', 'dimension_unreadable')
            if width < args.min_dim or height < args.min_dim:
                raise FetchError('error', 'dimension_too_small')
            if width > args.max_dim or height > args.max_dim:
                raise FetchError('error', 'dimension_too_large')
            record['format'] = fmt
            record['width'] = width
            record['height'] = height
            extension = ALLOWED_FORMATS[fmt]
            base = safe_id(job.get('filename') or job['id'], 'avatar')
            saved_name = base + '-' + record['sha256'][:8] + extension
            saved_path = out_dir / saved_name
            saved_path.write_bytes(data)
            try:
                os.chmod(saved_path, 0o600)
            except OSError:
                pass
            record['savedAs'] = saved_name
            if args.to_jpeg:
                jpeg_name = base + '-' + record['sha256'][:8] + '.jpg'
                jpeg_path = out_dir / jpeg_name
                ok, code = convert_to_jpeg(ffmpeg, saved_path, jpeg_path, args.jpeg_max_width)
                if ok:
                    record['jpeg'] = jpeg_name
                    record['jpegBytes'] = jpeg_path.stat().st_size
                else:
                    record['jpegError'] = code
            record['status'] = 'ok'
        except FetchError as exc:
            record['status'] = exc.status
            record['errorCode'] = exc.code
            if exc.detail:
                record['note'] = clip(exc.detail, 100)
        except ValueError as exc:
            record['status'] = 'blocked'
            record['errorCode'] = 'url_invalid'
            record['note'] = clip(str(exc), 100)
        except OSError as exc:
            record['status'] = 'error'
            record['errorCode'] = 'file_write_failed'
            record['note'] = clip(exc.strerror or '', 80)

        with manifest.open('a', encoding='utf-8') as handle:
            handle.write(json.dumps(record, ensure_ascii=False) + '\n')
        if record['status'] == 'ok':
            summary['ok'] += 1
        elif record['status'] in ('timeout', 'error'):
            summary['failed'] += 1
        elif record['status'] == 'blocked':
            summary['blocked'] += 1
        elif record['status'] == 'not_found':
            summary['notFound'] += 1
        elif record['status'] == 'rate_limited':
            summary['rateLimited'] += 1
        else:
            summary['skipped'] += 1
        print('[%s] %s status=%s %s' % (TOOL, record['id'], record['status'],
                                        record.get('errorCode', '') or record.get('savedAs', '')), file=sys.stderr)

    if summary['failed'] or summary['blocked'] or summary['notFound'] or summary['rateLimited'] or summary['skipped']:
        summary['status'] = 'partial'
    print(json.dumps(summary, ensure_ascii=False, separators=(',', ':')))
    return EXIT_OK if summary['status'] == 'ok' else EXIT_PARTIAL


def clipped_url(raw: str) -> str:
    return clip(raw, 400)


def display_path(value: Path) -> str:
    try:
        return str(Path(value).resolve().relative_to(Path.cwd().resolve()))
    except (ValueError, OSError):
        return str(value)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog='avatar_fetch.py',
        description='Download and validate public avatar images into an ignored directory.',
    )
    parser.add_argument('--jobs', help='jobs JSONL: {"id":"...","url":"https://..."}')
    parser.add_argument('--url', action='append', help='single avatar URL (repeatable, no jobs file)')
    parser.add_argument('--out-dir', help='output dir; default <cache>/avatars')
    parser.add_argument('--manifest', help='manifest JSONL; default <out-dir>/manifest.jsonl')
    parser.add_argument('--cache-dir', help='cache root; default $QIMENG_RESEARCH_KIT_CACHE')
    parser.add_argument('--max-bytes', type=int, default=MAX_BYTES, help='max download bytes (default: 5 MiB)')
    parser.add_argument('--min-dim', type=int, default=16, help='minimum pixel dimension (default: 16)')
    parser.add_argument('--max-dim', type=int, default=4096, help='maximum pixel dimension (default: 4096)')
    parser.add_argument('--to-jpeg', action='store_true', help='also convert to JPEG with ffmpeg')
    parser.add_argument('--jpeg-max-width', type=int, default=1024, help='JPEG max width (default: 1024)')
    parser.add_argument('--ffmpeg', help='ffmpeg path (default $QIMENG_RESEARCH_KIT_FFMPEG or PATH)')
    parser.add_argument('--timeout', type=float, default=20.0, help='download timeout seconds (default: 20)')
    parser.add_argument('--limit', type=int, default=0, help='process at most N pending jobs (0 = all)')
    parser.add_argument('--retry-errors', action='store_true', help='retry ids whose recorded status was not ok')
    parser.add_argument('--no-proxy', action='store_true', help='ignore HTTP(S)_PROXY environment settings')
    args = parser.parse_args(argv)
    if args.min_dim < 1 or args.max_dim < args.min_dim:
        print('[%s] invalid dimension bounds' % TOOL, file=sys.stderr)
        return EXIT_USAGE
    if args.max_bytes < 1024 or args.max_bytes > 64 * 1024 * 1024:
        print('[%s] --max-bytes must be 1 KiB..64 MiB' % TOOL, file=sys.stderr)
        return EXIT_USAGE
    try:
        return run(args)
    except OSError as exc:
        print('[%s] file error: %s' % (TOOL, exc.strerror or exc), file=sys.stderr)
        return EXIT_USAGE


if __name__ == '__main__':
    raise SystemExit(main())