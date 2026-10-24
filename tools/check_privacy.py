"""隐私文件/元数据门禁；密钥检测复用固定版本 Gitleaks，不自研密钥识别器。

本仓补充规则覆盖数据库、私人备份/报告、个人路径/邮箱和真机标识。
若成熟 DLP 工具可以覆盖这些项目约束，整件替换本补充检查。
只输出类别/数量；所有异常原文和命中内容均不输出到控制台/云端。
"""
import argparse
from collections import Counter
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

GITLEAKS_VERSION = '8.30.1'
TEXT_LIMIT_BYTES = 16 * 1024 * 1024
MEDIA_SUFFIXES = {'.png', '.jpg', '.jpeg', '.webp', '.gif', '.ico', '.icns', '.mp4',
                  '.mov', '.webm', '.mp3', '.wav', '.woff', '.woff2', '.ttf', '.pdf'}
PRIVATE_SUFFIXES = {'.db', '.sqlite', '.sqlite3', '.jks', '.p12', '.pfx', '.key', '.pem', '.keystore', '.log'}
PUBLIC_MEDIA_PREFIXES = ('android/app/src/main/res/', 'desktop/src-tauri/icons/', 'web/public/covers/')
PUBLIC_MEDIA_NAMES = {'desktop/app-icon.png', 'desktop/ui/app-icon.png', 'web/public/favicon.ico',
                      'web/public/apple-touch-icon-180x180.png',
                      'web/public/maskable-icon-512x512.png', 'web/public/pwa-192x192.png',
                      'web/public/pwa-512x512.png', 'web/public/pwa-64x64.png'}
PERSONAL_EMAIL = re.compile(rb'(?i)\b[\w.+%-]+@(?:qq|163|126|gmail|outlook|hotmail)\.com\b')
PROFILE = re.compile(rb'(?i)[a-z]:[\\/]+Users[\\/]+([^\\/\s\x00\"\'<>]+)')
# 这些是项目/上游代码中明确的模板或合成路径，不是通配忽略真实用户名。
PROFILE_EXAMPLES = {'你', '某用户', '用户名', 'user', 'username', 'foo', 'alice', 'bob', 'camera', 'public', 'default', 'runneradmin', 'runner~1'}
DEVICE_SERIAL = re.compile(rb'(?i)(?:adb\s+-s\s+|\bSERIAL\s*=\s*[\"\'])([0-9]{10,})')
LOCAL_RESULT = re.compile(r'(?:现场只读对账|用户手机.{0,30}备份).{0,120}\b[0-9]{3,}\b.{0,80}(?:文件|事件|作者|标签|关联)')


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root, stderr=subprocess.DEVNULL)


def private_path(name):
    path = Path(name)
    parts = name.replace('\\', '/').split('/')
    base = path.name.lower()
    if any(p in ('qimeng-data', 'local-sync', '.run-screens', '.walk') for p in parts):
        return True
    if base in ('qimeng_backup.json', 'media-secret') or '-private.' in base or '.private.' in base:
        return True
    if base == '.env' or (base.startswith('.env.') and not base.endswith(('.example', '.template', '.sample'))):
        return True
    if base.endswith(('.local', '.local.yaml', '.local.yml', '.local.json', '.db-wal', '.db-shm', '.db-journal')):
        return True
    if path.suffix.lower() in PRIVATE_SUFFIXES:
        return True
    if re.search(r'\.(?:db|sqlite3?)-(?:wal|shm|journal)$', base):
        return True
    if path.suffix.lower() in MEDIA_SUFFIXES:
        return name not in PUBLIC_MEDIA_NAMES and not name.startswith(PUBLIC_MEDIA_PREFIXES)
    return False


def content_codes(name, content, local_values=()):
    found = set()
    if PERSONAL_EMAIL.search(content):
        found.add('personal-email')
    for match in PROFILE.finditer(content):
        if match[1].decode('utf-8', 'replace').lower() not in PROFILE_EXAMPLES:
            found.add('personal-profile-path')
    if DEVICE_SERIAL.search(content):
        found.add('device-serial-literal')
    for value in local_values:
        if re.search(rb'(?<![a-zA-Z0-9])' + re.escape(value.encode()) + rb'(?![a-zA-Z0-9])', content):
            found.add('local-private-identifier')
    if name.endswith('.md') and LOCAL_RESULT.search(content.decode('utf-8', 'replace')):
        found.add('private-usage-summary')
    # 私人 JSON 改了扩展名也不能绕过；只解析看起来是对象的文本。
    if content.lstrip().startswith(b'{'):
        try:
            data = json.loads(content)
            if isinstance(data, dict) and (data.get('format') == 'qimeng_backup'
                    or {'reportVersion', 'readOnly', 'mediaContentRead'} <= data.keys()):
                found.add('private-backup-or-audit-payload')
        except (ValueError, UnicodeError):
            pass
    return found


def local_policy(root):
    path = Path(root) / '.privacy-policy.local.json'
    if not path.exists():
        return []
    values = json.loads(path.read_text(encoding='utf-8')).get('privateIdentifiers', [])
    if not isinstance(values, list) or any(not isinstance(v, str) or len(v) < 5 for v in values):
        raise ValueError('invalid-local-policy')
    return values


def inspect_blob(name, content, values):
    if private_path(name):
        return {'private-file-path'}
    if Path(name).suffix.lower() in MEDIA_SUFFIXES:
        return set()  # 媒体只查路径/清单，不读取/解码媒体字节。
    if len(content) > TEXT_LIMIT_BYTES:
        return {'oversized-unreviewed-file'}
    if content.startswith(b'SQLite format 3\0'):
        return {'private-database-payload'}
    if b'\x00' in content:
        return set()
    return content_codes(name, content, values)


def scan(root, mode):
    root = Path(root)
    values = local_policy(root)
    counts = Counter()
    if mode == 'history':
        metadata = git(root, 'log', '--all', '--format=%aE%n%cE%B')
        if PERSONAL_EMAIL.search(metadata):
            counts['personal-email-in-commit-metadata'] += 1
        records = git(root, 'rev-list', '--objects', '--all').splitlines()
        objects = {}
        for line in records:
            fields = line.split(b' ', 1)
            if len(fields) == 2:
                objects[fields[0].decode()] = fields[1].decode('utf-8')
        # 先区分 tree/blob，不尝试把 tree 内容当成文本。
        inputs = ''.join(oid + '\n' for oid in objects).encode()
        types = subprocess.run(['git', 'cat-file', '--batch-check=%(objectname) %(objecttype)'],
                               input=inputs, capture_output=True, check=True, cwd=root).stdout
        blobs = [(r.split()[0].decode(), objects[r.split()[0].decode()]) for r in types.splitlines()
                 if r.split()[1] == b'blob']
        process = subprocess.Popen(['git', 'cat-file', '--batch'], cwd=root,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        try:
            for oid, name in blobs:
                if private_path(name):
                    counts['private-file-path'] += 1
                    continue
                if Path(name).suffix.lower() in MEDIA_SUFFIXES:
                    continue
                process.stdin.write((oid + '\n').encode())
                process.stdin.flush()
                size = int(process.stdout.readline().split()[2])
                content = process.stdout.read(size)
                process.stdout.read(1)
                counts.update(inspect_blob(name, content, values))
        finally:
            process.stdin.close()
            process.stdout.close()
            if process.wait() != 0:
                raise ValueError('git-object-read-failed')
    else:
        if mode == 'staged':
            names = git(root, 'diff', '--cached', '--name-only', '-z', '--diff-filter=ACMR').split(b'\0')
        else:
            names = git(root, 'ls-files', '--cached', '--others', '--exclude-standard', '-z').split(b'\0')
        for raw in set(names) - {b''}:
            name = raw.decode('utf-8')
            if private_path(name):
                counts['private-file-path'] += 1
                continue
            if Path(name).suffix.lower() in MEDIA_SUFFIXES:
                continue
            if mode == 'staged':
                content = git(root, 'show', ':' + name)  # 检查真正将提交的 index，不被工作区覆盖掩盖。
            else:
                path = root / name
                if not path.exists():
                    continue
                if path.is_symlink() or path.is_junction():
                    continue
                if path.stat().st_size > TEXT_LIMIT_BYTES:
                    counts['oversized-unreviewed-file'] += 1
                    continue
                content = path.read_bytes()
            counts.update(inspect_blob(name, content, values))
        for identity in ('GIT_AUTHOR_IDENT', 'GIT_COMMITTER_IDENT'):
            result = subprocess.run(['git', 'var', identity], cwd=root, capture_output=True)
            if result.returncode and mode == 'staged':
                raise ValueError('git-identity-unconfigured')
            if PERSONAL_EMAIL.search(result.stdout):
                counts['personal-email-in-git-identity'] += 1
    return counts


def run_gitleaks(root, mode):
    configured = subprocess.run(['git', 'config', '--get', 'privacy.gitleaksPath'],
                                cwd=root, capture_output=True).stdout.decode().strip()
    binary = os.environ.get('QIMENG_GITLEAKS') or configured
    binary = binary or shutil.which('gitleaks')
    if not binary:
        raise ValueError('gitleaks-required')
    version = subprocess.check_output([binary, 'version'], stderr=subprocess.DEVNULL).decode().strip()
    if version != GITLEAKS_VERSION:
        raise ValueError('scanner-version-mismatch')
    options = ['--pre-commit', '--staged'] if mode == 'staged' else ['--log-opts=--all --full-history']
    with tempfile.TemporaryDirectory(prefix='qimeng-privacy-') as folder:
        report = Path(folder) / 'findings.json'
        result = subprocess.run([binary, 'git', *options, '--redact', '--no-banner', '--no-color',
                                 '--log-level=error', '--config', str(Path(root) / '.gitleaks.toml'),
                                 '--report-format=json', '--report-path', str(report), str(root)],
                                capture_output=True)
        if result.returncode not in (0, 1):
            raise ValueError('gitleaks-incomplete')
        findings = json.loads(report.read_text(encoding='utf-8')) if report.exists() else []
        return len(findings)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=('staged', 'worktree', 'history'), default='staged')
    parser.add_argument('--gitleaks', action='store_true')
    args = parser.parse_args()
    try:
        root = Path(git(Path.cwd(), 'rev-parse', '--show-toplevel').decode().strip())
        counts = scan(root, args.mode)
        if args.gitleaks:
            leaks = run_gitleaks(root, args.mode)
            if leaks:
                counts['gitleaks-secret-finding'] += leaks
        print(json.dumps({'status': 'blocked' if counts else 'passed', 'mode': args.mode,
                          'categories': dict(counts)}, ensure_ascii=False))
        return int(bool(counts))
    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
        print(json.dumps({'status': 'incomplete', 'reason': 'privacy-check-could-not-complete'}))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
