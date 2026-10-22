"""只读历史数据对账；不读取、解码或预览媒体内容。

复用 SQLite/文件系统标准库，不新增运行时依赖。这里只作诊断，不替代服务端
导入/统计实现；若服务端提供同等只读审计接口，本工具可整件退役。
统计校验跟随 stats.MaterializeDaily，备份校验跟随 DOMAIN_RULES 的迁移专题。
"""
import argparse
from collections import Counter, defaultdict
from datetime import datetime
import json
from pathlib import Path
import sqlite3
import stat
import time

from audit_backup import compare_backup

DB_TIMEOUT_SECONDS = 5
BACKUP_MAX_BYTES = 64 * 1024 * 1024  # 与便携备份协议请求上限一致。
REPORT_VERSION = 1
TABLES = ('libraries', 'assets', 'authors', 'tags', 'asset_authors', 'asset_tags',
          'favorites', 'likes', 'timeline_tags', 'view_events', 'asset_daily_stats',
          'portable_event_identity', 'kv_settings')


def issue(report, code, reference=None):
    # 报告只有问题类别/序号，不泄露文件名、绝对路径、事件内容或作者名称。
    entry = {'code': code}
    if reference is not None:
        entry['reference'] = reference
    report['issues'].append(entry)


def reject_links(path):
    for part in (path, *path.parents):
        if part.is_symlink() or part.is_junction():
            raise ValueError('linked-path')


def media_path(root, relative):
    # 不让受损数据库路径触发库外 stat，也拒绝 Windows ADS 和重解析路径。
    parts = relative.split('/')
    if (not relative or '\\' in relative or ':' in relative
            or any(p in ('', '.', '..') or p != p.rstrip(' .') for p in parts)):
        raise ValueError('unsafe-relative-path')
    path = root.joinpath(*parts)
    reject_links(path)
    return path


def snapshot(database):
    database = database.absolute()
    reject_links(database)
    if not database.is_file():
        raise ValueError('database-not-found')
    # mode=ro 保留 WAL 可见性；禁止 immutable=1（会漏掉运行库的已提交 WAL）。
    conn = sqlite3.connect(database.as_uri() + '?mode=ro', uri=True,
                           timeout=DB_TIMEOUT_SECONDS)
    conn.row_factory = sqlite3.Row
    try:
        conn.execute('PRAGMA query_only=ON')
        conn.execute('BEGIN')  # deferred 读事务；不获取写锁，不重建、不清理。
        integrity = [r[0] for r in conn.execute('PRAGMA quick_check')]
        foreign_keys = len(conn.execute('PRAGMA foreign_key_check').fetchall())
        data = {t: [dict(r) for r in conn.execute('SELECT * FROM ' + t)] for t in TABLES}
        return data, integrity == ['ok'], foreign_keys
    finally:
        conn.close()  # 在逐文件 stat 前释放读快照，不长期拖住 WAL checkpoint。


def check_files(data, report):
    libraries = {r['id']: r for r in data['libraries']}
    accessible = {}
    for index, library in enumerate(data['libraries'], 1):
        root = Path(library['root_path'])
        try:
            if not root.is_absolute():
                raise ValueError('relative-library-root')
            reject_links(root)
            if not root.is_dir():
                raise ValueError('unavailable-library-root')
            accessible[library['id']] = root
        except (OSError, ValueError):
            issue(report, 'library-root-unavailable-or-unsafe', index)
    checked = matched = total_bytes = 0
    for index, asset in enumerate(data['assets'], 1):
        if asset['library_id'] not in libraries:
            issue(report, 'asset-library-missing', index)
        root = accessible.get(asset['library_id'])
        if root is None:
            continue
        checked += 1
        try:
            info = media_path(root, asset['rel_path']).stat()
            if not stat.S_ISREG(info.st_mode):
                issue(report, 'media-not-regular-file', index)
            elif info.st_size != asset['size_bytes']:
                issue(report, 'media-size-mismatch', index)
            else:
                matched += 1
                total_bytes += info.st_size
        except FileNotFoundError:
            issue(report, 'media-missing', index)
        except (OSError, ValueError):
            issue(report, 'media-path-unsafe-or-unreadable', index)
    report['files'] = {'registered': len(data['assets']), 'checked': checked,
                       'sizeMatched': matched, 'matchedBytes': total_bytes,
                       'libraryRoots': len(libraries), 'accessibleRoots': len(accessible)}


def check_events(data, report):
    live = {a['asset_id'] for a in data['assets']}
    expected = defaultdict(lambda: [0, 0, 0])
    historic = invalid = 0
    identities = {r['event_id']: r['stable_id'] for r in data['portable_event_identity']}
    keys = Counter()
    for index, event in enumerate(data['view_events'], 1):
        key = event['client_event_id'] if event['client_event_id'] is not None else identities.get(event['id'])
        if not key:
            issue(report, 'event-portable-identity-missing', index)
        else:
            keys[key] += 1
        if event['asset_id'] not in live:
            historic += 1  # 删除资产的历史事件按设计保留，不误判为孤儿数据损坏。
            continue
        try:
            at = datetime.fromisoformat(event['started_at'].replace('Z', '+00:00'))
            if at.tzinfo is None:
                raise ValueError('timezone-missing')
            day = at.astimezone().date().isoformat()
            kind = event['kind']
            if kind not in ('open', 'play', 'dwell'):
                raise ValueError('event-kind-invalid')
            seconds = event['seconds'] or 0
            if not isinstance(seconds, int) or seconds < 0:
                raise ValueError('event-seconds-invalid')
            value = expected[(event['asset_id'], day)]
            value[0] += int(kind == 'open')
            value[1] += int(kind == 'play')
            value[2] += seconds if kind == 'dwell' else 0
        except (ValueError, TypeError, OverflowError):
            invalid += 1
            issue(report, 'event-invalid', index)
    duplicate_keys = sum(n - 1 for n in keys.values() if n > 1)
    if duplicate_keys:
        issue(report, 'duplicate-portable-event-identity')
    actual = {(r['asset_id'], r['day']): [r['view_count'], r['play_count'], r['browse_seconds']]
              for r in data['asset_daily_stats']}
    differences = sum(expected.get(k) != actual.get(k) for k in expected.keys() | actual.keys())
    if differences:
        issue(report, 'daily-statistics-mismatch')
    report['events'] = {'total': len(data['view_events']), 'historicalNonresidentAssets': historic,
                        'invalid': invalid, 'duplicateIdentities': duplicate_keys,
                        'expectedDailyRows': len(expected), 'actualDailyRows': len(actual),
                        'dailyRowDifferences': differences,
                        'localTimezone': str(datetime.now().astimezone().tzinfo)}


def check_staging(database, report):
    # 只查看服务端明确的上传暂存目录；不递归媒体目录或清理任何文件。
    root = database.absolute().parent / 'uploads-tmp'
    count = 0
    def fail(error):
        raise error
    try:
        reject_links(root)
        if root.exists():
            for directory, dirs, files in root.walk(follow_symlinks=False, on_error=fail):
                for name in dirs + files:
                    reject_links(directory / name)
                count += len(files)
    except (OSError, ValueError):
        issue(report, 'upload-staging-unreadable-or-unsafe')
    report['staging'] = {'filesPresent': count,
                         'interpretation': 'may-be-active-upload' if count else 'empty'}


def audit(database, backup=None):
    started = time.monotonic()
    data, healthy, foreign_keys = snapshot(database)
    report = {'reportVersion': REPORT_VERSION, 'readOnly': True, 'mediaContentRead': False,
              'issues': [], 'database': {'quickCheckOk': healthy, 'foreignKeyIssues': foreign_keys},
              'counts': {t: len(rows) for t, rows in data.items()}}
    if not healthy:
        issue(report, 'database-quick-check-failed')
    if foreign_keys:
        issue(report, 'database-foreign-key-violations')
    check_files(data, report)
    check_events(data, report)
    check_staging(database, report)
    if backup is None:
        report['backup'] = {'status': 'not-supplied', 'compared': False}
    else:
        reject_links(backup.absolute())
        with backup.open('rb') as stream:
            payload = stream.read(BACKUP_MAX_BYTES + 1)
        if len(payload) > BACKUP_MAX_BYTES:
            raise ValueError('backup-too-large')
        report['backup'] = compare_backup(data, json.loads(payload.decode('utf-8-sig')))
        if report['backup']['differences']:
            issue(report, 'backup-differences-found')
    report['issueCounts'] = dict(Counter(r['code'] for r in report['issues']))
    report['status'] = 'differences-found' if report['issues'] else 'passed-within-scope'
    report['elapsedSeconds'] = round(time.monotonic() - started, 3)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--db', type=Path, required=True)
    parser.add_argument('--backup', type=Path)
    args = parser.parse_args()
    try:
        report = audit(args.db, args.backup)
    except (OSError, ValueError, TypeError, KeyError, sqlite3.Error):
        # 异常原文可能包含绝对路径或私人记录，不打印。
        print(json.dumps({'status': 'incomplete', 'reason': 'input-or-database-unreadable-or-unsupported'}))
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return int(bool(report['issues']))


if __name__ == '__main__':
    raise SystemExit(main())
