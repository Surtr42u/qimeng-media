#!/usr/bin/env python3
"""旧三代维护目录只读核对与两代（当前/上次）布局复制迁移。

背景：维护目录原先把「最新 /（上次）/（上上次）」三代文件放在同一目录；
目标布局是「当前/」与「上次/」两个独立代目录，每代独立正文与 .sha256
摘要（docs/AI_MAINTENANCE_P0.md 4.4、docs/AI_PLATFORM_TASKBOOK.md 9.4）。

默认 --check 只读：逐文件核对可读性、.sha256 复算与新布局对应建议，输出
机器可读 JSON 摘要；显式 --apply 才在维护根内创建「当前/」「上次/」并复制
文件（当前=旧最新，上次=旧「（上次）」）。「（上上次）」只列入可清理清单，
不复制。本工具绝不删除任何文件：迁移成功后旧文件仍在，由用户核对后自行
清理；回退 = 继续使用旧配置与旧文件。

退出码：0=检查通过（可能含非阻塞警告）、1=存在阻塞问题/复制失败、2=输入或
参数错误。输出只含相对路径，不把含用户名的本机绝对路径写进报告。

选型：只依赖标准库（与 tools/ 既有工具约定一致）；新布局文件写入走
「同目录临时文件 + os.replace」原子替换，复制后复算源摘要，目标已存在且内容
不同则拒绝覆盖。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import stat
import sys
import tempfile
from pathlib import Path

TOOL_NAME = 'migrate_backup_layout'
FORMAT_VERSION = 1
MAX_BODY_BYTES = 64 * 1024 * 1024  # 与 Web/备份导出 64MiB 预算同量级
MAX_SUM_BYTES = 1024
SHA_SUFFIX = '.sha256'
HASH_CHUNK_BYTES = 1024 * 1024
TEMP_PREFIX = '.qm-migrate-'

CURRENT_DIR = '当前'
PREVIOUS_DIR = '上次'
LEGACY_PREVIOUS = '（上次）'
LEGACY_OLDER = '（上上次）'

# 旧布局参与三代轮换的已知文件（目录、主干名、扩展名、类别）；命名与
# Web maintenance-folder.ts / Android MaintenanceFolderRepository 一致。
GENERATION_ARTIFACTS = (
    ('作者资料', '作者总表', '.txt', 'authorMasterTxt'),
    ('备份', '影库备份', '.json', 'libraryBackup'),
)
# 只属于当前代的单份伴随文件（不参与轮换）：复制到 当前/<目录>/。
CURRENT_COMPANIONS = (
    ('作者资料', '作者资料.md', 'authorReport'),
    ('作者资料', '作者片段索引.json', 'authorFragmentIndex'),
)


class CheckError(Exception):
    """单个文件/目标的核对失败；由调用方转为 JSON 问题项。"""


class UsageError(Exception):
    """维护根不可用等输入错误（退出码 2）。"""


def lexists(path: Path) -> bool:
    """lexical 存在性：断链/联接也返回 True（不做符号链接跟随）。"""
    return os.path.lexists(path)


def is_real_file(path: Path) -> bool:
    """普通文件判定：拒绝符号链接、目录联接与其他特殊文件。"""
    try:
        info = path.lstat()
    except OSError:
        return False
    if stat.S_ISLNK(info.st_mode):
        return False
    try:
        if path.is_junction():
            return False
    except OSError:
        return False
    return stat.S_ISREG(info.st_mode)


def is_real_dir(path: Path) -> bool:
    try:
        if path.is_symlink() or path.is_junction():
            return False
        return path.is_dir()
    except OSError:
        return False


def relative(root: Path, path: Path) -> str:
    return path.relative_to(root).as_posix()


def digest_file(path: Path, max_bytes: int = MAX_BODY_BYTES) -> tuple[str, int]:
    """流式复算 SHA-256；拒绝链接/非普通文件与超预算文件，返回 (摘要, 字节数)。"""
    if not is_real_file(path):
        raise CheckError('不是可读的普通文件（链接/目录或特殊文件）')
    value = hashlib.sha256()
    size = 0
    try:
        with path.open('rb') as stream:
            for chunk in iter(lambda: stream.read(HASH_CHUNK_BYTES), b''):
                size += len(chunk)
                if size > max_bytes:
                    raise CheckError(f'文件超过 {max_bytes} 字节预算，拒绝读取')
                value.update(chunk)
    except OSError as error:
        raise CheckError(f'读取失败: {error.strerror or error}') from error
    return value.hexdigest(), size


def read_sidecar(path: Path) -> tuple[str, str]:
    """读取正文旁 .sha256；返回 (missing|unreadable|ok, 小写摘要文本)。"""
    sidecar = path.with_name(path.name + SHA_SUFFIX)
    if not lexists(sidecar):
        return 'missing', ''
    if not is_real_file(sidecar):
        return 'unreadable', ''
    try:
        data = sidecar.read_bytes()
    except OSError:
        return 'unreadable', ''
    if len(data) > MAX_SUM_BYTES:
        return 'unreadable', ''
    try:
        return 'ok', data.decode('ascii').strip().lower()
    except UnicodeDecodeError:
        return 'unreadable', ''


def write_atomic(path: Path, data: bytes) -> None:
    """同目录临时文件 + 原子替换；失败只清理本次临时文件。"""
    descriptor, temp_name = tempfile.mkstemp(prefix=TEMP_PREFIX, suffix='.tmp', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(data)
        os.replace(temp_name, path)
        temp_name = None
    finally:
        if temp_name is not None:
            try:
                os.unlink(temp_name)
            except FileNotFoundError:
                pass


def copy_verified(source: Path, destination: Path, expected_sha256: str) -> None:
    """复制并复算源摘要；源在核对后变化或目标被占用则放弃，不留临时文件。"""
    if not is_real_file(source):
        raise CheckError('源不是可读的普通文件')
    value = hashlib.sha256()
    size = 0
    descriptor, temp_name = tempfile.mkstemp(prefix=TEMP_PREFIX, suffix='.tmp', dir=destination.parent)
    try:
        with os.fdopen(descriptor, 'wb') as output, source.open('rb') as stream:
            for chunk in iter(lambda: stream.read(HASH_CHUNK_BYTES), b''):
                size += len(chunk)
                if size > MAX_BODY_BYTES:
                    raise CheckError(f'源文件在复制中超过 {MAX_BODY_BYTES} 字节预算')
                value.update(chunk)
                output.write(chunk)
        if value.hexdigest() != expected_sha256:
            raise CheckError('源文件在核对后发生变化，放弃本次复制')
        if lexists(destination):
            raise CheckError('目标在核对后出现，拒绝覆盖')
        os.replace(temp_name, destination)
        temp_name = None
    except OSError as error:
        raise CheckError(f'复制失败: {error.strerror or error}') from error
    finally:
        if temp_name is not None:
            try:
                os.unlink(temp_name)
            except FileNotFoundError:
                pass


def ensure_sidecar(body: Path, digest: str) -> None:
    """保证正文旁 .sha256 与正文复算一致；摘要是派生数据，可安全重写。"""
    sidecar = body.with_name(body.name + SHA_SUFFIX)
    expected = digest.encode('ascii')
    if is_real_file(sidecar):
        try:
            if sidecar.read_bytes().strip().lower() == expected:
                return
        except OSError:
            pass
    write_atomic(sidecar, expected)


def make_directory(root: Path, rel_directory: Path) -> None:
    """在维护根内逐段创建目标目录；任一既有路径不是普通目录即失败。"""
    current = root
    for part in rel_directory.parts:
        current = current / part
        if lexists(current):
            if not is_real_dir(current):
                raise CheckError(f'目标路径不是普通目录: {rel_directory.as_posix()}')
            continue
        try:
            current.mkdir()
        except OSError as error:
            raise CheckError(f'创建目录失败: {error.strerror or error}') from error


class Report:
    """JSON 摘要构建器：相对路径 + 计数 + 阻塞问题/警告分开。"""

    def __init__(self, root: Path, mode: str):
        self.root = root
        self.data = {
            'tool': TOOL_NAME,
            'formatVersion': FORMAT_VERSION,
            'mode': mode,
            'status': 'ok',
            'applied': False,
            'counts': {
                'artifacts': 0,
                'copy': 0,
                'unchanged': 0,
                'copied': 0,
                'skipped': 0,
                'cleanupCandidates': 0,
                'issues': 0,
                'warnings': 0,
            },
            'artifacts': [],
            'cleanupCandidates': [],
            'legacyFiles': [],
            'issues': [],
            'warnings': [],
        }

    def artifact(self, **fields) -> dict:
        entry = dict(fields)
        self.data['artifacts'].append(entry)
        return entry

    def legacy(self, path: Path) -> None:
        name = relative(self.root, path)
        if name not in self.data['legacyFiles']:
            self.data['legacyFiles'].append(name)

    def cleanup(self, path: Path) -> None:
        name = relative(self.root, path)
        if name not in self.data['cleanupCandidates']:
            self.data['cleanupCandidates'].append(name)

    def issue(self, code: str, path: str, message: str) -> None:
        self.data['issues'].append({'code': code, 'path': path, 'message': message})

    def warning(self, code: str, path: str, message: str) -> None:
        self.data['warnings'].append({'code': code, 'path': path, 'message': message})

    def finalize(self) -> None:
        artifacts = self.data['artifacts']
        self.data['status'] = 'issues' if self.data['issues'] else 'ok'
        self.data['artifacts'] = sorted(artifacts, key=lambda entry: entry['target'])
        self.data['cleanupCandidates'].sort()
        self.data['legacyFiles'].sort()
        counts = self.data['counts']
        counts['artifacts'] = len(artifacts)
        counts['copy'] = sum(1 for entry in artifacts if entry['action'] == 'copy')
        counts['unchanged'] = sum(1 for entry in artifacts if entry['action'] == 'unchanged')
        counts['copied'] = sum(1 for entry in artifacts if entry['action'] == 'copied')
        counts['skipped'] = sum(1 for entry in artifacts if entry['action'] == 'skip')
        counts['cleanupCandidates'] = len(self.data['cleanupCandidates'])
        counts['issues'] = len(self.data['issues'])
        counts['warnings'] = len(self.data['warnings'])


def validate_root(root: Path) -> None:
    if not lexists(root):
        raise UsageError('维护根目录不存在')
    if not is_real_dir(root):
        raise UsageError('维护根必须是普通目录（不接受链接/联接/文件）')


def target_conflict(report: Report, target: Path, expected_sha256: str) -> str | None:
    """核对既有目标：返回 action（unchanged/skip）；无目标返回 None。

    目标已存在且与源内容不同时记阻塞问题，绝不覆盖来历不明的文件。
    """
    if not lexists(target):
        return None
    name = relative(report.root, target)
    if not is_real_file(target):
        report.issue('target_invalid', name, '目标已存在但不是普通文件，拒绝写入')
        return 'skip'
    try:
        digest, _ = digest_file(target)
    except CheckError as error:
        report.issue('target_unreadable', name, f'目标不可读: {error}')
        return 'skip'
    if digest != expected_sha256:
        report.issue('target_occupied', name, '目标已存在且内容与本次迁移不同，拒绝覆盖；请先人工核对')
        return 'skip'
    return 'unchanged'


def plan_generation_group(report: Report, folder: str, stem: str, extension: str, kind: str) -> None:
    """规划一组三代轮换文件：最新→当前，旧「（上次）」→上次，上上次只列清理。"""
    root = report.root
    latest_name = f'{stem}{extension}'
    previous_name = f'{stem}{LEGACY_PREVIOUS}{extension}'
    older_name = f'{stem}{LEGACY_OLDER}{extension}'
    plans = (
        (latest_name, 'current', CURRENT_DIR, 'latest'),
        (previous_name, 'previous', PREVIOUS_DIR, 'previous'),
    )
    present = 0
    for legacy_name, generation, generation_dir, role in plans:
        source = root / folder / legacy_name
        if not lexists(source):
            continue
        present += 1
        source_name = relative(root, source)
        report.legacy(source)
        sidecar = source.with_name(source.name + SHA_SUFFIX)
        if lexists(sidecar):
            report.legacy(sidecar)
        sidecar_status, sidecar_text = read_sidecar(source)
        try:
            digest, size = digest_file(source)
        except CheckError as error:
            report.issue('source_unreadable', source_name, f'旧文件不可读: {error}')
            continue
        if sidecar_status == 'ok' and sidecar_text != digest:
            report.issue('sha_mismatch', source_name, '旧文件 .sha256 与正文复算不符，可能已被替换或未写完')
            continue
        if sidecar_status == 'unreadable':
            report.issue('sha_unreadable', source_name, '旧文件 .sha256 不可读，无法核对正文')
            continue
        if sidecar_status == 'missing':
            report.warning('sha_missing', source_name, '旧文件缺少 .sha256；复制时按正文复算生成新摘要')
        target = root / generation_dir / folder / latest_name
        entry = report.artifact(
            kind=kind,
            role=role,
            generation=generation,
            source=source_name,
            target=relative(root, target),
            sha256=digest,
            size=size,
            sha256File=sidecar_status,
            action='copy',
        )
        action = target_conflict(report, target, digest)
        if action is not None:
            entry['action'] = action
    if present == 0:
        report.warning('group_missing', folder, f'未发现旧代文件组 {folder}/{stem}{extension}，跳过')
        return
    latest = root / folder / latest_name
    previous = root / folder / previous_name
    if not lexists(latest):
        report.issue('current_missing', relative(root, root / folder), '存在旧「（上次）」但缺少最新代，无法确定当前代')
    elif not lexists(previous):
        report.warning('previous_missing', relative(root, previous), '缺少旧「（上次）」；迁移后上次代为空')
    # 上上次只列入可清理清单：核对可读性与摘要但不复制、不删除。
    older = root / folder / older_name
    if lexists(older):
        older_relative = relative(root, older)
        report.legacy(older)
        report.cleanup(older)
        older_sidecar = older.with_name(older.name + SHA_SUFFIX)
        if lexists(older_sidecar):
            report.legacy(older_sidecar)
            report.cleanup(older_sidecar)
        try:
            older_digest, _ = digest_file(older)
        except CheckError as error:
            report.warning('source_unreadable', older_relative, f'上上次文件不可读（仅列入清理清单）: {error}')
            older_digest = None
        if older_digest is not None:
            sidecar_status, sidecar_text = read_sidecar(older)
            if sidecar_status == 'ok' and sidecar_text != older_digest:
                report.warning('sha_mismatch', older_relative, '上上次文件摘要与正文不符（仅提示，不阻塞迁移）')
            elif sidecar_status in ('missing', 'unreadable'):
                report.warning('sha_' + sidecar_status, older_relative, '上上次文件摘要缺失/不可读（仅提示，不阻塞迁移）')
    # 孤立摘要：正文已被移走时提示用户核对，绝不删除。
    for legacy_name in (latest_name, previous_name, older_name):
        sidecar = root / folder / (legacy_name + SHA_SUFFIX)
        if lexists(sidecar) and not lexists(root / folder / legacy_name):
            report.warning('orphan_sidecar', relative(root, sidecar), '摘要缺少对应正文，未纳入迁移；请人工核对')


def plan_companion(report: Report, folder: str, filename: str, kind: str) -> None:
    """规划只属于当前代的单份伴随文件（作者资料.md / 作者片段索引.json）。"""
    root = report.root
    source = root / folder / filename
    if not lexists(source):
        return
    source_name = relative(root, source)
    report.legacy(source)
    try:
        digest, size = digest_file(source)
    except CheckError as error:
        report.issue('source_unreadable', source_name, f'旧文件不可读: {error}')
        return
    target = root / CURRENT_DIR / folder / filename
    entry = report.artifact(
        kind=kind,
        role='companion',
        generation='current',
        source=source_name,
        target=relative(root, target),
        sha256=digest,
        size=size,
        sha256File='absent',
        action='copy',
    )
    action = target_conflict(report, target, digest)
    if action is not None:
        entry['action'] = action


def plan_target_directories(report: Report) -> None:
    """预检目标代目录：既有的 当前/上次 及其子目录必须是普通目录。"""
    root = report.root
    folders = sorted({spec[0] for spec in GENERATION_ARTIFACTS} | {spec[0] for spec in CURRENT_COMPANIONS})
    for generation_dir in (CURRENT_DIR, PREVIOUS_DIR):
        generation_path = root / generation_dir
        if lexists(generation_path) and not is_real_dir(generation_path):
            report.issue('target_invalid', relative(root, generation_path), '目标代路径已存在但不是普通目录，拒绝写入')
            continue
        for folder in folders:
            folder_path = generation_path / folder
            if lexists(folder_path) and not is_real_dir(folder_path):
                report.issue('target_invalid', relative(root, folder_path), '目标代目录已存在但不是普通目录，拒绝写入')


def build_report(root: Path, mode: str) -> dict:
    validate_root(root)
    report = Report(root, mode)
    plan_target_directories(report)
    for folder, stem, extension, kind in GENERATION_ARTIFACTS:
        plan_generation_group(report, folder, stem, extension, kind)
    for folder, filename, kind in CURRENT_COMPANIONS:
        plan_companion(report, folder, filename, kind)
    report.finalize()
    if mode == 'apply' and report.data['status'] == 'ok':
        perform_apply(report)
        report.finalize()
        report.data['applied'] = report.data['status'] == 'ok'
    return report.data


def perform_apply(report: Report) -> None:
    """按只读计划复制：只创建/写入 当前/上次，绝不删除或移动旧文件。"""
    root = report.root
    for entry in report.data['artifacts']:
        if entry['action'] != 'copy':
            continue
        source = root / Path(entry['source'])
        target = root / Path(entry['target'])
        try:
            make_directory(root, Path(entry['target']).parent)
            if lexists(target):
                # 计划后目标才出现：内容一致按已迁移处理，不同则拒绝覆盖。
                digest, _ = digest_file(target)
                if digest != entry['sha256']:
                    raise CheckError('目标已存在且内容不同，拒绝覆盖')
                ensure_sidecar(target, entry['sha256'])
                entry['action'] = 'unchanged'
                continue
            copy_verified(source, target, entry['sha256'])
            ensure_sidecar(target, entry['sha256'])
            entry['action'] = 'copied'
        except (CheckError, OSError) as error:
            report.issue('apply_failed', entry['target'], f'复制失败（已复制的文件保留，可重试）: {error}')
            return


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description='旧三代维护目录核对与两代（当前/上次）布局复制迁移；绝不删除文件。',
    )
    parser.add_argument('--root', required=True, help='旧三代布局的维护根目录（含 作者资料/、备份/）')
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--check', action='store_true', help='只读核对（默认）')
    group.add_argument('--apply', action='store_true', help='复制生成 当前/ 与 上次/（不删除、不移动旧文件）')
    args = parser.parse_args(argv)
    mode = 'apply' if args.apply else 'check'
    try:
        report = build_report(Path(args.root), mode)
    except UsageError as error:
        report = {
            'tool': TOOL_NAME,
            'formatVersion': FORMAT_VERSION,
            'mode': mode,
            'status': 'invalid',
            'applied': False,
            'issues': [{'code': 'invalid_input', 'path': '', 'message': str(error)}],
        }
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 2
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report['status'] == 'ok' else 1


if __name__ == '__main__':
    sys.exit(main())
