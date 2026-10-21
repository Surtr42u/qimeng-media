"""AI 执行的停写目录副本/核对工具；只用标准库复制和 SHA-256，不操作运行实例。

通行方案是现有在线 SQLite 快照或平台备份；本工具只补跨数据目录/媒体目录的
逐文件清单核对，不替代在线快照/快照调度或实现增量备份。若平台备份可导出并
验证同等清单即可整件退役。必须先正常停服、暂停客户端写入/同步，再显式 --offline。
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import stat

MANIFEST = 'qimeng-copy-manifest.json'
FORMAT_VERSION = 1
HASH_CHUNK_BYTES = 1024 * 1024


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(HASH_CHUNK_BYTES), b''):
            value.update(chunk)
    return value.hexdigest()


def regular(path: Path) -> None:
    if path.is_symlink() or path.is_junction() or not stat.S_ISREG(path.stat().st_mode):
        raise ValueError('拒绝链接或非普通文件')


def files(root: Path) -> list[Path]:
    if root.is_symlink() or root.is_junction() or not root.is_dir():
        raise ValueError('副本根必须为普通目录')
    result = []
    for path in root.rglob('*'):
        if path.is_symlink() or path.is_junction():
            raise ValueError('副本范围内存在链接，停止复制/核对')
        if path.is_file():
            regular(path)
            result.append(path)
        elif not path.is_dir():
            raise ValueError('副本范围内存在非普通文件')
    return sorted(result)


def copy_checked(source: str, destination: str) -> str:
    src, dst = Path(source), Path(destination)
    regular(src)
    before = src.stat()
    expected = digest(src)
    shutil.copy2(src, dst)
    after = src.stat()
    if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns) or digest(src) != expected or digest(dst) != expected:
        raise ValueError('源在复制中变化或目标字节不一致；保留未完成副本供排查')
    return str(dst)


def create(sources: list[Path], destination: Path, offline: bool) -> dict:
    if not offline:
        raise ValueError('必须先停服/停写；缺少 --offline，拒绝复制运行库')
    if not sources or destination.exists() or destination.is_symlink():
        raise ValueError('需要源目录且目标必须不存在')
    dest = destination.resolve()
    resolved = []
    for source in sources:
        # resolve 前检查根本身，不把链接自动转换成合法根。
        files(source)
        src = source.resolve()
        if src == dest or src in dest.parents or dest in src.parents:
            raise ValueError('源和目标目录不得互相包含')
        if any(src == old or src in old.parents or old in src.parents for old in resolved):
            raise ValueError('源目录重复或重叠')
        resolved.append(src)
    destination.mkdir(parents=False)
    for index, source in enumerate(resolved):
        # 数字目录避免把含用户名的源绝对路径写进清单；对应顺序由执行 AI 保存在忽略的本地记录。
        shutil.copytree(source, destination / f'root-{index + 1}', copy_function=copy_checked)
    entries = []
    for path in files(destination):
        info = path.stat()
        entries.append({'path': path.relative_to(destination).as_posix(), 'size': info.st_size,
                        'mtimeNs': info.st_mtime_ns, 'sha256': digest(path)})
    manifest = {'formatVersion': FORMAT_VERSION, 'roots': len(resolved), 'files': entries}
    # 最后才产生清单；中途失败的目录没有清单，不能被 verify 当成成功副本。
    marker = destination / MANIFEST
    with marker.open('x', encoding='utf-8') as stream:
        stream.write(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    try:
        verify(destination)
    except Exception:
        marker.unlink()  # 只移除本次创建的完成清单；副本/原件不删除。
        raise
    return manifest


def verify(root: Path) -> dict:
    inventory = files(root)  # 先拒绝父段链接，再读取清单与其指向的文件。
    regular(root / MANIFEST)
    manifest = json.loads((root / MANIFEST).read_text(encoding='utf-8'))
    if manifest.get('formatVersion') != FORMAT_VERSION or not isinstance(manifest.get('files'), list):
        raise ValueError('不支持或无效的副本清单')
    expected = set()
    for entry in manifest['files']:
        name = entry['path']
        relative = PurePosixPath(name)
        parts = name.split('/')
        if not name or relative.is_absolute() or '\\' in name or ':' in name or any(part in ('', '.', '..') or part != part.rstrip(' .') for part in parts) or name in expected or name == MANIFEST:
            raise ValueError('清单路径无效/重复')
        expected.add(name)
        path = root.joinpath(*relative.parts)
        regular(path)
        info = path.stat()
        if info.st_size != entry['size'] or info.st_mtime_ns != entry['mtimeNs'] or digest(path) != entry['sha256']:
            raise ValueError('副本字节或时间戳与清单不一致')
    actual = {p.relative_to(root).as_posix() for p in inventory if p.name != MANIFEST or p.parent != root}
    if actual != expected:
        raise ValueError('副本包含未登记文件或缺少文件')
    return manifest


def restore(backup: Path, destination: Path) -> dict:
    verify(backup)
    if destination.exists() or destination.is_symlink():
        raise ValueError('恢复目标必须不存在；禁止覆盖既有数据或真实 WAL')
    source, target = backup.resolve(), destination.resolve()
    if source == target or source in target.parents or target in source.parents:
        raise ValueError('恢复源和目标不得互相包含')
    shutil.copytree(backup, destination, copy_function=copy_checked)
    return verify(destination)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    create_cmd = commands.add_parser('create')
    create_cmd.add_argument('--source', type=Path, action='append', required=True)
    create_cmd.add_argument('--destination', type=Path, required=True)
    create_cmd.add_argument('--offline', action='store_true')
    verify_cmd = commands.add_parser('verify')
    verify_cmd.add_argument('--backup', type=Path, required=True)
    restore_cmd = commands.add_parser('restore')
    restore_cmd.add_argument('--backup', type=Path, required=True)
    restore_cmd.add_argument('--destination', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'create':
        result = create(args.source, args.destination, args.offline)
    elif args.command == 'restore':
        result = restore(args.backup, args.destination)
    else:
        result = verify(args.backup)
    print(f'Cold copy {args.command}: OK, {len(result["files"])} file(s); target hardware not certified')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
