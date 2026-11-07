#!/usr/bin/env python3
"""Probe media container metadata, SHA-256 and review frames with ffprobe/ffmpeg.

Usage:
  python -X utf8 tools/research_kit/media_probe.py FILE [FILE ...] [--frames 15,50,85]
  python -X utf8 tools/research_kit/media_probe.py FILE --crops corner:br,face --out probe.jsonl

Python 3.13 standard library only; ffprobe/ffmpeg are external tools (no pip
packages). Output is a compact JSON/JSONL record with the fields that decide
ownership: ARTIST/comment/PURL/title, duration, resolution, fps and SHA-256.
Frames are written to an ignored directory as <=1600px WebP/JPEG for Lens; crops
are lossy heuristics for watermarks ("corner:tl/tr/bl/br") and a fixed
upper-middle "face" ROI (this is NOT face detection -- document it as a
heuristic when used as evidence).

Safety/privacy:
- File names are emitted as basename only unless --include-path is given.
- Frames may contain real media; keep the output directory ignored and never
  commit it.

Exit codes: 0 ok; 1 partial (probe ok but frames/metadata incomplete); 2 usage or
missing ffprobe/ffmpeg.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import time
from pathlib import Path

TOOL = 'media_probe'
VERSION = '1.0.0'
EXIT_OK, EXIT_PARTIAL, EXIT_USAGE = 0, 1, 2
TAG_KEYS = (
    'artist', 'album_artist', 'album', 'title', 'comment', 'description',
    'purl', 'date', 'creation_time', 'copyright', 'encoder', 'synopsis',
    'genre', 'composer', 'performer', 'track', 'disc',
)
CROPS = ('full', 'corner:tl', 'corner:tr', 'corner:bl', 'corner:br', 'face')


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


def display_path(value) -> str:
    try:
        return str(Path(value).resolve().relative_to(Path.cwd().resolve()))
    except (ValueError, OSError):
        return str(value)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def parse_rate(raw) -> float:
    try:
        text = str(raw or '').strip()
        if '/' in text:
            numerator, denominator = text.split('/', 1)
            if float(denominator) == 0:
                return 0.0
            return round(float(numerator) / float(denominator), 3)
        return round(float(text), 3)
    except (TypeError, ValueError):
        return 0.0


def image_size(path: Path) -> tuple[int | None, int | None]:
    try:
        with path.open('rb') as handle:
            head = handle.read(262144)
    except OSError:
        return None, None
    if head[:8] == b'\x89PNG\r\n\x1a\n' and len(head) >= 24:
        return struct.unpack('>II', head[16:24])
    if head[:4] == b'RIFF' and head[8:12] == b'WEBP' and len(head) >= 30:
        kind = head[12:16]
        if kind == b'VP8X':
            width = 1 + int.from_bytes(head[24:27], 'little')
            height = 1 + int.from_bytes(head[27:30], 'little')
            return width, height
        if kind == b'VP8 ':
            width = struct.unpack('<H', head[26:28])[0] & 0x3fff
            height = struct.unpack('<H', head[28:30])[0] & 0x3fff
            return (width, height) if width and height else (None, None)
    if head[:2] == b'\xff\xd8':
        offset = 2
        while offset + 9 < len(head):
            if head[offset] != 0xff:
                offset += 1
                continue
            marker = head[offset + 1]
            if marker in (0xd8, 0x01) or 0xd0 <= marker <= 0xd7:
                offset += 2
                continue
            if marker in (0xd9, 0xda):
                break
            length = struct.unpack('>H', head[offset + 2:offset + 4])[0]
            if length < 2:
                break
            if 0xc0 <= marker <= 0xcf and marker not in (0xc4, 0xc8, 0xcc):
                height, width = struct.unpack('>HH', head[offset + 5:offset + 9])
                return width, height
            offset += 2 + length
    return None, None


def find_tool(explicit: str | None, env_name: str, name: str) -> str:
    candidate = (explicit or os.environ.get(env_name, '')).strip() or name
    if Path(candidate).is_file():
        return str(Path(candidate).resolve())
    found = shutil.which(candidate)
    return found or ''


def run_probe(ffprobe: str, path: Path) -> tuple[dict | None, str]:
    command = [ffprobe, '-v', 'error', '-print_format', 'json', '-show_format', '-show_streams', str(path)]
    try:
        completed = subprocess.run(command, capture_output=True, timeout=60, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None, 'ffprobe_failed'
    if completed.returncode != 0:
        return None, 'ffprobe_failed'
    try:
        payload = json.loads(completed.stdout.decode('utf-8', 'ignore'))
    except ValueError:
        return None, 'ffprobe_invalid_json'
    return payload if isinstance(payload, dict) else None, ''


def collect_tags(payload: dict) -> tuple[dict, list[str]]:
    raw_tags = {}
    for container in (payload.get('format') or {}, *(payload.get('streams') or [])):
        tags = container.get('tags') if isinstance(container, dict) else None
        if not isinstance(tags, dict):
            continue
        for key, value in tags.items():
            normalized = key.lower()
            if normalized in TAG_KEYS and normalized not in raw_tags and value not in (None, ''):
                raw_tags[normalized] = clip(value, 300)
    return raw_tags, sorted(raw_tags)


def video_stream(payload: dict) -> dict:
    for stream in payload.get('streams') or []:
        if isinstance(stream, dict) and stream.get('codec_type') == 'video':
            return stream
    return {}


def audio_stream(payload: dict) -> dict:
    for stream in payload.get('streams') or []:
        if isinstance(stream, dict) and stream.get('codec_type') == 'audio':
            return stream
    return {}


def crop_filter(crop: str) -> str:
    if crop == 'corner:tl':
        return 'crop=iw/4:ih/4:0:0'
    if crop == 'corner:tr':
        return 'crop=iw/4:ih/4:iw-iw/4:0'
    if crop == 'corner:bl':
        return 'crop=iw/4:ih/4:0:ih-ih/4'
    if crop == 'corner:br':
        return 'crop=iw/4:ih/4:iw-iw/4:ih-ih/4'
    if crop == 'face':
        # Heuristic upper-middle ROI; not a face detector.
        return 'crop=iw*0.5:ih*0.35:iw*0.25:ih*0.12'
    return ''


def crop_suffix(crop: str) -> str:
    if crop == 'full':
        return ''
    if crop.startswith('corner:'):
        return '-' + crop.split(':', 1)[1]
    return '-' + crop


def extract_frame(ffmpeg: str, source: Path, seconds: float, crop: str, width: int,
                  fmt: str, out_path: Path) -> tuple[bool, str]:
    filters = []
    crop_expr = crop_filter(crop)
    if crop_expr:
        filters.append(crop_expr)
    filters.append('scale=min(%d\\,iw):-2' % width)
    command = [
        ffmpeg, '-hide_banner', '-nostdin', '-v', 'error', '-y',
        '-ss', '%.3f' % max(0.0, seconds), '-i', str(source),
        '-frames:v', '1', '-vf', ','.join(filters),
    ]
    if fmt == 'jpg':
        command += ['-q:v', '2']
    else:
        command += ['-c:v', 'libwebp', '-quality', '80']
    command.append(str(out_path))
    try:
        completed = subprocess.run(command, capture_output=True, timeout=90, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return False, 'ffmpeg_failed'
    if completed.returncode != 0 or not out_path.is_file() or out_path.stat().st_size == 0:
        return False, 'ffmpeg_failed'
    return True, ''


def parse_percentages(raw: str) -> list[float]:
    values = []
    for part in str(raw or '').split(','):
        part = part.strip()
        if not part:
            continue
        value = float(part)
        if value < 0 or value > 100:
            raise ValueError('--frames percentages must be 0-100')
        values.append(round(value, 2))
    return values or [15.0, 50.0, 85.0]


def probe_one(path: Path, args, ffprobe: str, ffmpeg: str) -> dict:
    record = {
        'tool': TOOL,
        'name': clip(path.name, 200),
        'bytes': 0,
        'sha256': '',
        'status': 'error',
        'container': '',
        'durationSec': None,
        'width': None,
        'height': None,
        'fps': None,
        'videoCodec': '',
        'audioCodec': '',
        'tags': {},
        'tagsFound': [],
        'frames': [],
        'warnings': [],
        'probedAt': now_iso(),
    }
    if args.include_path:
        record['path'] = str(path)
    if not path.is_file():
        record['errorCode'] = 'file_missing'
        return record
    record['bytes'] = path.stat().st_size
    if record['bytes'] <= 0:
        record['errorCode'] = 'file_empty'
        return record
    record['sha256'] = sha256_file(path)

    payload, error_code = run_probe(ffprobe, path)
    if payload is None:
        record['errorCode'] = error_code
        return record

    fmt = payload.get('format') if isinstance(payload.get('format'), dict) else {}
    video = video_stream(payload)
    audio = audio_stream(payload)
    record['container'] = clip(fmt.get('format_name'), 80)
    duration = fmt.get('duration') or video.get('duration') or 0
    try:
        record['durationSec'] = round(float(duration), 3) if float(duration) > 0 else None
    except (TypeError, ValueError):
        record['durationSec'] = None
    record['width'] = video.get('width') if isinstance(video.get('width'), int) else None
    record['height'] = video.get('height') if isinstance(video.get('height'), int) else None
    record['fps'] = parse_rate(video.get('avg_frame_rate') or video.get('r_frame_rate')) or None
    record['videoCodec'] = clip(video.get('codec_name'), 40)
    record['audioCodec'] = clip(audio.get('codec_name'), 40)
    tags, found = collect_tags(payload)
    record['tags'] = tags
    record['tagsFound'] = found

    if not args.no_frames:
        if record['durationSec'] is None or record['durationSec'] <= 0:
            record['warnings'].append('no_video_duration')
        elif not ffmpeg:
            record['warnings'].append('ffmpeg_missing')
            record['status'] = 'partial'
        else:
            frame_dir = Path(args.frame_dir).expanduser().resolve()
            frame_dir.mkdir(parents=True, exist_ok=True)
            prefix = record['sha256'][:12]
            total = 0
            for percentage in args.frame_percentages:
                for crop in args.frame_crops:
                    if total >= 12:
                        record['warnings'].append('frame_limit')
                        break
                    suffix = crop_suffix(crop)
                    out_path = frame_dir / ('%s-%g%s-w%d.%s' % (prefix, percentage, suffix, args.frame_width, args.frame_format))
                    seconds = record['durationSec'] * percentage / 100.0
                    if not out_path.is_file() or out_path.stat().st_size == 0:
                        ok, code = extract_frame(ffmpeg, path, seconds, crop, args.frame_width,
                                                 args.frame_format, out_path)
                        if not ok:
                            record['warnings'].append('frame_%g%s_failed' % (percentage, suffix))
                            record['status'] = 'partial'
                            continue
                    width, height = image_size(out_path)
                    record['frames'].append({
                        'pct': percentage,
                        'crop': crop,
                        'file': out_path.name,
                        'width': width,
                        'height': height,
                        'bytes': out_path.stat().st_size,
                    })
                    total += 1

    if record['status'] != 'partial':
        record['status'] = 'ok'
    return record


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog='media_probe.py',
        description='ffprobe metadata + SHA-256 + 15/50/85% frame extraction for review.',
    )
    parser.add_argument('files', nargs='+', help='media files (video or image)')
    parser.add_argument('--out', help='write JSONL results here instead of stdout')
    parser.add_argument('--cache-dir', help='cache root; default $QIMENG_RESEARCH_KIT_CACHE')
    parser.add_argument('--frame-dir', help='frame output dir; default <cache>/frames')
    parser.add_argument('--ffprobe', help='ffprobe path (default $QIMENG_RESEARCH_KIT_FFPROBE or PATH)')
    parser.add_argument('--ffmpeg', help='ffmpeg path (default $QIMENG_RESEARCH_KIT_FFMPEG or PATH)')
    parser.add_argument('--frames', default='15,50,85', help='percentages (default: 15,50,85)')
    parser.add_argument('--no-frames', action='store_true', help='metadata only, no frame extraction')
    parser.add_argument('--frame-width', type=int, default=1600, help='max frame width px (default: 1600)')
    parser.add_argument('--frame-format', choices=('webp', 'jpg'), default='webp', help='frame format (default: webp)')
    parser.add_argument('--crops', default='full',
                        help='comma list: full, corner:tl, corner:tr, corner:bl, corner:br, face (default: full)')
    parser.add_argument('--include-path', action='store_true', help='include the absolute path in output (avoid in shared logs)')
    parser.add_argument('--pretty', action='store_true', help='indent JSON output')
    args = parser.parse_args(argv)

    try:
        args.frame_percentages = parse_percentages(args.frames)
    except ValueError as exc:
        print('[%s] %s' % (TOOL, exc), file=sys.stderr)
        return EXIT_USAGE
    crops = []
    for crop in [item.strip() for item in args.crops.split(',') if item.strip()]:
        if crop not in CROPS:
            print('[%s] unsupported crop: %s' % (TOOL, crop), file=sys.stderr)
            return EXIT_USAGE
        crops.append(crop)
    args.frame_crops = crops or ['full']

    ffprobe = find_tool(args.ffprobe, 'QIMENG_RESEARCH_KIT_FFPROBE', 'ffprobe')
    if not ffprobe:
        print('[%s] ffprobe not found; set QIMENG_RESEARCH_KIT_FFPROBE or --ffprobe' % TOOL, file=sys.stderr)
        return EXIT_USAGE
    ffmpeg = find_tool(args.ffmpeg, 'QIMENG_RESEARCH_KIT_FFMPEG', 'ffmpeg')
    if not ffmpeg and not args.no_frames:
        print('[%s] ffmpeg not found; set QIMENG_RESEARCH_KIT_FFMPEG, --ffmpeg, or use --no-frames' % TOOL,
              file=sys.stderr)
        return EXIT_USAGE
    if not args.frame_dir:
        args.frame_dir = str((Path(args.cache_dir).expanduser().resolve() if args.cache_dir else default_cache_dir())
                             / 'frames')

    records = []
    for raw in args.files:
        path = Path(raw).expanduser()
        record = probe_one(path, args, ffprobe, ffmpeg or '')
        records.append(record)
        print('[%s] %s status=%s frames=%d warnings=%s'
              % (TOOL, record['name'], record['status'], len(record['frames']),
                 ','.join(record['warnings']) or '-'), file=sys.stderr)

    summary = {
        'tool': TOOL,
        'version': VERSION,
        'status': 'ok',
        'files': len(records),
        'ok': sum(1 for item in records if item['status'] == 'ok'),
        'partial': sum(1 for item in records if item['status'] == 'partial'),
        'error': sum(1 for item in records if item['status'] == 'error'),
        'frameDir': display_path(args.frame_dir),
    }
    if args.out:
        out_path = Path(args.out).expanduser()
        out_path.parent.mkdir(parents=True, exist_ok=True)
        with out_path.open('w', encoding='utf-8') as handle:
            for record in records:
                handle.write(json.dumps(record, ensure_ascii=False) + '\n')
        summary['out'] = display_path(out_path)
        print(json.dumps(summary, ensure_ascii=False, separators=(',', ':')))
    else:
        payload = records[0] if len(records) == 1 else records
        print(json.dumps(payload, ensure_ascii=False, indent=2 if args.pretty else None,
                         separators=None if args.pretty else (',', ':')))
    if summary['error']:
        return EXIT_PARTIAL
    if summary['partial']:
        return EXIT_PARTIAL
    return EXIT_OK


if __name__ == '__main__':
    raise SystemExit(main())