#!/usr/bin/env python3
"""Research pipeline: jobs -> priority ladder -> results JSONL with budgets.

Usage:
  python -X utf8 tools/research_kit/run_pipeline.py --jobs jobs.jsonl
  python -X utf8 tools/research_kit/run_pipeline.py --jobs jobs.jsonl --stages lens,yandex --deep

Stages run in the documented priority ladder:
  media (video frame) -> Lens -> Yandex -> SauceNAO -> author probe (rid)

Input jobs (JSONL): {"id":"job-001","file":"...","rid":"optional"}

Rules:
- Lens is single-flight and rate-limited; it stops on a risk signal and leaves a
  cooldown marker. Yandex and SauceNAO stop on blocked/rate_limited.
- A hit means a known platform domain (X/Pixiv/Patreon/...) appears in Lens
  topDomains or Yandex sites. By default the ladder stops at the first hit;
  --deep continues. Cites alone are evidence to review, not an automatic hit.
- Author probes only run when a job carries rid: @handle/x:<handle>/post:<id>/
  kemono:<service>:<creator>[:postId].
- Results are appended per job so a crash or exhausted budget can resume.
- Request budgets are counted per stage and never exceeded.

Exit codes: 0 all processed jobs ok/skipped; 1 partial/blocked/budget/unavailable
dependency; 2 usage or invalid jobs file.
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

TOOL = 'run_pipeline'
VERSION = '1.0.0'
EXIT_OK, EXIT_PARTIAL, EXIT_USAGE = 0, 1, 2
ALL_STAGES = ('lens', 'yandex', 'saucenao', 'author')
IMAGE_SUFFIXES = {'.jpg', '.jpeg', '.png', '.webp', '.gif', '.bmp'}
VIDEO_SUFFIXES = {'.mp4', '.webm', '.mov', '.mkv', '.avi', '.m4v', '.wmv', '.flv'}
PLATFORM_DOMAINS = (
    'x.com', 'twitter.com', 'pixiv.net', 'pixiv.me', 'fanbox.cc', 'patreon.com',
    'iwara.tv', 'booth.pm', 'fantia.jp', 'subscribestar.com', 'deviantart.com',
    'artstation.com', 'weibo.com', 'bilibili.com', 'bsky.app', 'dlsite.com',
    'melonbooks.co.jp', 'kemono.su', 'coomer.su', 'skeb.jp', 'fanbox.pixiv.net',
)


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
    return str(value or '').strip()[:limit]


def display_path(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(Path.cwd().resolve()))
    except (ValueError, OSError):
        return path.name


def is_platform_domain(domain: str) -> bool:
    host = str(domain or '').lower().removeprefix('www.').rstrip('.')
    return any(host == item or host.endswith('.' + item) for item in PLATFORM_DOMAINS)


def read_json(path: Path):
    try:
        return json.loads(path.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return None


def read_jobs(path: Path) -> tuple[list[dict], int]:
    try:
        text = path.read_text(encoding='utf-8-sig')
    except OSError:
        raise ValueError('cannot read jobs file: ' + display_path(path))
    jobs = []
    bad = 0
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
        job_id = str(item.get('id') or '').strip()
        file_value = item.get('file')
        if not job_id or not isinstance(file_value, str) or not file_value.strip():
            bad += 1
            continue
        if job_id in seen:
            raise ValueError('duplicate job id: ' + job_id)
        seen.add(job_id)
        jobs.append({
            'id': job_id,
            'file': file_value.strip(),
            'rid': str(item.get('rid') or '').strip(),
        })
    return jobs, bad


def existing_results(path: Path, retry_errors: bool) -> set[str]:
    """Resume semantics: only fully finished jobs are skipped by default.

    partial/failed records are retried on the next run; --retry-errors is kept
    as an explicit equivalent for symmetry with the single-tool flags.
    """
    done = set()
    if not path.is_file():
        return done
    for raw in path.read_text(encoding='utf-8-sig').splitlines():
        try:
            item = json.loads(raw)
        except ValueError:
            continue
        if not isinstance(item, dict) or 'id' not in item:
            continue
        if item.get('status') not in ('ok', 'skipped'):
            continue
        done.add(str(item['id']))
    return done


def append_result(path: Path, record: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a', encoding='utf-8') as handle:
        handle.write(json.dumps(record, ensure_ascii=False) + '\n')


def run_command(command: list[str], timeout: float) -> tuple[int, str, str]:
    try:
        completed = subprocess.run(command, capture_output=True, timeout=timeout, check=False)
    except subprocess.TimeoutExpired:
        return 124, '', 'timeout'
    except OSError:
        return 127, '', 'spawn_failed'
    return (completed.returncode,
            completed.stdout.decode('utf-8', 'replace'),
            completed.stderr.decode('utf-8', 'replace'))


def last_json(stdout: str):
    for line in reversed(stdout.splitlines()):
        line = line.strip()
        if not line or not line.startswith(('{', '[')):
            continue
        try:
            return json.loads(line)
        except ValueError:
            continue
    return None


def read_jsonl(path: Path) -> list[dict]:
    items = []
    if not path.is_file():
        return items
    for raw in path.read_text(encoding='utf-8-sig').splitlines():
        try:
            item = json.loads(raw)
        except ValueError:
            continue
        if isinstance(item, dict):
            items.append(item)
    return items


def compact_lens(result: dict) -> dict:
    domains = [clip(item, 80) for item in (result.get('topDomains') or [])[:5]]
    info = {
        'status': clip(result.get('status'), 20) or 'error',
        'topDomains': domains,
        'citesCount': int(result.get('citesCount') or 0),
        'hit': any(is_platform_domain(item) for item in domains),
    }
    if result.get('cacheHit'):
        info['cacheHit'] = True
    if result.get('errorCode'):
        info['errorCode'] = clip(result['errorCode'], 40)
    return info


def compact_yandex(result: dict) -> dict:
    sites = result.get('sites') if isinstance(result.get('sites'), list) else []
    domains = []
    for site in sites:
        domain = clip(site.get('domain'), 80) if isinstance(site, dict) else ''
        if domain and domain not in domains:
            domains.append(domain)
        if len(domains) >= 5:
            break
    info = {
        'status': clip(result.get('status'), 20) or 'error',
        'sites': len(sites),
        'domains': domains,
        'hit': any(is_platform_domain(item) for item in domains),
    }
    if result.get('errorCode'):
        info['errorCode'] = clip(result['errorCode'], 40)
    return info


def compact_saucenao(result: dict) -> dict:
    info = {
        'status': clip(result.get('status'), 20) or 'error',
        'similarity': result.get('similarity'),
        'creator': clip(result.get('creator'), 60),
        'index': clip(result.get('index'), 40),
    }
    if result.get('errorCode'):
        info['errorCode'] = clip(result['errorCode'], 40)
    return info


def compact_author(kind: str, result: dict) -> dict:
    if kind == 'kemono':
        creator = result.get('creator') if isinstance(result.get('creator'), dict) else {}
        info = {
            'status': clip(result.get('status'), 20) or 'error',
            'kind': 'kemono',
            'service': clip(result.get('service'), 20),
            'creator': {'id': clip(creator.get('id'), 60), 'name': clip(creator.get('name'), 80)},
            'posts': len(result.get('posts') or []),
        }
    elif result.get('kind') == 'post':
        info = {
            'status': clip(result.get('status'), 20) or 'error',
            'kind': 'post',
            'id': clip(result.get('id'), 40),
            'authorHandle': clip(result.get('authorHandle'), 32),
            'authorId': clip(result.get('authorId'), 40),
        }
    else:
        info = {
            'status': clip(result.get('status'), 20) or 'error',
            'kind': 'author',
            'handle': clip(result.get('handle'), 32),
            'name': clip(result.get('name'), 60),
            'id': clip(result.get('id'), 40),
            'followers': result.get('followers'),
        }
    if result.get('errorCode'):
        info['errorCode'] = clip(result['errorCode'], 40)
    return info


def parse_rid(raw: str) -> tuple[str, dict] | None:
    value = str(raw or '').strip()
    if not value:
        return None
    lowered = value.lower()
    if lowered.startswith('kemono:'):
        parts = value.split(':')
        if len(parts) >= 3:
            service = parts[1].strip()
            creator = parts[2].strip()
            if service and creator:
                return 'kemono', {'service': service, 'creator': creator,
                                  'post': parts[3].strip() if len(parts) >= 4 else ''}
        return None
    if lowered.startswith('post:'):
        return 'fxtwitter_post', {'id': value.split(':', 1)[1].strip()}
    if lowered.startswith('x:'):
        return 'fxtwitter_author', {'handle': value.split(':', 1)[1].strip().lstrip('@')}
    if value.startswith('@'):
        return 'fxtwitter_author', {'handle': value[1:].strip()}
    if lowered.isdigit():
        return 'fxtwitter_post', {'id': value}
    if value.lower().startswith(('http://', 'https://')):
        return 'fxtwitter_auto', {'target': value}
    if value.startswith('rid:'):
        value = value[4:]
    return 'fxtwitter_author', {'handle': value.lstrip('@')}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog='run_pipeline.py',
        description='Orchestrate media/frame preparation, Lens, Yandex, SauceNAO and author probes.',
    )
    parser.add_argument('--jobs', required=True, help='jobs JSONL: {"id":"...","file":"...","rid":"..."}')
    parser.add_argument('--out', help='results JSONL; default <cache>/pipeline/results.jsonl')
    parser.add_argument('--failures-out', help='failure JSONL; default <out>.failures.jsonl')
    parser.add_argument('--cache-dir', help='cache root; default $QIMENG_RESEARCH_KIT_CACHE')
    parser.add_argument('--evidence-dir', help='optional per-job evidence JSON directory (ignored)')
    parser.add_argument('--stages', default='auto', help='auto or comma list of ' + ','.join(ALL_STAGES))
    parser.add_argument('--deep', action='store_true', help='continue Yandex/SauceNAO after a Lens hit')
    parser.add_argument('--saucenao', dest='saucenao', action='store_true', default=None,
                        help='force the SauceNAO stage (needs the API key)')
    parser.add_argument('--no-saucenao', dest='saucenao', action='store_false',
                        help='disable the SauceNAO stage')
    parser.add_argument('--max-lens', type=int, default=15, help='Lens request budget (default: 15)')
    parser.add_argument('--max-yandex', type=int, default=10, help='Yandex request budget (default: 10)')
    parser.add_argument('--max-saucenao', type=int, default=20, help='SauceNAO request budget (default: 20)')
    parser.add_argument('--max-author', type=int, default=20, help='author probe budget (default: 20)')
    parser.add_argument('--max-jobs', type=int, default=0, help='process at most N jobs (0 = all)')
    parser.add_argument('--lens-min-interval-ms', type=int, default=8000, help='Lens spacing (default: 8000)')
    parser.add_argument('--frame-width', type=int, default=1600, help='video frame max width (default: 1600)')
    parser.add_argument('--stage-timeout', type=float, default=900.0, help='per-tool timeout seconds (default: 900)')
    parser.add_argument('--node', help='node executable (default $QIMENG_RESEARCH_KIT_NODE or PATH)')
    parser.add_argument('--python', help='python executable for sibling tools (default: current interpreter)')
    parser.add_argument('--edge', help='Edge/Chrome path passed to lens_batch')
    parser.add_argument('--retry-errors', action='store_true', help='retry ids whose recorded status was not ok')
    parser.add_argument('--dry-run', action='store_true', help='plan only, no external requests')
    parser.add_argument('--json-summary', action='store_true', help='print one JSON summary line')
    parser.add_argument('--summary-lines', type=int, default=12, help='human summary line cap (default: 12)')
    parser.add_argument('--verbose', action='store_true', help='print tool stderr progress')
    return parser


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    if args.summary_lines < 4:
        print('[%s] --summary-lines must be >= 4' % TOOL, file=sys.stderr)
        return EXIT_USAGE
    cache_root = (Path(args.cache_dir).expanduser().resolve() if args.cache_dir
                  else default_cache_dir()) / TOOL
    cache_root.mkdir(parents=True, exist_ok=True)
    out_path = Path(args.out).expanduser() if args.out else cache_root / 'results.jsonl'
    failures_path = (Path(args.failures_out).expanduser() if args.failures_out
                     else out_path.with_suffix(out_path.suffix + '.failures.jsonl'))
    evidence_dir = Path(args.evidence_dir).expanduser().resolve() if args.evidence_dir else None

    try:
        jobs, bad = read_jobs(Path(args.jobs).expanduser())
    except ValueError as exc:
        print('[%s] %s' % (TOOL, exc), file=sys.stderr)
        return EXIT_USAGE
    if not jobs:
        print('[%s] jobs file contains no valid job' % TOOL, file=sys.stderr)
        return EXIT_USAGE

    if args.stages == 'auto':
        stages = ['lens', 'yandex', 'author']
        key_env = os.environ.get('QIMENG_RESEARCH_KIT_SAUCENAO_KEY', '').strip()
        if args.saucenao is True or (args.saucenao is None and key_env):
            stages.insert(2, 'saucenao')
        if args.saucenao is False:
            stages = [stage for stage in stages if stage != 'saucenao']
    else:
        stages = [item.strip() for item in args.stages.split(',') if item.strip()]
        unknown = [item for item in stages if item not in ALL_STAGES]
        if unknown:
            print('[%s] unknown stage(s): %s' % (TOOL, ','.join(unknown)), file=sys.stderr)
            return EXIT_USAGE
        stages = [stage for stage in ALL_STAGES if stage in stages]
        if 'saucenao' in stages and args.saucenao is False:
            stages.remove('saucenao')
    if not stages:
        print('[%s] no stage selected' % TOOL, file=sys.stderr)
        return EXIT_USAGE

    script_dir = Path(__file__).resolve().parent
    python = args.python or sys.executable
    node = args.node or os.environ.get('QIMENG_RESEARCH_KIT_NODE', '').strip() or shutil.which('node') or ''
    if node and not (Path(node).is_file() or shutil.which(node)):
        node = ''
    if 'lens' in stages and not node:
        if args.stages != 'auto':
            print('[%s] --stages requires lens but node was not found' % TOOL, file=sys.stderr)
            return EXIT_USAGE
        stages = [stage for stage in stages if stage != 'lens']
        lens_unavailable = True
    else:
        lens_unavailable = False
    if 'saucenao' in stages and not os.environ.get('QIMENG_RESEARCH_KIT_SAUCENAO_KEY', '').strip():
        if args.stages != 'auto' or args.saucenao is True:
            print('[%s] SauceNAO stage needs QIMENG_RESEARCH_KIT_SAUCENAO_KEY' % TOOL, file=sys.stderr)
            return EXIT_USAGE
        stages = [stage for stage in stages if stage != 'saucenao']
    if not stages:
        print('[%s] no usable stage (missing node/SauceNAO key)' % TOOL, file=sys.stderr)
        return EXIT_USAGE

    done = existing_results(out_path, args.retry_errors)
    pending = [job for job in jobs if job['id'] not in done]
    if args.max_jobs > 0:
        pending = pending[:args.max_jobs]

    plan = {'jobs': len(jobs), 'badLines': bad, 'pending': len(pending), 'stages': stages,
            'videos': sum(1 for job in pending if Path(job['file']).suffix.lower() in VIDEO_SUFFIXES),
            'withRid': sum(1 for job in pending if job['rid'])}
    if args.dry_run:
        lines = [
            'research_kit pipeline (dry run)',
            '  jobs: %d valid, %d bad, %d pending after resume' % (len(jobs), bad, len(pending)),
            '  stages: %s' % ','.join(stages),
            '  videos needing a frame: %d' % plan['videos'],
            '  jobs with rid: %d' % plan['withRid'],
            '  results: %s' % display_path(out_path),
            '  budgets: lens=%d yandex=%d saucenao=%d author=%d'
            % (args.max_lens, args.max_yandex, args.max_saucenao, args.max_author),
        ]
        if lens_unavailable:
            lines.append('  note: lens skipped because node is unavailable')
        print('\n'.join(lines[:args.summary_lines]))
        return EXIT_OK

    if not pending:
        summary = {'tool': TOOL, 'status': 'ok', 'jobs': len(jobs), 'pending': 0,
                   'processed': 0, 'ok': 0, 'partial': 0, 'failed': 0, 'remaining': 0}
        print(json.dumps(summary, ensure_ascii=False, separators=(',', ':'))
              if args.json_summary else 'research_kit pipeline: no pending jobs')
        return EXIT_OK

    prepared: dict[str, Path] = {}
    job_state: dict[str, dict] = {}
    failures: list[dict] = []
    notes: list[str] = []
    budget_used = {'lens': 0, 'yandex': 0, 'saucenao': 0, 'author': 0}
    stage_hits = {'lens': 0, 'yandex': 0, 'saucenao': 0, 'author': 0}
    stage_errors: dict[str, str] = {}
    stop_stage = {'lens': '', 'yandex': '', 'saucenao': ''}
    media_dir = cache_root / 'frames'
    media_dir.mkdir(parents=True, exist_ok=True)

    def job_record(job: dict) -> dict:
        return job_state.setdefault(job['id'], {
            'id': job['id'],
            'status': 'pending',
            'kind': ('video' if Path(job['file']).suffix.lower() in VIDEO_SUFFIXES
                     else 'image' if Path(job['file']).suffix.lower() in IMAGE_SUFFIXES else 'unknown'),
            'ts': now_iso(),
        })

    def fail(stage: str, job: dict, code: str) -> None:
        record = job_record(job)
        if not any(item['id'] == job['id'] and item['stage'] == stage for item in failures):
            failures.append({'id': job['id'], 'stage': stage, 'code': clip(code, 60)})
        record.setdefault('notes', []).append(clip(stage + ':' + code, 80))
        record['status'] = 'partial'

    # Phase 1: prepare one review image per job (video frame extraction).
    if lens_unavailable:
        notes.append('lens_unavailable')
    for job in pending:
        record = job_record(job)
        source = Path(job['file']).expanduser()
        suffix = source.suffix.lower()
        if suffix in IMAGE_SUFFIXES:
            if source.is_file():
                prepared[job['id']] = source
            else:
                fail('media', job, 'file_missing')
                record['status'] = 'failed'
            continue
        if suffix not in VIDEO_SUFFIXES:
            fail('media', job, 'unsupported_extension')
            record['status'] = 'failed'
            continue
        media_out = cache_root / 'media' / (clip(job['id'], 60) + '.json')
        media_out.parent.mkdir(parents=True, exist_ok=True)
        command = [
            python, '-X', 'utf8', str(script_dir / 'media_probe.py'), str(source),
            '--frames', '50', '--crops', 'full', '--frame-width', str(args.frame_width),
            '--frame-dir', str(media_dir), '--out', str(media_out),
        ]
        code, stdout, stderr = run_command(command, min(args.stage_timeout, 300))
        if args.verbose and stderr:
            print(stderr.strip()[-500:], file=sys.stderr)
        probe = read_jsonl(media_out)
        probe_record = probe[0] if probe else last_json(stdout)
        if not isinstance(probe_record, dict):
            fail('media', job, 'probe_failed')
            record['status'] = 'failed'
            continue
        record['media'] = {
            'status': clip(probe_record.get('status'), 20),
            'durationSec': probe_record.get('durationSec'),
            'width': probe_record.get('width'),
            'height': probe_record.get('height'),
            'sha256': clip(probe_record.get('sha256'), 64),
            'tagsFound': (probe_record.get('tagsFound') or [])[:6],
        }
        frames = probe_record.get('frames') if isinstance(probe_record.get('frames'), list) else []
        if frames and isinstance(frames[0], dict) and frames[0].get('file'):
            frame_path = media_dir / str(frames[0]['file'])
            if frame_path.is_file():
                prepared[job['id']] = frame_path
            else:
                fail('media', job, 'frame_missing')
        else:
            fail('media', job, 'frame_missing')

    lens_results: dict[str, dict] = {}
    if 'lens' in stages and not lens_unavailable:
        eligible = [job for job in pending if job['id'] in prepared][:max(0, args.max_lens)]
        if eligible:
            stamp = time.strftime('%Y%m%d-%H%M%S', time.gmtime())
            jobs_file = cache_root / ('lens-jobs-' + stamp + '.jsonl')
            results_file = cache_root / ('lens-results-' + stamp + '.jsonl')
            with jobs_file.open('w', encoding='utf-8') as handle:
                for job in eligible:
                    handle.write(json.dumps({'id': job['id'], 'file': str(prepared[job['id']])},
                                            ensure_ascii=False) + '\n')
            command = [
                node, str(script_dir / 'lens_batch.mjs'), '--jobs', str(jobs_file),
                '--out', str(results_file), '--cache-dir', str(cache_root.parent),
                '--min-interval-ms', str(args.lens_min_interval_ms),
                '--timeout-ms', str(int(args.stage_timeout * 1000)),
            ]
            if args.edge:
                command += ['--edge', args.edge]
            budget_used['lens'] = len(eligible)
            code, stdout, stderr = run_command(command, max(120.0, args.stage_timeout * len(eligible) / 4))
            if args.verbose and stderr:
                print(stderr.strip()[-800:], file=sys.stderr)
            summary = last_json(stdout)
            if isinstance(summary, dict) and summary.get('status') in ('blocked', 'blocked_cooldown'):
                stop_stage['lens'] = 'blocked'
                notes.append('lens_blocked')
            tool_failed = code not in (0, 1)
            if tool_failed:
                stage_errors['lens'] = 'lens_tool_failed'
                notes.append('lens_tool_failed')
                for job in eligible:
                    fail('lens', job, 'lens_tool_failed')
            for item in read_jsonl(results_file):
                if 'id' in item:
                    lens_results[str(item['id'])] = item
            if not tool_failed:
                for job in eligible:
                    if job['id'] not in lens_results:
                        fail('lens', job, 'lens_no_result')
        elif args.max_lens <= 0:
            notes.append('lens_budget_zero')
        else:
            notes.append('lens_no_input')

    for job in pending:
        record = job_record(job)
        if job['id'] in lens_results:
            record['lens'] = compact_lens(lens_results[job['id']])
            if record['lens']['hit']:
                stage_hits['lens'] += 1
            if record['lens']['status'] == 'blocked':
                stop_stage['lens'] = 'blocked'
                fail('lens', job, 'blocked')
        elif 'lens' in stages and job['id'] in prepared and not lens_unavailable:
            if budget_used['lens'] >= args.max_lens and args.max_lens > 0:
                record.setdefault('notes', []).append('lens:budget')
            elif stop_stage['lens']:
                record.setdefault('notes', []).append('lens:stopped')
        elif lens_unavailable:
            record.setdefault('notes', []).append('lens:unavailable')

    # Phase 2: Yandex for jobs without a Lens hit (or --deep).
    if 'yandex' in stages:
        for job in pending:
            record = job_record(job)
            if job['id'] not in prepared:
                continue
            lens_hit = bool(record.get('lens', {}).get('hit'))
            if lens_hit and not args.deep:
                continue
            if stop_stage['yandex']:
                record.setdefault('notes', []).append('yandex:' + stop_stage['yandex'])
                continue
            if budget_used['yandex'] >= args.max_yandex:
                record.setdefault('notes', []).append('yandex:budget')
                continue
            command = [
                python, '-X', 'utf8', str(script_dir / 'yandex_cbir.py'), str(prepared[job['id']]),
                '--cache-dir', str(cache_root.parent),
            ]
            budget_used['yandex'] += 1
            code, stdout, stderr = run_command(command, args.stage_timeout)
            if args.verbose and stderr:
                print(stderr.strip()[-500:], file=sys.stderr)
            payload = last_json(stdout)
            if not isinstance(payload, dict):
                fail('yandex', job, 'invalid_output')
                continue
            record['yandex'] = compact_yandex(payload)
            if record['yandex']['hit']:
                stage_hits['yandex'] += 1
            if record['yandex']['status'] in ('blocked', 'rate_limited'):
                stop_stage['yandex'] = record['yandex']['status']
                fail('yandex', job, record['yandex']['status'])

    # Phase 3: SauceNAO batch for jobs still without any platform hit.
    if 'saucenao' in stages:
        candidates = []
        for job in pending:
            record = job_record(job)
            if job['id'] not in prepared:
                continue
            if (record.get('lens', {}).get('hit') or record.get('yandex', {}).get('hit')) and not args.deep:
                continue
            candidates.append(job)
        candidates = candidates[:max(0, args.max_saucenao)]
        if candidates:
            stamp = time.strftime('%Y%m%d-%H%M%S', time.gmtime())
            jobs_file = cache_root / ('saucenao-jobs-' + stamp + '.jsonl')
            results_file = cache_root / ('saucenao-results-' + stamp + '.jsonl')
            with jobs_file.open('w', encoding='utf-8') as handle:
                for job in candidates:
                    handle.write(json.dumps({'id': job['id'], 'file': str(prepared[job['id']])},
                                            ensure_ascii=False) + '\n')
            command = [
                python, '-X', 'utf8', str(script_dir / 'saucenao_batch.py'),
                '--jobs', str(jobs_file), '--out', str(results_file),
                '--cache-dir', str(cache_root.parent), '--limit', str(len(candidates)),
            ]
            budget_used['saucenao'] = len(candidates)
            code, stdout, stderr = run_command(command, max(120.0, 40.0 * len(candidates)))
            if args.verbose and stderr:
                print(stderr.strip()[-800:], file=sys.stderr)
            by_id = {job['id']: job for job in candidates}
            saucenao_tool_failed = code not in (0, 1)
            if saucenao_tool_failed:
                stage_errors['saucenao'] = 'saucenao_tool_failed'
                notes.append('saucenao_tool_failed')
                for job in candidates:
                    fail('saucenao', job, 'saucenao_tool_failed')
            for item in read_jsonl(results_file):
                job = by_id.get(str(item['id']))
                if job is None:
                    continue
                record = job_record(job)
                record['saucenao'] = compact_saucenao(item)
                if record['saucenao']['status'] == 'ok':
                    stage_hits['saucenao'] += 1
                elif record['saucenao']['status'] in ('blocked', 'rate_limited'):
                    fail('saucenao', job, record['saucenao']['status'])
            if not saucenao_tool_failed:
                for job in candidates:
                    if job['id'] not in by_id or 'saucenao' not in job_record(job):
                        fail('saucenao', job, 'saucenao_not_processed')

    # Phase 4: author probe for jobs carrying rid (independent of image hits).
    if 'author' in stages:
        for job in pending:
            record = job_record(job)
            parsed = parse_rid(job['rid'])
            if not parsed:
                continue
            if budget_used['author'] >= args.max_author:
                record.setdefault('notes', []).append('author:budget')
                continue
            kind, values = parsed
            budget_used['author'] += 1
            if kind == 'kemono':
                command = [
                    python, '-X', 'utf8', str(script_dir / 'kemono_probe.py'),
                    '--service', values['service'], '--creator', values['creator'],
                    '--cache-dir', str(cache_root.parent), '--posts', '3', '--pretty',
                ]
                if values.get('post'):
                    command += ['--post', values['post']]
                code, stdout, stderr = run_command(command, args.stage_timeout)
                payload = last_json(stdout)
                if isinstance(payload, dict):
                    record['author'] = compact_author('kemono', payload)
                else:
                    fail('author', job, 'invalid_output')
            else:
                target = values.get('handle') or values.get('id') or values.get('target') or ''
                if not target:
                    fail('author', job, 'rid_unrecognized')
                    continue
                command = [
                    python, '-X', 'utf8', str(script_dir / 'fxtwitter_probe.py'), target,
                    '--cache-dir', str(cache_root.parent), '--pretty',
                ]
                if kind == 'fxtwitter_post':
                    command += ['--kind', 'post']
                code, stdout, stderr = run_command(command, args.stage_timeout)
                if args.verbose and stderr:
                    print(stderr.strip()[-400:], file=sys.stderr)
                payload = last_json(stdout)
                if isinstance(payload, dict):
                    record['author'] = compact_author(kind, payload)
                    if record['author']['status'] == 'ok':
                        stage_hits['author'] += 1
                    elif record['author']['status'] in ('blocked', 'rate_limited'):
                        fail('author', job, record['author']['status'])
                else:
                    fail('author', job, 'invalid_output')

    # Finalize, write results and evidence.
    processed = 0
    ok_count = partial_count = failed_count = skipped_count = 0
    remaining = 0
    for job in pending:
        record = job_record(job)
        statuses = [record.get(stage, {}).get('status', '') for stage in ('lens', 'yandex', 'saucenao', 'author')]
        stage_errors_here = [status for status in statuses
                             if status in ('blocked', 'rate_limited', 'timeout', 'error')]
        attempted = any(stage in record for stage in ('lens', 'yandex', 'saucenao', 'author'))
        deferred_notes = [note for note in record.get('notes', [])
                          if note.endswith((':budget', ':stopped', ':blocked', ':rate_limited'))]
        budget_limited = bool(deferred_notes)
        attempted_statuses = [status for status in statuses if status]
        if record.get('media', {}).get('status') == 'error' and not attempted:
            record['status'] = 'failed'
            failed_count += 1
        elif not attempted and record.get('status') == 'failed':
            failed_count += 1
        elif not attempted and record.get('status') == 'partial':
            partial_count += 1
        elif not attempted and budget_limited:
            record['status'] = 'partial'
            partial_count += 1
        elif not attempted:
            record['status'] = 'skipped'
            record.setdefault('notes', []).append('no_stage_result')
            skipped_count += 1
        elif attempted_statuses and all(status == 'skipped' for status in attempted_statuses):
            record['status'] = 'skipped'
            skipped_count += 1
        elif stage_errors_here:
            record['status'] = 'partial'
            partial_count += 1
        else:
            record['status'] = 'ok'
            ok_count += 1
        record['ts'] = now_iso()
        processed += 1
        append_result(out_path, record)
        if evidence_dir:
            evidence_dir.mkdir(parents=True, exist_ok=True)
            (evidence_dir / (clip(job['id'], 60) + '.json')).write_text(
                json.dumps(record, ensure_ascii=False, indent=1), encoding='utf-8')

    for failure in failures:
        failures_path.parent.mkdir(parents=True, exist_ok=True)
        with failures_path.open('a', encoding='utf-8') as handle:
            handle.write(json.dumps(failure, ensure_ascii=False) + '\n')

    deferred = sum(1 for record in job_state.values()
                   if any(note.endswith((':budget', ':stopped', ':blocked', ':rate_limited'))
                          for note in record.get('notes', [])))
    remaining = max(0, len(pending) - processed)
    budget_note = deferred > 0
    status = 'ok'
    if failed_count or partial_count or remaining or lens_unavailable or budget_note:
        status = 'partial'
    summary = {
        'tool': TOOL,
        'version': VERSION,
        'status': status,
        'jobs': len(jobs),
        'badLines': bad,
        'pending': len(pending),
        'processed': processed,
        'ok': ok_count,
        'partial': partial_count,
        'failed': failed_count,
        'skipped': skipped_count,
        'remaining': remaining,
        'deferred': deferred,
        'stages': stages,
        'budget': {'used': budget_used, 'limits': {
            'lens': args.max_lens, 'yandex': args.max_yandex,
            'saucenao': args.max_saucenao, 'author': args.max_author}},
        'hits': stage_hits,
        'stageErrors': stage_errors,
        'results': display_path(out_path),
        'failures': display_path(failures_path),
        'notes': notes[:8],
    }

    if args.json_summary:
        print(json.dumps(summary, ensure_ascii=False, separators=(',', ':')))
    else:
        lines = [
            'research_kit pipeline: %d jobs (%d ok, %d partial, %d failed, %d skipped), %d processed, %d remaining'
            % (len(jobs), ok_count, partial_count, failed_count, skipped_count, processed, remaining),
            '  stages: %s%s' % (','.join(stages), ' (deep)' if args.deep else ''),
            '  hits: lens=%d yandex=%d saucenao=%d author=%d'
            % (stage_hits['lens'], stage_hits['yandex'], stage_hits['saucenao'], stage_hits['author']),
            '  budget: lens %d/%d, yandex %d/%d, saucenao %d/%d, author %d/%d'
            % (budget_used['lens'], args.max_lens, budget_used['yandex'], args.max_yandex,
               budget_used['saucenao'], args.max_saucenao, budget_used['author'], args.max_author),
            '  results: %s' % display_path(out_path),
            '  failures: %d line(s) -> %s' % (len(failures), display_path(failures_path)),
        ]
        lines.extend('  note: ' + note for note in notes[:4])
        if lens_unavailable:
            lines.append('  note: lens skipped (node unavailable)')
        if budget_note:
            lines.append('  note: some jobs were left for resume because a budget was reached')
        if len(lines) > args.summary_lines:
            lines = lines[:args.summary_lines - 1] + ['  ... %d more line(s)' % (len(lines) - args.summary_lines + 1)]
        print('\n'.join(lines))

    if status == 'ok':
        return EXIT_OK
    return EXIT_PARTIAL


if __name__ == '__main__':
    raise SystemExit(main())