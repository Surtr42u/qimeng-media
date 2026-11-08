#!/usr/bin/env python3
"""64-bit dHash comparison for frame/image duplicate and near-duplicate checks.

Usage:
  python -X utf8 tools/research_kit/dhash_compare.py --ref frame.webp --candidate DIR
  python -X utf8 tools/research_kit/dhash_compare.py --ref a.webp --ref b.png --pairwise

Images are decoded by ffmpeg (no Pillow): each input is scaled to 9x8 gray and
reduced to a 64-bit difference hash. Thresholds follow tools/IMAGE_RESEARCH.md:
<=2 same frame, 3..15 requires manual review or byte/duration checks, >=16 is a
different work. dHash is a screening signal only: it is sensitive to crops,
overlays, letterbox and text, so it cannot establish authorship by itself.

Exit codes: 0 ok; 1 some inputs could not be decoded; 2 usage or missing ffmpeg.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

TOOL = 'dhash_compare'
VERSION = '1.0.0'
EXIT_OK, EXIT_PARTIAL, EXIT_USAGE = 0, 1, 2
IMAGE_SUFFIXES = {'.jpg', '.jpeg', '.png', '.webp', '.gif', '.bmp'}
VIDEO_SUFFIXES = {'.mp4', '.webm', '.mov', '.mkv', '.avi', '.m4v', '.wmv', '.flv'}
MAX_INPUTS = 200
MAX_PAIRS = 2000


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
    temporary.write_text(json.dumps(payload, ensure_ascii=False), encoding='utf-8')
    os.replace(temporary, path)


def find_tool(explicit: str | None, env_name: str, name: str) -> str:
    candidate = (explicit or os.environ.get(env_name, '')).strip() or name
    if Path(candidate).is_file():
        return str(Path(candidate).resolve())
    found = shutil.which(candidate)
    return found or ''


def video_duration(ffprobe: str, path: Path) -> float:
    if not ffprobe:
        return 0.0
    command = [ffprobe, '-v', 'error', '-show_entries', 'format=duration',
               '-of', 'default=noprint_wrappers=1:nokey=1', str(path)]
    try:
        completed = subprocess.run(command, capture_output=True, timeout=30, check=False)
        return max(0.0, float(completed.stdout.decode('utf-8', 'ignore').strip() or 0))
    except (OSError, subprocess.TimeoutExpired, ValueError):
        return 0.0


def decode_gray(ffmpeg: str, path: Path, seconds: float | None, hash_size: int) -> bytes:
    width = hash_size + 1
    height = hash_size
    command = [ffmpeg, '-hide_banner', '-nostdin', '-v', 'error']
    if seconds is not None:
        command += ['-ss', '%.3f' % max(0.0, seconds)]
    command += [
        '-i', str(path), '-frames:v', '1',
        '-vf', 'scale=%d:%d:flags=area,format=gray' % (width, height),
        '-f', 'rawvideo', '-',
    ]
    completed = subprocess.run(command, capture_output=True, timeout=60, check=False)
    if completed.returncode != 0 or len(completed.stdout) < width * height:
        raise ValueError('decode_failed')
    return completed.stdout[:width * height]


def dhash_from_gray(pixels: bytes, hash_size: int) -> int:
    width = hash_size + 1
    bits = 0
    for row in range(hash_size):
        base = row * width
        for column in range(hash_size):
            bits = (bits << 1) | (1 if pixels[base + column] > pixels[base + column + 1] else 0)
    return bits


def popcount(value: int) -> int:
    return bin(value).count('1')


def hash_input(path: Path, args, ffmpeg: str, ffprobe: str, cache_dir: Path) -> dict:
    digest = sha256_file(path)
    suffix = path.suffix.lower()
    is_video = suffix in VIDEO_SUFFIXES
    seconds = None
    if is_video and args.at_pct is not None:
        duration = video_duration(ffprobe, path)
        seconds = duration * args.at_pct / 100.0 if duration > 0 else None
    cache_file = cache_dir / ('%s-%d-%s.json' % (digest[:32], args.hash_size,
                                                 'v%g' % (args.at_pct if is_video else 0)))
    if not args.refresh:
        cached = read_json(cache_file)
        if isinstance(cached, dict) and isinstance(cached.get('dhash'), str):
            return {'name': clip(path.name, 160), 'sha256': digest, 'dhash': cached['dhash'],
                    'video': is_video, 'cacheHit': True}
    pixels = decode_gray(ffmpeg, path, seconds, args.hash_size)
    value = dhash_from_gray(pixels, args.hash_size)
    dhash_hex = format(value, '016x')
    write_json_atomic(cache_file, {'dhash': dhash_hex, 'sha256': digest,
                                   'hashSize': args.hash_size, 'atPct': args.at_pct if is_video else None})
    return {'name': clip(path.name, 160), 'sha256': digest, 'dhash': dhash_hex, 'video': is_video}


def expand_inputs(items: list[str]) -> list[Path]:
    found: list[Path] = []
    seen = set()
    for raw in items:
        path = Path(raw).expanduser()
        if path.is_dir():
            candidates = sorted(item for item in path.rglob('*')
                                if item.is_file() and item.suffix.lower() in IMAGE_SUFFIXES | VIDEO_SUFFIXES)
        elif path.is_file():
            candidates = [path]
        else:
            candidates = []
        for candidate in candidates:
            key = str(candidate.resolve()).lower()
            if key in seen:
                continue
            seen.add(key)
            found.append(candidate.resolve())
    return found


def verdict_for(distance: int, args) -> str:
    if distance <= 2:
        return 'same_frame'
    if distance < 16:
        return 'manual_review'
    return 'different'


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog='dhash_compare.py',
        description='64-bit dHash Hamming comparison for frames and images (batch pairs).',
    )
    parser.add_argument('--ref', action='append', default=[], help='reference file or directory (repeatable)')
    parser.add_argument('--candidate', action='append', default=[], help='candidate file or directory (repeatable)')
    parser.add_argument('paths', nargs='*', help='more files/directories (treated as candidates, or as pairwise inputs)')
    parser.add_argument('--pairwise', action='store_true', help='compare every input pair instead of ref x candidate')
    parser.add_argument('--hash-size', type=int, default=8, help='grid size; 8 = 64-bit (default: 8)')
    parser.add_argument('--threshold', type=int, default=2, help='hit cutoff distance (default: 2)')
    parser.add_argument('--top', type=int, default=20, help='max pairs to print, 0 = all up to --max-pairs (default: 20)')
    parser.add_argument('--max-pairs', type=int, default=MAX_PAIRS, help='hard pair cap (default: 2000)')
    parser.add_argument('--at-pct', type=float, default=50.0, help='video sample position percent (default: 50)')
    parser.add_argument('--out', help='write the JSON report here instead of stdout')
    parser.add_argument('--cache-dir', help='cache root; default $QIMENG_RESEARCH_KIT_CACHE')
    parser.add_argument('--ffmpeg', help='ffmpeg path (default $QIMENG_RESEARCH_KIT_FFMPEG or PATH)')
    parser.add_argument('--ffprobe', help='ffprobe path (default $QIMENG_RESEARCH_KIT_FFPROBE or PATH)')
    parser.add_argument('--refresh', action='store_true', help='ignore cached hashes')
    parser.add_argument('--pretty', action='store_true', help='indent the JSON report')
    args = parser.parse_args(argv)

    if args.hash_size < 4 or args.hash_size > 16:
        print('[%s] --hash-size must be 4..16' % TOOL, file=sys.stderr)
        return EXIT_USAGE
    if args.at_pct is not None and not 0 <= args.at_pct <= 100:
        print('[%s] --at-pct must be 0..100' % TOOL, file=sys.stderr)
        return EXIT_USAGE
    ffmpeg = find_tool(args.ffmpeg, 'QIMENG_RESEARCH_KIT_FFMPEG', 'ffmpeg')
    if not ffmpeg:
        print('[%s] ffmpeg not found; set QIMENG_RESEARCH_KIT_FFMPEG or --ffmpeg' % TOOL, file=sys.stderr)
        return EXIT_USAGE
    ffprobe = find_tool(args.ffprobe, 'QIMENG_RESEARCH_KIT_FFPROBE', 'ffprobe')

    refs_paths = expand_inputs(args.ref)
    candidate_paths = expand_inputs(args.candidate + args.paths)
    if args.pairwise:
        all_paths = refs_paths + [item for item in candidate_paths if item not in refs_paths]
        if len(all_paths) < 2:
            print('[%s] --pairwise needs at least two inputs' % TOOL, file=sys.stderr)
            return EXIT_USAGE
        refs_paths = all_paths
        candidate_paths = all_paths
    if not refs_paths or not candidate_paths:
        print('[%s] provide --ref and --candidate (or positional paths)' % TOOL, file=sys.stderr)
        return EXIT_USAGE
    if len(refs_paths) + len(candidate_paths) > MAX_INPUTS * 2:
        print('[%s] too many inputs' % TOOL, file=sys.stderr)
        return EXIT_USAGE

    cache_dir = (Path(args.cache_dir).expanduser().resolve() if args.cache_dir else default_cache_dir()) / TOOL
    cache_dir.mkdir(parents=True, exist_ok=True)

    hashed: dict[str, dict] = {}
    failed = []
    for path in refs_paths + candidate_paths:
        key = str(path)
        if key in hashed:
            continue
        try:
            hashed[key] = hash_input(path, args, ffmpeg, ffprobe, cache_dir)
        except (OSError, ValueError) as exc:
            failed.append({'name': clip(path.name, 160), 'error': clip(str(exc), 80)})

    def label(record: dict) -> str:
        return record['name'] + '#' + record['sha256'][:8]

    pairs = []
    seen_pairs = set()
    ref_records = [hashed[str(path)] for path in refs_paths if str(path) in hashed]
    candidate_records = [hashed[str(path)] for path in candidate_paths if str(path) in hashed]
    if args.pairwise:
        for index, left in enumerate(ref_records):
            for right in ref_records[index + 1:]:
                key = tuple(sorted((label(left), label(right))))
                if key in seen_pairs:
                    continue
                seen_pairs.add(key)
                distance = popcount(int(left['dhash'], 16) ^ int(right['dhash'], 16))
                pairs.append({'ref': label(left), 'candidate': label(right), 'distance': distance,
                              'hit': distance <= args.threshold, 'verdict': verdict_for(distance, args)})
    else:
        for ref in ref_records:
            for candidate in candidate_records:
                if ref['sha256'] == candidate['sha256']:
                    distance = 0
                else:
                    distance = popcount(int(ref['dhash'], 16) ^ int(candidate['dhash'], 16))
                pairs.append({'ref': label(ref), 'candidate': label(candidate), 'distance': distance,
                              'hit': distance <= args.threshold, 'verdict': verdict_for(distance, args)})
    pairs.sort(key=lambda item: (item['distance'], item['ref'], item['candidate']))
    if args.max_pairs > 0:
        pairs = pairs[:args.max_pairs]
    printed = pairs if args.top <= 0 else pairs[:args.top]

    report = {
        'tool': TOOL,
        'version': VERSION,
        'hashSize': args.hash_size,
        'threshold': args.threshold,
        'thresholds': {'same_frame': '<=2', 'manual_review': '3..15', 'different': '>=16'},
        'refs': [{'label': label(record), 'dhash': record['dhash']} for record in ref_records],
        'matches': printed,
        'stats': {
            'refs': len(ref_records),
            'candidates': len(candidate_records),
            'pairs': len(pairs),
            'hits': sum(1 for item in pairs if item['hit']),
            'failed': len(failed),
        },
        'failed': failed[:10],
        'generatedAt': now_iso(),
    }
    text = json.dumps(report, ensure_ascii=False, indent=2 if args.pretty else None,
                      separators=None if args.pretty else (',', ':'))
    if args.out:
        out_path = Path(args.out).expanduser()
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding='utf-8')
    print(text)
    if failed:
        print('[%s] %d input(s) could not be decoded; see failed[]' % (TOOL, len(failed)), file=sys.stderr)
        return EXIT_PARTIAL
    return EXIT_OK


if __name__ == '__main__':
    raise SystemExit(main())